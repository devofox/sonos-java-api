package org.tensin.sonos.commands;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tensin.sonos.SonosException;
import org.tensin.sonos.control.ZonePlayer;

/**
 * The Class ZoneCommandDispatcher. Send the right command to the right zone executor, and keeps a map linking zone name to the corresponding executor.
 * ZoneCommandExecutor are threaded and have a queue containing every command to process. The thread only wakes up when a new command is available.
 * ZoneCommandExecutor can only works if the Sonos box has been found by discovery on the network : this event has to be fired by the registerZoneAsAvailable method.
 */
public class ZoneCommandDispatcher {

    /** The Constant LOGGER. */
    private static final Logger LOGGER = LoggerFactory.getLogger(ZoneCommandDispatcher.class);

    /** The INSTANCE. */
    private static ZoneCommandDispatcher INSTANCE;

    /**
     * Gets the single instance of ZoneCommandDispatcher.
     * 
     * @return single instance of ZoneCommandDispatcher
     */
    public static ZoneCommandDispatcher getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new ZoneCommandDispatcher();
        }
        return INSTANCE;
    }

    /** The no command executed. */
    private boolean noCommandExecuted = true;

    /** The Constant executors. */
    private final Map<String, ZoneCommandExecutor> executors = new HashMap<String, ZoneCommandExecutor>();

    /**
     * Dispatch command.
     * 
     * @param command
     *            the command
     * @param zoneName
     *            the zone name
     */
    public void dispatchCommand(final IZoneCommand command, final String zoneName) {
        LOGGER.debug("Dispatching [" + command.getName() + "]");
        final ZoneCommandExecutor executor = registerZoneExecutor(zoneName);
        executor.addCommand(command);
        noCommandExecuted = false;
    }

    /**
     * Gets the executors.
     * 
     * @return the executors
     */
    public Map<String, ZoneCommandExecutor> getExecutors() {
        return executors;
    }

    /**
     * Gets the zone command executor.
     * 
     * @param zoneName
     *            the zone name
     * @return the zone command executor
     */
    public ZoneCommandExecutor getZoneCommandExecutor(final String zoneName) {
        if (executors.containsKey(zoneName.toUpperCase())) {
            return executors.get(zoneName.toUpperCase());
        }
        return null;
    }

    /**
     * Gets the zones names.
     * 
     * @return the zones names
     */
    public Collection<String> getZonesNames() {
        return executors.keySet();
    }

    /**
     * Log summary.
     */
    public void logSummary() {
        StringBuilder sb = new StringBuilder();
        StringBuilder summary = new StringBuilder();

        int problems = 0, reports = 0;
        for (final Entry<String, ZoneCommandExecutor> entry : executors.entrySet()) {
            final ZoneCommandExecutor executor = entry.getValue();
            if (executor.getSonosZone() == null) {
                sb.append("- Zone [" + executor.getZoneName() + "] hasn't been found on the network by discovery !\n");
                problems++;
            } else {
                summary.append("- Zone [" + executor.getZoneName() + "], executed commands count = " + executor.getExecutedCommandsCount() + "\n");
                reports++;
            }
            if (!executor.hasNoCommandLeft()) {
                sb.append("- Zone [" + executor.getZoneName() + "] has " + executor.getCommandLeftCount() + " awaiting command(s) that won't be processed.\n");
                problems++;
            }
            if (executor.hasRunningCommand()) {
                sb.append("- Zone [" + executor.getZoneName() + "] has 1 running command that will be killed.\n");
                problems++;
            }
        }
        if (problems > 0) {
            LOGGER.error("Problems found before terminating program : \n" + sb.toString());
        }
        if (reports > 0) {
            LOGGER.info("Summary :\n" + summary.toString());
        }

    }

    /**
     * Register zone as available.
     * 
     * @param sonos
     *            the sonos
     * @param zoneName
     *            the zone name
     */
    public void registerZoneAsAvailable(final ZonePlayer sonos, final String zoneName) {
        ZoneCommandExecutor executor = registerZoneExecutor(zoneName);
        executor.registerZoneAsAvailable(sonos);
    }

    /**
     * Register zone executor.
     * 
     * @param zoneName
     *            the zone name
     * @return the zone command executor
     */
    public ZoneCommandExecutor registerZoneExecutor(final String zoneName) {
        final String zoneNameUppercase = zoneName.toUpperCase();
        ZoneCommandExecutor executor;
        synchronized (executors) {
            if (!executors.containsKey(zoneNameUppercase)) {
                executor = new ZoneCommandExecutor(zoneNameUppercase);
                executor.start();
                executors.put(zoneNameUppercase, executor);
            } else {
                executor = executors.get(zoneNameUppercase);
            }
        }
        return executor;
    }

    /**
     * Reset instance.
     * 
     * @throws SonosException
     *             the sonos exception
     */
    public void resetInstance() throws SonosException {
        stopExecutors();
        synchronized (executors) {
            executors.clear();
        }
    }

    /**
     * Stop.
     * 
     * @throws SonosException
     *             the sonos exception
     */
    public void stopExecutors() throws SonosException {
        synchronized (executors) {
            for (Entry<String, ZoneCommandExecutor> entry : executors.entrySet()) {
                ZoneCommandExecutor executor = entry.getValue();
                executor.halt();
                executor.addCommand(new CommandPoisonPill());
            }
        }
    }

    /**
     * Wait end execution.
     * 
     * @param delay
     *            the delay
     * @param checkEmptyQueues
     *            the check empty queues
     * @throws SonosException
     *             the sonos exception
     */
    public void waitEndExecution(final int delay, final boolean checkEmptyQueues) throws SonosException {
        final long start = System.currentTimeMillis();
        long current;
        boolean active = true;
        boolean firstPass = true;
        while (active) {
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
            }
            current = System.currentTimeMillis();
            if (current > (start + delay)) {
                active = false;
            } else {
                if (checkEmptyQueues) {
                    // We can end the awaiting if all Zone Executor command queues are empty
                    synchronized (executors) {
                        boolean allCommandsProcessed = true;
                        boolean allCommandsEnded = true;
                        for (final Entry<String, ZoneCommandExecutor> entry : executors.entrySet()) {
                            final ZoneCommandExecutor executor = entry.getValue();
                            if (!executor.hasNoCommandLeft()) {
                                allCommandsProcessed = false;
                                break;
                            }
                            if (executor.hasRunningCommand()) {
                                allCommandsEnded = false;
                                break;
                            }
                        }
                        if (allCommandsProcessed && allCommandsEnded && !noCommandExecuted) { // for the start case : no commands have been issued yet on the zone dispatcher
                            active = false;
                        }
                    }
                } else {
                    if (firstPass) {
                        LOGGER.info("As no zone has been specified to work on, we wait a bit to be sure that every zone has been discovered on the network ...");
                        firstPass = false;
                    }
                }
            }
        }
    }

}