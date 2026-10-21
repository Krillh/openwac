package org.mod.openwac.runtime;

import org.mod.openwac.core.diagnostic.FailureDetails;
import java.security.*;
import java.util.*;

/** Immutable evidence for a rejected kernel, independent of the computer's fatal state. */
public record KernelInstallFailure(long processId, int moduleBytes, String sha256, FailureDetails details) {
    public static KernelInstallFailure capture(long processId, byte[] module, FailureDetails details) {
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(module));
            return new KernelInstallFailure(processId, module.length, hash, details);
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public String key() { return sha256 + "\n" + details.stage() + "\n" + details.message(); }
    public String chatSummary() { return FailureDetails.limit(details.stage() + ": " + details.summary(), 384).replace('\n', ' '); }
    public String render(UUID machine, String dimension, String position) {
        return FailureDetails.limit("OpenWAC kernel installation failed (-16)\nMachine: " + machine
                + "\nDimension: " + dimension + "\nPosition: " + position + "\nInstaller PID: " + processId
                + "\nKernel bytes: " + moduleBytes + "\nKernel SHA-256: " + sha256
                + "\nStage: " + details.stage() + "\nCause: " + details.message() + "\n" + details.stackTrace(), 20 * 1024);
    }
}
