# Implementation gaps

This is a short, evidence-based list of work that is proposed, incomplete, or
bounded by the current implementation. Archived documents are design history;
they do not establish that every item is a promised feature. Resolved claims in
older plans (such as memory accounting) are omitted.

Scope: mod hardware, host runtime support, and integration of externally built
guest artifacts. OpenWACOS's bootloader, kernel, OS, and shell live in a separate
repository; their implementation and syscall API are outside this to-do list.

## Confirmed product direction

These plans reflect maintainer guidance. They describe future behavior, not
features already available in this Java implementation.

| Area | Direction |
| --- | --- |
| Diagnostic EEPROMs | Remove the test BIOS EEPROM items before the 1.0 release. They are temporary development tools. |
| Screens | TTD is one screen option; many more screen options are planned. Their interfaces and capabilities remain to be specified. |
| Built-in OpenWACOS media | Supply OpenWACOS (kernel, OS, and shell) on a built-in in-game floppy disk once floppy disks are implemented. |
| Default EEPROM | Make the default BIOS EEPROM empty, with a crafting recipe that turns it into the default OpenWAC bootloader BIOS EEPROM. The current bootstrap-loaded default is temporary. |
| Custom bootloaders | Add an in-game flashing device for writing custom bootloaders to EEPROMs. |
| Hardware tiers | Add more tiers of CPU, RAM, and disk hardware. |
| Floppy hardware | Add a floppy disk device with multiple tiers, supporting the built-in media above. |
| Gameplay balance | Tune hardware values and progression based on gameplay. Current capacities and execution budgets are provisional. |
| Redstone | Add a redstone interface peripheral. Its device contract remains to be designed. |
| OpenWACOS RAM compatibility | The external guest currently needs 2 MiB to boot; its target is 1 MiB, possibly less later. Support suitable RAM configurations here; guest memory optimization belongs in the OpenWACOS repository. |

## Runtime proposals

These are proposals, separate from the confirmed hardware to-do. Their host
contracts and implementation approach are undecided.

| Proposal | Host work to explore |
| --- | --- |
| Chicory JIT compilation | JIT-compile Wasm modules instead of interpreting them, while preserving metering, resumable execution, isolation, and resource limits. |
| Interrupts for I/O | Provide interrupt support so guests can wait for I/O efficiently. Define delivery and wake behavior at the host boundary. |
| Guest-controlled process scheduling | Provide hardware/runtime support that lets a guest OS schedule processes. The guest scheduling policy and OS implementation belong in the separate repository; current host scheduling remains as documented in [architecture](architecture.md). |

## Implementation and verification work

| Area | Current evidence | Action / scope |
| --- | --- | --- |
| Serial monitor (`0x0300`) | The archived backend contract reserves the ID; its archived implementation index says it is not implemented ([contract](archive/true_device_interfaces.md), [index](archive/device_interfaces.md)). The current interface index covers TTD at `0x0400` and has no serial device ([interfaces](device-interfaces.md)). | Implement a serial device only if serial hardware remains in scope; otherwise retain it as a documented reservation. |
| Dynamic libraries and linked spawn | The archived proposal describes host registries/handles, lifecycle imports, linked spawn, linking metadata, and memory profiles ([plan](archive/dynamic_libraries_plan.md)); the current [HAL](hal.md) has no library lifecycle imports. | Keep proposed host linking support in scope here. Guest library lookup, loader policy, and OS implementation belong in the separate repository. |
| Built-in guest integration | OpenWACOS artifacts are produced in the separate repository. The host syscall bridge and [bundled-data packaging](../bundled-data/README.md) are implemented; floppy hardware, image installation, and the OpenWACOS release image remain to be added ([kernel runtime](kernel-runtime.md#scope-of-the-guest-os-abi)). | Place release floppy images in `bundled-data/floppies/`, integrate them with floppy hardware, and document their version and hardware requirements. Maintain guest syscall documentation upstream. |
| Event delivery | `ComputerRuntime` creates a host `EventHub(64)`, `VirtualMachine` owns an `EventHub(1024)` and offers kernel panic events to it, and `ChicoryComputer` creates per-process mailboxes ([runtime](../src/main/java/org/mod/openwac/service/ComputerRuntime.java), [VM](../src/main/java/org/mod/openwac/core/machine/VirtualMachine.java), [computer](../src/main/java/org/mod/openwac/runtime/chicory/ChicoryComputer.java)). No general VM-event-to-process-mailbox routing exists. | Add explicit event routing or mailbox producers for intended guest events, with overflow and ownership semantics documented. |
| Per-device authorization | `Capability` defines access labels but no device path consults it ([enum](../src/main/java/org/mod/openwac/core/device/Capability.java), [device call adapter](../src/main/java/org/mod/openwac/runtime/chicory/ChicoryHostImports.java)). HAL import linking distinguishes privileged processes, while device calls resolve IDs through the bus ([engine](../src/main/java/org/mod/openwac/runtime/chicory/ChicoryEngine.java), [bus](../src/main/java/org/mod/openwac/core/device/DeviceBus.java)). | If least-privilege device access is required, define and enforce per-device grants. This is a proposed policy layer, not an absent runtime privilege boundary. |
| Machine service and restoration | `MachineService` exists as an index, but playable cases construct `ComputerRuntime` directly; `VirtualMachine.snapshot()` produces a DTO that runtime restoration does not consume ([architecture](architecture.md#package-ownership), [service](../src/main/java/org/mod/openwac/service/MachineService.java), [case](../src/main/java/org/mod/openwac/integration/neoforge/blockentity/ComputerCaseBlockEntity.java)). No general live-machine checkpoint/restore or unknown-device placeholder reconstruction is implemented. | Decide whether generalized runtime ownership and snapshot reconstruction are product requirements; currently this is design scope, not required for the placed-computer flow. |
| Additional screens | Current TTD is a CP437 text-cell device ([peripherals](peripherals.md#tiny-text-display-ttd)); the wider screen lineup is planned. Archived architecture also discusses framebuffer graphics ([archive](archive/architecture.md)). | Define each new screen's capabilities and interface independently of the TTD contract. |
| Energy and server configuration | Archived peripheral contracts explicitly say energy accounting is not implemented ([archive](archive/true_device_interfaces.md)); `MachineConfig` and server limits are constructed in code, with no server quota configuration UI evidenced in the case screen ([config](../src/main/java/org/mod/openwac/core/machine/MachineConfig.java), [screen](../src/main/java/org/mod/openwac/integration/neoforge/client/ComputerCaseScreen.java)). Hardware assembly UI is implemented. | Specify energy behavior and expose configurable server quotas only if operators need them; current safety limits are code-defined. |
| Imported-file retention | `ImportedFileStore` stores immutable content-addressed blobs and enforces a 256 MiB world limit; uploads can supersede a device's selected hash, but no garbage collection of old unreferenced blobs is present ([store](../src/main/java/org/mod/openwac/service/ImportedFileStore.java), [upload path](../src/main/java/org/mod/openwac/integration/neoforge/network/ImporterUploads.java)). The archived architecture also records that superseded blobs are not automatically collected ([archive](archive/architecture.md)). | Track selected hashes and add safe cleanup of unreferenced blobs, or document that operators must manage world storage. |
| EEPROM release workflow | `BiosEepromItem.program(...)` is a host-side API; the current default EEPROM loads bootstrap firmware and no in-game flashing workflow is exposed ([item](../src/main/java/org/mod/openwac/integration/neoforge/item/BiosEepromItem.java), [guide](user-guide.md)). | Implement the empty default EEPROM, bootloader crafting recipe, and flashing device described above; remove diagnostic EEPROM items before 1.0. |
| Firmware build and artifact tracking | The local C build script accepts an unpinned Clang executable and records no build manifest; it does not rebuild the development bootstrap WAT ([WAT](firmware/bootstrap-v1.wat), [build script](../tools/build-firmware.ps1)). OpenWACOS bootloader source and builds belong upstream. | Record local firmware toolchains and externally supplied artifact versions/hashes. Document how to update bundled artifacts; keep OpenWACOS build instructions in its own repository. |
| Component identity on copies | `ComponentItem.deviceId(ItemStack)` creates an ID only when absent; copied item NBT therefore retains the same UUID ([item](../src/main/java/org/mod/openwac/integration/neoforge/item/ComponentItem.java)). This can make copied components address the same device identity. | Verify the intended copy/clone semantics and regenerate IDs for independent copied hardware if duplicates are not meant to share identity. |

The process watchdog is cooperative: timeout interrupts/closes execution, and
Java interruption is not a hard-kill guarantee ([watchdog implementation](../src/main/java/org/mod/openwac/runtime/chicory/ChicoryProcess.java)). This is an explicit runtime limitation; hard process isolation is an optional architecture change rather than a missing guest feature.
