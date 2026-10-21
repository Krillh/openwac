package org.mod.openwac.core.diagnostic;

import java.io.*;
import java.util.Objects;

/** Bounded runtime diagnostics; no exception or guest instance is retained or persisted. */
public record FailureDetails(String stage, String message, String stackTrace) {
    public static final int TRACE_LIMIT = 16 * 1024;
    public FailureDetails {
        stage = limit(Objects.requireNonNull(stage), 128);
        message = limit(Objects.requireNonNull(message), 512);
        stackTrace = limit(Objects.requireNonNull(stackTrace), TRACE_LIMIT);
    }
    public static FailureDetails capture(String stage, Throwable failure) {
        var trace = new BoundedWriter();
        failure.printStackTrace(new PrintWriter(trace));
        String message = failure.getClass().getSimpleName() + ": " + Objects.toString(failure.getMessage(), "No exception message");
        return new FailureDetails(stage, message, trace.toString());
    }
    public static FailureDetails message(String stage, String message) { return new FailureDetails(stage, message, "No stack trace available."); }
    public String summary() { return message.replaceAll("[\\p{Cntrl}\\x{a7}]", " "); }
    public static String limit(String text, int size) {
        String marker = "\n[truncated]";
        return text.length() <= size ? text : text.substring(0, size - marker.length()) + marker;
    }
    private static final class BoundedWriter extends Writer {
        private final StringBuilder text = new StringBuilder();
        private boolean truncated;
        @Override public void write(char[] chars, int offset, int length) {
            int count = Math.min(length, TRACE_LIMIT - text.length());
            text.append(chars, offset, count);
            truncated |= count < length;
        }
        @Override public void flush() {}
        @Override public void close() {}
        @Override public String toString() { return truncated ? limit(text + "\n[truncated]", TRACE_LIMIT) : text.toString(); }
    }
}
