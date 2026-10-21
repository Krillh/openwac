package org.mod.openwac.service;

import org.mod.openwac.core.diagnostic.FailureDetails;
import org.mod.openwac.core.machine.MachineCrash;

/** Full report formatting is independent of Minecraft and does not write files. */
public record ComputerCrashReport(MachineCrash crash, long usedPages, long installedPages, FailureDetails kernelFailure) {
    public String render(String dimension, String position) {
        var text = new StringBuilder("OpenWAC computer crash report\n");
        text.append("Machine: ").append(crash.machineId()).append("\nDimension: ").append(dimension)
                .append("\nPosition: ").append(position).append("\nExecution tick: ").append(crash.tick())
                .append("\nProcess: ").append(crash.processId() > 0 ? crash.processId() : "not started")
                .append("\nBIOS firmware: ").append(crash.firmware()).append("\nRAM pages (allocated/installed): ")
                .append(usedPages).append('/').append(installedPages);
        var config = crash.config();
        if (config == null) text.append("\nCPU limits: unavailable during startup");
        else text.append("\nFuel per tick: ").append(config.fuelPerTick()).append("\nQuantum: ").append(config.quantum())
                .append("\nProcess limit: ").append(config.maxProcesses());
        append(text, "Failure", crash.failure());
        if (kernelFailure != null) append(text, "Earlier kernel installation failure", kernelFailure);
        return FailureDetails.limit(text.toString(), 40 * 1024);
    }
    private static void append(StringBuilder text, String title, FailureDetails failure) {
        text.append('\n').append(title).append(" stage: ").append(failure.stage()).append("\nCause: ")
                .append(failure.message()).append('\n').append(failure.stackTrace());
    }
}
