package org.mod.openwac.service;

import org.mod.openwac.core.machine.MachineConfig;
import org.mod.openwac.core.machine.VirtualMachine;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Server-owned index of currently loaded computers. Persistence stays with the platform adapter. */
public final class MachineService {
    private static final MachineService INSTANCE = new MachineService();
    private final Map<UUID, VirtualMachine> machines = new ConcurrentHashMap<>();
    private MachineService() {}
    public static MachineService instance() { return INSTANCE; }
    public VirtualMachine create(UUID id) {
        VirtualMachine machine = new VirtualMachine(id, MachineConfig.defaults());
        VirtualMachine previous = machines.putIfAbsent(id, machine);
        if (previous != null) throw new IllegalStateException("Machine is already loaded: " + id);
        return machine;
    }
    public Optional<VirtualMachine> find(UUID id) { return Optional.ofNullable(machines.get(id)); }
    public void unload(UUID id) { machines.remove(id); }
    public List<VirtualMachine> loadedMachines() { return machines.values().stream().sorted(Comparator.comparing(VirtualMachine::id)).toList(); }
}
