package plg.stream.model;

import java.util.Date;
import java.util.Timer;
import java.util.TimerTask;

import mqttxes.lib.XesMqttProducer;
import mqttxes.lib.exceptions.XesMqttClientNotConnectedException;
import plg.generator.log.SimulationConfiguration;
import plg.generator.log.TraceGenerator;
import plg.model.Process;
import plg.stream.configuration.StreamConfiguration;
import plg.utils.Logger;

/**
 * This class provides the main access to the stream capabilities. It is
 * possible to construct a new streamer with the provided constructor, and then
 * use the {@link #startStream()} and {@link #endStream()} methods in order to
 * activate or stop the events streaming.
 *
 * <p>
 * The method {@link #updateProcess(Process)} can be called without stopping the
 * running stream. This can be used to simulate <em>concept drifts</em>.
 *
 * @author Andrea Burattin
 */
public class Streamer extends Thread {

    private String processName;
    private Process process;
    private SimulationConfiguration simulationParameters;
    private StreamConfiguration configuration;

    private XesMqttProducer producer;
    private StreamBuffer buffer;
    private long streamedEvents = 0;
    private long generatedInstances = 0;
    private long timeLastEvent = -1;

    private boolean enabled = false;
    private Timer infoTimer = null;

    /**
     * Basic streamer constructor
     *
     * @param configuration the stream configuration
     * @param processName the name of the process
     * @param process the process to use for the first stream
     * @param simulationParameters parameters for the simulation of the traces
     */
    public Streamer(StreamConfiguration configuration, String processName, Process process, SimulationConfiguration simulationParameters) {
        this.processName = processName;
        this.configuration = configuration;
        this.process = process;
        this.simulationParameters = simulationParameters;
        this.buffer = new StreamBuffer(configuration);

        initialBufferPopulation();
    }

    /**
     * This method starts the actual streaming of the provided process
     */
    public synchronized void startStream() {
        if (!enabled) {
            enabled = true;

            producer = new XesMqttProducer(configuration.brokerHost, configuration.topicBase);
            // start the broadcast service
            producer.connect();

            // start the stream info thread
            infoTimer = new Timer();
            infoTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    Logger.instance().debug("Stream buffer size: " + buffer.eventsInQueue() + ", traces generated: " + generatedInstances + ", events sent: " + streamedEvents);
                }
            }, 1000, 2500);

            // populate the buffer
            populateBuffer();

            Logger.instance().info("Streaming started");

            // start the thread
            start();
        }
    }

    /**
     * This method ends the currently ongoing streaming
     */
    public synchronized void endStream() {
        if (enabled) {
            enabled = false;

            // stop the broadcast service
            producer.disconnect();

            // end the stream info thread
            infoTimer.cancel();

            Logger.instance().info("Streaming stopped");
        }
    }

    /**
     * This method clears up the buffer
     */
    public synchronized void clearBuffer() {
        buffer.clearQueues();
    }

    /**
     * This method performs the initial population of the buffer
     */
    public synchronized void initialBufferPopulation() {
        // initial population of the buffer
        for(int i = 0; i < configuration.maximumParallelInstances * 3; i++) {
            populateBuffer();
        }
    }

    /**
     * This method is responsible of the generation of new traces to populate
     * the {@link StreamBuffer}
     */
    protected synchronized void populateBuffer() {
        // define the trace generator
        TraceGenerator th = new TraceGenerator(process,
                String.format(simulationParameters.getCaseIdPattern(), generatedInstances++),
                simulationParameters);
        try {
            // start up the trace generator
            th.start();
            th.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        // enqueue the new trace
        buffer.enqueueTrace(th.getGeneratedTrace());
    }

    /**
     * This method returns the stream buffer
     *
     * @return the stream buffer
     */
    public synchronized StreamBuffer getBuffer() {
        return buffer;
    }

    /**
     * This method can be used to update the current process. This method can be
     * called while the streaming is ongoing.
     *
     * @param process the new process
     */
    public synchronized void updateProcess(Process process) {
        if (process != null) {
            this.process = process;
            this.buffer.clearQueues();
            populateBuffer();
        }
    }

    /**
     * This method is responsible for the actual streaming of an event fetched
     * from the {@link StreamBuffer}
     */
    protected synchronized void streamEvent() {
        // extract the new event to stream
        StreamEvent toStream = buffer.getEventToStream();

        // define the amount of time to wait
        long timeNewEvent = toStream.getDate().getTime();
        long timeToWait = 0;
        if (timeLastEvent > 0) {
            timeToWait = (long) ((timeNewEvent - timeLastEvent) * configuration.timeMultiplier);
        }
        if (timeToWait < 0) {
            // this situation can happen when the process is changed
            timeToWait = 0;
        }
        timeLastEvent = timeNewEvent;

        // update the event time to use the current time
        toStream.setDate(new Date());
        try {
            producer.send(toStream.getXesMqttEvent(processName));
            streamedEvents++;
        } catch (XesMqttClientNotConnectedException e) {
            e.printStackTrace();
        }

        // if necessary, update the buffer
        if (buffer.needsTraces()) {
            populateBuffer();
        }

        // now we have to sleep for the provided amount of time
        try {
            Thread.sleep(timeToWait);
        } catch (InterruptedException e1) {
            e1.printStackTrace();
        }
    }

    @Override
    public void run() {
        while(enabled) {
            // stream next event
            streamEvent();
        }
    }
}