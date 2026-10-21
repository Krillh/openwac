package org.mod.openwac.service;

import java.util.function.Consumer;

/** One log entry per power attempt, owned by the block entity's server thread. */
public final class CrashReportPublisher {
    private final Consumer<String> output;
    private boolean reported;
    public CrashReportPublisher(Consumer<String> output) { this.output = java.util.Objects.requireNonNull(output); }
    public void reset() { reported = false; }
    public boolean publish(ComputerCrashReport report, String dimension, String position) {
        if (reported || report == null) return false;
        reported = true;
        output.accept(report.render(dimension, position));
        return true;
    }
}
