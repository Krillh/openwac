package org.mod.openwac.core.machine;

import org.mod.openwac.core.device.DeviceBus;
import org.mod.openwac.core.diagnostic.FailureDetails;
import org.mod.openwac.core.device.eeprom.BiosEeprom;
import org.mod.openwac.core.event.EventHub;
import org.mod.openwac.core.event.MachineEvent;
import org.mod.openwac.core.process.*;
import java.util.*;

/** Loader independent machine aggregate. Guest execution is injected via the runtime service. */
public final class VirtualMachine {
    private final UUID id;
    private final MachineConfig config;
    private final DeviceBus devices = new DeviceBus();
    private final EventHub events = new EventHub(1024);
    private final ProcessManager processes;
    private volatile MachineState state = MachineState.OFF;
    private volatile String diagnostic = "";
    private UUID bootMedia;
    private long logicalTick;
    private long bootProcessId;
    private String bootFirmware = "unknown";
    private volatile MachineCrash crashDetails;
    private java.util.function.Consumer<MachineCrash> crashListener = crash -> {};

    public VirtualMachine(UUID id, MachineConfig config) {
        this.id = Objects.requireNonNull(id); this.config = Objects.requireNonNull(config);
        this.processes = new ProcessManager(config.maxProcesses());
    }
    public synchronized UUID id() { return id; }
    public MachineConfig config() { return config; }
    public synchronized MachineState state() { return state; }
    public DeviceBus devices() { return devices; }
    public EventHub events() { return events; }
    public ProcessManager processes() { return processes; }
    public String diagnostic() { return diagnostic; }
    public MachineCrash crashDetails() { return crashDetails; }
    public void onCrash(java.util.function.Consumer<MachineCrash> listener) { crashListener = Objects.requireNonNull(listener); }
    public void kernelPanic(String message) {
        diagnostic = message;
        events.offer(new MachineEvent("kernel_panic", logicalTick, message.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        processes.killUnprivileged();
    }
    public synchronized void setBootMedia(UUID media) { bootMedia = media; }
    public synchronized void powerOn() {
        if (state == MachineState.OFF || state == MachineState.CRASHED) { diagnostic = ""; crashDetails = null; bootFirmware = "unknown"; bootProcessId = 0; state = MachineState.BOOTING; }
    }
    public synchronized void markRunning() { if (state != MachineState.BOOTING) throw new IllegalStateException("Not booting"); state = MachineState.RUNNING; }
    public synchronized void sleep() { if (state == MachineState.RUNNING) state = MachineState.SLEEPING; }
    public synchronized void powerOff() { processes.close(); state = MachineState.OFF; }
    public synchronized void crash() { crash(FailureDetails.message("Computer execution", "Computer crashed"), 0); }
    private synchronized void crash(FailureDetails details, long processId) {
        if (state == MachineState.CRASHED) return;
        diagnostic = details.summary();
        crashDetails = new MachineCrash(id, logicalTick, processId, bootFirmware, config, details);
        crashListener.accept(crashDetails);
        processes.close();
        state = MachineState.CRASHED;
    }
    public synchronized boolean enqueue(MachineEvent event) { return events.offer(event); }
    public synchronized MachineSnapshot snapshot() {
        return new MachineSnapshot(1, id, config, state, bootMedia, devices.devices().stream().map(d -> d.snapshot()).toList(), logicalTick);
    }
    public synchronized void advanceTick() { logicalTick++; }
    /** Called by the machine's serial execution worker, never by the Minecraft tick thread. */
    public void runTick(ProcessFactory factory) {
        try {
            if (state == MachineState.BOOTING) {
                processes.close();
                BiosEeprom bios = devices.devices().stream()
                        .filter(d -> d instanceof BiosEeprom && (bootMedia == null || d.id().equals(bootMedia)))
                        .map(d -> (BiosEeprom) d).findFirst().orElseThrow(() -> new IllegalStateException("No BIOS EEPROM installed"));
                bootFirmware = bios.snapshot().metadata().getOrDefault("firmware", "unknown");
                bootProcessId = processes.spawnBoot(bios.bootloader(), factory);
                if (bootProcessId == 0) throw new IllegalStateException("Cannot start BIOS process");
                markRunning();
            }
            if (state == MachineState.RUNNING || state == MachineState.SLEEPING) {
                ProcessManager.TickResult result = processes.runTick(config.fuelPerTick(), config.quantum());
                for (var failure : result.failures()) {
                    diagnostic = "Process " + failure.processId() + ": " + failure.diagnostic();
                    if (failure.processId() == bootProcessId) {
                        crash(failure.details() != null ? failure.details() : FailureDetails.message("BIOS process", diagnostic), failure.processId());
                        return;
                    }
                }
                state = processes.size() == 0 && !processes.hasMaintenance() ? MachineState.OFF : result.runnable() ? MachineState.RUNNING : MachineState.SLEEPING;
                advanceTick();
            }
        } catch (Exception failure) {
            crash(FailureDetails.capture(state == MachineState.BOOTING ? "Computer boot" : "Computer execution", failure), bootProcessId);
        }
    }
}
