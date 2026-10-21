package org.mod.openwac.core.machine;

public record MachineConfig(long fuelPerTick, long quantum, int maxProcesses, long maxMemoryBytes) {
    public MachineConfig {
        if (fuelPerTick < 1 || fuelPerTick > 1_000_000 || quantum < 1 || quantum > fuelPerTick
                || maxProcesses < 1 || maxProcesses > 256 || maxMemoryBytes < 65536 || maxMemoryBytes > 256L * 1024 * 1024
                || maxMemoryBytes % 65536 != 0)
            throw new IllegalArgumentException("Invalid machine limits");
    }
    public static MachineConfig defaults() { return new MachineConfig(4096, 256, 8, 64L * 1024 * 1024); }
}
