package org.mod.openwac.runtime;

public record EngineLimits(int maxModuleBytes, long maxMemoryBytes, long fuelPerTurn) {
    public EngineLimits {
        if (maxModuleBytes < 8 || maxModuleBytes > 1024 * 1024 || maxMemoryBytes < 65536
                || maxMemoryBytes > 256L * 1024 * 1024 || fuelPerTurn < 1 || fuelPerTurn > 1_000_000)
            throw new IllegalArgumentException("Invalid WASM engine limits");
    }
}
