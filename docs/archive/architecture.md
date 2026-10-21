# OpenWAC Java Architecture

OpenWAC hosts virtual computers whose programs are compiled WebAssembly modules. Java owns the machine, hardware, persistence, scheduling, and controlled host calls. The guest OS, kernel, drivers, filesystem, toolchain, and applications are outside this architecture and run in WASM.

This document complements [HAL reference](hal.md), which specifies the current import names, signatures, buffer rules, and return codes. When implementing the first runtime, treat that reference as the wire contract. In particular, `hal_device_info` returns an interface ID and UUID, and `hal_device_call` addresses a device by UUID. The process-control imports there are runtime services; they do not define operating-system concepts or device-specific APIs.

## 1. Design rules

* Keep Minecraft types out of the core. The core should be testable and embeddable without loading NeoForge.
* The server owns every machine and every execution decision. A client is a presentation and input endpoint.
* Hardware identity is a permanent UUID. Slot numbers and enumeration indices are temporary.
* Expose a small, versioned host ABI. Hardware-specific behavior travels in bounded opaque request and response byte arrays.
* Persist source artifacts and explicit machine snapshots, never a live Chicory object graph.
* Treat every WASM module and every device payload as untrusted input. Bound bytes, work, queues, memory, and lifetime.
* Keep hardware-oriented interfaces stable. A guest OS may build any filesystem, driver model, or process model on top.

## 2. Package layout

Suggested Java namespace: `org.mod.openwac`.

```text
org.mod.openwac
  integration.neoforge
    OpenWacMod, registration/
    block/                 ComputerBlock
    blockentity/           ComputerBlockEntity, lifecycle bridge
    item/                  ComputerItem, media and peripheral items
    menu/                  ComputerMenu
    screen/                ComputerScreen, display widgets
    network/               payload codecs, handlers, sync batching
    persistence/           SavedData bridge, data codecs, migration
    capability/            optional NeoForge capability adapters
  core
    machine/               VirtualMachine, MachineConfig, MachineState
    device/                Device, DeviceRegistry, DeviceBus, addresses
    device/api/             interfaces, operation IDs, result codes
    device/storage/         raw block media and storage snapshots
    device/display/          terminal surface, framebuffer abstractions
    event/                  event values and bounded queues
    process/                WasmProcess, ProcessManager, permissions
    scheduler/              tick budget, run queue, execution policy
    persistence/            snapshot DTOs, repository contracts
    security/               limits, validation, quotas
    time/                   monotonic clock abstraction
  runtime
    WasmEngine, WasmModule, WasmInstance, WasmProcessHandle
    chicory/                ChicoryEngine and Chicory-specific adapters
    hal/                    import linker and guest-memory guards
  service
    MachineService, DeviceAttachmentService, MachineRepository
```

`core` may depend on Java platform APIs only. `runtime` depends on `core` and Chicory. `integration.neoforge` depends on both and translates Minecraft events/data to core calls. Do not let a core type accept `Level`, `BlockPos`, `ItemStack`, `CompoundTag`, or a NeoForge event. Pass plain IDs, immutable values, and service interfaces instead.

## 3. Main object model

```text
ComputerBlockEntity ──binds──> VirtualMachine ──owns──> ProcessManager
       │                              │                  │
       │                              ├──owns──> DeviceBus │
       │                              ├──owns──> EventHub │
       │                              └──uses──> WasmEngine
       │                                                 │
       └──Minecraft lifecycle bridge                     └──WasmProcess

DeviceBus ──contains──> Device (UUID, interfaces, capabilities)
WasmProcess ──imports──> HostAbi ──calls──> VirtualMachine services
```

### VirtualMachine

One instance per placed computer. It owns configuration, state, device bus, event hub, process manager, boot artifact reference, CPU accounting, and snapshot production. It has no world reference. Its public methods are commands (`requestPowerOn`, `requestShutdown`, `attachDevice`, `enqueueInput`) and bounded simulation (`advance(TickBudget)`). State transitions happen in the core and are reported as events.

```java
enum MachineState { OFF, BOOTING, RUNNING, SLEEPING, CRASHED }
interface VirtualMachine {
    MachineId id();
    MachineState state();
    void requestPowerOn();
    void requestPowerOff(ShutdownMode mode);
    void enqueue(MachineEvent event);
    MachineSnapshot snapshot();
    void advance(TickBudget budget);
}
```

`MachineId` is a stable UUID for the computer itself, independent of its current block position. The integration layer maps it to the currently loaded block entity. A move or dimension transfer updates that mapping, not identity.

### State and lifecycle

* `OFF`: no processes are executing. Devices and saved state remain available.
* `BOOTING`: boot artifact is resolved and validated, a privileged boot process is being instantiated, and startup limits apply.
* `RUNNING`: scheduler admits processes and events.
* `SLEEPING`: no runnable process; device/input events can wake the machine.
* `CRASHED`: execution stopped due to a fatal boot/runtime fault. Preserve diagnostics and permit explicit reset or power cycle.

Power-on transitions OFF/SLEEPING → BOOTING → RUNNING after successful instantiation and entry-point validation. Failure transitions to CRASHED. Graceful shutdown queues a guest-visible event and allows a bounded number of ticks; hard shutdown terminates all process handles and transitions to OFF. Chunk unload is not a power-off: stop advancement, snapshot, detach the world binding, and release runtime instances. On reload, restore a new runtime from artifacts and snapshot. Server stop follows the same snapshot path.

## 4. Device system and stable hardware contract

### Identity and interfaces

Every device has a UUID generated once at item/device creation and persisted with that device. Moving it between inventories, computers, dimensions, or worlds preserves the UUID. Cloning a device creates a new UUID; copying serialized data must not accidentally duplicate a physical device identity. A machine's attached-device set is keyed by UUID and rejects duplicate attachment. Enumeration order is ascending UUID only for determinism; indices are valid only for a single enumeration result.

An interface identifier describes a hardware contract, not a Java class. Use a namespaced stable ID plus major/minor version, for example `openwac:storage.block` v1 or `openwac:display.framebuffer` v1. Keep numeric IDs in the compact ABI registry, with a permanent mapping and no reuse. Every device exposes exactly one `InterfaceId`; its Java implementation dispatches operation numbers inside `device_call`. Hardware with different interfaces exposes separate UUID-addressed logical devices.

```java
record InterfaceId(int numericId, String namespacedName, int major, int minor) {}
record DeviceAddress(UUID uuid) {}
record DeviceDescriptor(DeviceAddress address, InterfaceId interfaceId,
                        Set<Capability> capabilities, long generation) {}

interface Device {
    UUID id();
    InterfaceId interfaceId();
    DeviceResult device_call(DeviceCall call);
    DeviceSnapshot snapshot();
}
record DeviceCall(int operation, byte[] arguments, int responseLimit) {}
sealed interface DeviceResult {
    record Success(byte[] response) implements DeviceResult {}
    record Failure(int code, byte[] detail) implements DeviceResult {}
}
```

`DeviceRegistry` maps stable interface IDs and implementation factories to implementations. `DeviceBus` is per-machine and maps UUID to attached device, enforces uniqueness, captures attachment generation, enumerates descriptors, and dispatches bounded calls. A device is not trusted merely because it is supplied by another mod: validate descriptor sizes and catch/contain exceptions at the bus boundary. Third-party devices register factories through a core extension point or NeoForge registration adapter; never permit them to register an existing numeric ID under a different contract.

### Generic call envelope and versioning

The current HAL uses a 32-bit operation number and opaque bytes. Define each interface's operation table in a companion interface document. Reserve operation IDs permanently; specify argument and response encoding, maximum sizes, required capability, side effects, and error meanings. Use a compact explicitly little-endian encoding (or a versioned CBOR-like encoding if extensibility warrants its cost); never serialize Java objects or rely on default Java serialization. Include a schema version in each interface's payload where an operation needs extensible fields. The interface major version changes for incompatible behavior; minor versions add optional operations/features.

At the host boundary, copy guest input into a host-owned bounded array before dispatch. Do not allow a device to retain guest-memory views. Validate output length against caller capacity before copying. Return stable generic HAL errors for malformed calls and interface-specific errors for valid calls. Cap arguments and responses and charge their byte length and device work against the caller's budget. Use capabilities (`READ`, `WRITE`, `CONFIGURE`, `NETWORK_SEND`, etc.) to gate effects; privilege is an additional process property, not a substitute for per-device capability checks.

### Raw block storage

Persistent media expose blocks, not files or paths. A v1 interface should report block size and count; read a bounded range; write a bounded range; and flush. Define block numbering from zero, exact transfer lengths, alignment, read-only behavior, durability of completed writes, and errors. Implement storage backing with bounded byte storage or a file-backed store selected by configuration. Store the device UUID, geometry, format version, and content reference/checksum. For Minecraft item media, persist the data in item NBT/data components while enforcing a hard capacity; for large media, use world-scoped external region files with atomic temp-write/rename and UUID-keyed metadata. Never let guest strings select host paths.

### Display and terminal hardware

Keep display hardware generic. A terminal-oriented surface can be one display interface with dimensions, cursor position/visibility, cell contents, foreground/background palette or packed color, and dirty rows/rectangles. Model a text cell as code point plus style; sanitize invalid Unicode and cap dimensions. `TerminalBuffer` is a core-side or device-side model with a monotonically increasing revision and dirty-region accumulator. A read/sync operation returns revision and only changed regions; acknowledgement clears a region only if no later write superseded it.

Future framebuffer interfaces expose pixel format, dimensions, stride, bounded region writes, and presentation revision. Multiple displays are distinct UUIDs. A GPU-like device can add command-buffer operations, but it must remain bounded and cannot execute arbitrary host code. NeoForge screen widgets render a snapshot; they do not own display state.

## 5. Events, processes, and scheduling

`MachineEvent` is a versioned immutable value with source, type, tick/time, and bounded payload. `EventHub` maintains bounded queues/mailboxes, applies drop/coalesce policies by event class, and supports wakeups. Keyboard repeat and pointer motion can be coalesced; key transitions, power events, and device attach/detach must not be silently lost. Each process has its own mailbox so one process cannot consume another's messages. Bound queue count and total bytes per machine and per process.

```java
interface ProcessManager {
    ProcessId queueSpawn(SpawnRequest request);
    void queueKill(ProcessId id);
    void deliver(MachineEvent event);
    void schedule(TickBudget budget);
    List<ProcessSnapshot> snapshots();
}
interface WasmProcess {
    ProcessId id();
    ProcessState state();
    ProcessPrivileges privileges();
    ProcessStepResult run(ExecutionSlice slice);
    void requestStop(StopReason reason);
    ProcessSnapshot snapshot();
}
```

`ProcessManager` owns process IDs, lifecycle, pending spawn/kill requests, mailbox assignment, per-process memory limits and execution quotas. Process IDs are machine-local positive 64-bit values and are not reused while the machine is alive. Multiple processes are a core scheduling feature, even if first release boots only one module. The boot module may have privileged host imports; ordinary modules receive only explicitly granted imports/capabilities. The current `hal.md` documents process-control calls and should remain the authority for their initial ABI behavior.

`ProcessManager` uses round-robin scheduling. Each machine gets a total fuel budget per server tick and each ready process receives a fresh quantum. After exhaustion or `hal_yield`, append that process to the end of the ready queue. A yielded process may run again in the same tick. Charge actual consumption, refund unused quantum, and grant a partial final quantum when necessary to exhaust the total budget. Preserve the queue cursor across tick boundaries. Device work and host copies also consume metered fuel. Tick budgets are simulation units and do not promise wall-clock deadlines.

## 6. Runtime and Chicory boundary

Only `runtime.chicory` imports Chicory classes. The core depends on these abstractions:

```java
interface WasmEngine {
    CompiledModule compile(byte[] wasm, EngineLimits limits);
    WasmProcessHandle instantiate(CompiledModule module, HostAbi host,
                                  ProcessConfig config);
}
interface CompiledModule extends AutoCloseable {
    ModuleMetadata metadata();
}
interface WasmProcessHandle extends AutoCloseable {
    ProcessStepResult invokeEntry(String export, ExecutionSlice slice);
    ProcessStepResult resume(ExecutionSlice slice);
    GuestMemory memory();
    void close();
}
interface GuestMemory {
    byte[] read(int address, int length, int maximum);
    void write(int address, byte[] bytes, int maximum);
}
```

The Chicory adapter compiles and validates modules, links only imports allowed for the process privilege tier, applies module and memory limits supported by the chosen runtime version, wraps guest memory access, and converts traps/fuel exhaustion into core results. Chicory-specific module/instance/memory types must not appear in persisted DTOs or NeoForge code. Keep engine creation injectable so tests can use a fake engine and a future runtime can replace Chicory.

The stable import namespace `hal` should stay small: device enumeration/info/call, event poll, monotonic time, yield, and bounded debug logging are the foundational hardware/runtime surface. Process management imports can remain in `hal.md` as lifecycle services, but avoid adding OS syscalls, file operations, or per-device imports there. Pin exact Wasm signatures and memory semantics in the HAL reference and version changes explicitly.

## 7. Execution safety and multiplayer isolation

WASM validation and execution are untrusted operations. Set limits before compiling/instantiating: module bytes, functions/tables/globals, linear memory, table elements, stack depth where available, process count, per-machine memory, and queued spawn bytes. Reject unsupported proposals/imports and all imports outside the allowlist. Do not expose filesystem, reflection, class loading, arbitrary Java callbacks, or host paths.

Never run guest code synchronously on the Minecraft server tick thread. Use a bounded worker executor with strict per-machine task admission and a per-machine serial executor/lock so no two turns mutate one VM concurrently. Main-thread callbacks only enqueue commands and consume immutable results. Chicory fuel limits are the primary deterministic instruction bound, but host functions must also have bounded work. Add a wall-clock watchdog around worker turns; on deadline, mark the machine faulted and quarantine/retire the worker. Java cannot safely stop an arbitrary stuck thread, so isolate execution in a restartable worker process if a hard kill guarantee becomes a requirement. Do not claim a thread timeout is hard cancellation.

Enforce global worker count and queue limits, per-player/per-machine creation quotas, per-tick fuel, max devices, host-call byte and operation budgets, network packet bounds, and bounded debug logs. A single machine fault must not take down the server. Device calls run on the machine worker or are explicitly marshalled to the server thread through bounded asynchronous commands; devices must declare thread affinity. Minecraft world mutation always occurs on the server thread. Timeouts, traps, invalid memory, budget exhaustion, and device exceptions produce structured diagnostics and a controlled CRASHED or process-failed state.

## 8. HAL call flow

For `hal_device_call`, the flow is:

1. Chicory linker invokes a small `HostAbi` function with primitive Wasm values.
2. `GuestMemory` validates signed pointers/lengths, overflow, exported memory presence, and configured bounds; it copies argument bytes.
3. `ProcessContext` checks privilege and operation budget; `DeviceBus` resolves UUID and interface/operation.
4. Device validates its payload and capability, performs bounded work, and returns a bounded result.
5. Host validates response capacity and copies bytes back; a stable code is returned.

All failures are ordinary ABI results unless the runtime itself is corrupted. Catch `RuntimeException` at the device boundary and report a generic device failure; do not leak Java stack traces or implementation details to guest memory. Keep host calls short and nonblocking. Operations needing world interaction should return queued/pending status and deliver completion through the event mailbox.

## 9. Persistence and restoration

Persist explicit versioned DTOs, not live objects:

```java
record MachineSnapshot(int schemaVersion, UUID machineId, MachineConfig config,
                       MachineState savedState, UUID bootMedia,
                       List<DeviceSnapshot> devices,
                       List<ProcessSnapshot> processes,
                       TerminalSnapshot terminal, long logicalTick,
                       List<Diagnostic> diagnostics) {}
```

Machine state snapshots never store Java stack frames or a live Chicory instance. A safe initial policy is to persist machine configuration, boot media reference/hash, device UUIDs and metadata, storage contents/references, user settings, terminal/display state, logical time, and diagnostics; on reload reboot the kernel. Later, process restoration can be supported only through a guest-defined checkpoint protocol: request a checkpoint event, let the guest serialize its own state to persistent media, stop processes, then restore by booting and asking the guest to recover. Do not imply transparent WASM continuation.

NeoForge persistence hooks encode snapshots through versioned codecs into the block entity and/or world `SavedData`. Block entity stores identity and compact configuration; world-level repository stores larger machine/device blobs keyed by UUID. Atomic external storage writes need checksums, size caps, and orphan cleanup. Device item data preserves UUID and bounded contents when moved. On load, migrate DTO versions, validate every size and UUID, reconstruct devices through factories, create a fresh runtime, restore state, and only then admit execution. Unknown optional device types remain detached placeholders with their data preserved.

Chunk unload snapshots and releases runtime instances. To avoid loading all chunks just to run computers, define policy explicitly: by default a computer advances only while its chunk is loaded; optional always-on machines require a separate server-side ticking policy and strict quotas. Server restart snapshots all active machines. Saving is scheduled and coalesced; never serialize on every guest instruction.

## 10. NeoForge integration and networking

`ComputerBlockEntity` is an adapter. On load it resolves `MachineId` through `MachineService`; on unload it requests suspension/snapshot; on server tick it submits bounded machine work to the scheduler. It stores only core snapshot DTOs via a codec. The block handles placement/removal and access checks. Items carry device identity and device data. Menus enforce distance, permissions, and server-side validation.

Server-authoritative payloads should be directionally explicit:

* Client → server: open/close terminal, key down/up, bounded clipboard paste, pointer/button events, power/reset request, display acknowledgement, optional resize request.
* Server → client: machine state and diagnostics summary, terminal delta, framebuffer delta, cursor/palette changes, device topology change, request rejection.

Handlers validate dimension, machine ID, distance, menu/session ownership, permissions, sequence number, rate limits, payload size, and current machine state. Never accept a client-supplied device UUID as proof of access. Input becomes a core event; it never calls guest code directly on the packet thread.

For terminal updates, send machine/display ID, base revision, new revision, changed row/rectangle bounds, and compact cell/color data. Coalesce updates per viewer and cap bytes per tick. A base-revision mismatch triggers a bounded resync. For framebuffer updates, encode dirty rectangles and pixel format; use compression only when it reduces size within a CPU budget. Send a full keyframe on initial open or recovery, then deltas. Track per-client acknowledged revision so one slow client does not hold back the machine's dirty state. Mouse motion can be sampled/coalesced; button edges and key edges remain ordered.

Clipboard is untrusted text: enforce strict byte/codepoint limits, sanitize control sequences as appropriate for terminal input, and insert it as input events rather than executing host commands. Treat client terminal text as presentation data only; clients do not submit authoritative display contents.

## 11. Data flow summary

```text
client input → NeoForge payload validation → MachineService queue → EventHub
                                                         ↓
server tick → Scheduler → ProcessManager → WasmEngine/Chicory
                                      imports → HostAbi → DeviceBus → Device
client display ← delta encoder ← Terminal/Framebuffer revision ← device/guest calls
save hook → MachineSnapshot → versioned codec → block entity/world repository
load hook → validate/migrate → restore core objects → fresh WasmEngine instances
```

## 12. Build and implementation sequence

1. Establish `core` values and contracts: IDs, states, events, device interfaces, snapshots, limits. Keep these free of Minecraft and Chicory.
2. Implement `DeviceBus`, a UUID-backed block storage device, and terminal buffer with revisioned dirty regions.
3. Implement `WasmEngine` and Chicory adapter for one boot process. Link only the documented HAL imports and enforce memory/buffer bounds.
4. Add deterministic process scheduling and bounded execution service, initially one process per machine if needed while retaining the `ProcessManager` boundary.
5. Add explicit snapshot/migration and runtime rebuild after chunk/server lifecycle events.
6. Add NeoForge block entity, items, menus, and server-authoritative input/output payloads.
7. Add multi-process support, additional devices, framebuffer interfaces, and optional always-on execution only with explicit global quotas.

Keep interface and snapshot compatibility tests in the project as implementation evolves. Any ABI change should update `hal.md`, its C header, and interface operation documents together. Avoid binding interface IDs, enum ordinals, or serialized field order to incidental Java declaration order.

## 13. Current implementation

The first playable hardware is `openwac:computer_case`, with nine component slots plus dedicated BIOS EEPROM, CPU, and four RAM slots, a `openwac:bios_eeprom` item, a `openwac:cpu_tier_1` item, a `openwac:ram_tier_1` item, and a `openwac:disk_drive_tier_1` item. All are available in the OpenWAC creative tab. Right-click the case to edit its components; with an empty hand, shift-right-click to toggle power. Slots are locked while powered. Exactly one BIOS EEPROM, one CPU, and at least one RAM module must be installed in their labeled slots. The nine component slots reject all three. CPU and RAM items stack to 16 in normal inventories; every computer slot holds one item. The custom case menu synchronizes power locking and routes shift-clicked hardware to its dedicated slots, distributing RAM across the four available slots. Duplicate component UUIDs are rejected by the bus.

The tier 1 CPU supplies 65,536 shared fuel per tick and a limit of 16 live or pending processes, including the BIOS. `CpuItem` supplies fuel and process limits, and `RamItem` supplies installed memory capacity, combined into a `MachineConfig` at startup. Neither exposes a guest device interface. The scheduling quantum is 256. Each tier 1 RAM module contributes 1 MiB (16 WASM pages), for 1–4 MiB total. The CPU slot is index 10 and RAM slots are indices 11–14, preserving component indices 0–8 and BIOS index 9. Existing computers require manual CPU and RAM installation; hardware is not added during loading. Missing CPUs or RAM produce startup diagnostics through the normal failure flow.

`ComputerRuntime` owns a shared `MemoryBudget` passed to every process through `WasmEngine.createProcess`. Processes reserve only allocated linear-memory pages, including initial memory and successful `memory.grow`, rather than equal shares or their declared maxima. A process can use all available RAM; other processes then compete for the remaining pages. A delegating Chicory `Memory` adapter enforces both the computer pool and the server-wide 256 MiB pool. Failed growth returns -1 without changing memory or reservations. Initial memory exhaustion traps the affected process; BIOS failure crashes the computer. Exit, traps, and initialization failure release pages before completion is published. Cancellation releases pages after the execution thread stops. Java metadata, code, and interpreter stacks are governed by separate limits.

The tier 1 disk implements interface ID `0x0100`, `openwac:disk`, with 512 blocks of 2048 bytes (1 MiB). `ComponentContext` supplies independent firmware and storage repositories when items become devices. Disks join the same UUID-addressed bus as the BIOS and support IS_ROOT, SET_ROOT, BLOCK_SIZE, BLOCK_COUNT, READ_BLOCK and WRITE_BLOCK. Their versioned images live in the world's `openwac/disks` directory, while the item retains its permanent UUID. Every successful write is forced to storage; runtime disposal and chunk unload do not discard a write cache. See [disk wire format](true_device_interfaces.md).

`ComponentItem` persists a UUID at the first server inventory tick, crafting callback, or installation, and retains it when moved. `BiosEepromItem` stores only that identity and a firmware reference. Default EEPROMs reference the bundled immutable `openwac:bootstrap-v1` image. Programming through `BiosEepromItem.program` installs custom bytes once in the world's `openwac/firmware/<sha256>.wasm` store, then writes the SHA-256 reference to the item. Identical images share a blob; rewriting one EEPROM changes its reference without changing another EEPROM or its own UUID. Firmware filenames are derived only from validated hashes. Custom firmware moved to another world must have its referenced blob exported/imported there; missing content causes a visible boot failure. Built-in firmware is available in every world.

The bundled diagnostic BIOS prints `OpenWAC BIOS ready.` through temporary `hal_chat_print`, then yields indefinitely. It is a minimal execution diagnostic, not an OS loader implementation. Its readable source is [bootstrap-v1.wat](../firmware/bootstrap-v1.wat); only the compiled `.wasm` resource is loaded. No WASI or host filesystem imports are provided.

A separate `openwac:disk_test_bios_eeprom` item references the bundled `openwac:disk-test-v1` firmware. This firmware is a freestanding WASM program with readable [C source](../firmware/disk-test-v1.c), compiled ahead of time and executed exclusively through Chicory. See the in-game procedure below.

`ComputerRuntime` admits at most one outstanding turn per computer to two shared workers with a bounded queue. Slow computers skip server ticks instead of building a backlog. `ProcessManager` maintains a persistent round-robin queue, supports deferred spawn and kill, and enforces the installed CPU's process limit. The Chicory adapter explicitly selects the interpreter. A process uses a parked Java virtual thread to retain its continuation at every fuel boundary or yield. Execution never runs on the Minecraft tick thread. A two-second turn watchdog cancels the process; cancellation checks occur at each instruction. This is in-process execution, so a watchdog is not a hard JVM isolation boundary.

The initial Wasm profile bounds module bytes (1 MiB), encoded function bodies (64 KiB total), locals (1024/function, 65536/module), functions/types/globals (4096 each), one scalar linear memory, one bounded funcref table (4096 entries), operand stack (65536 values), and call depth (128 frames). Numeric types are i32/i64/f32/f64; GC, threads, exception tags, and passive/reference element segments are rejected. A binary preflight checks compressed declaration counts before Chicory parsing. Allocated process linear memory is capped at 256 MiB across the server, with at most 128 live process threads and 16 active computer controllers. CPU and RAM items provide machine execution and memory limits; global safety limits remain constants without a server config UI.

The block entity saves the case UUID, inventory/component data, power intent and boot failure diagnostic. Chunk unload and server stop close runtime processes; reload cold-boots the stored EEPROM if power was on and it had not faulted. A fault requires a power cycle. Process stacks are not serialized. TTD text screen networking, the kernel syscall ABI (`sys`), `hal_install_kernel`, and sticky `hal_wake` are implemented; full guest checkpoints and graphical framebuffer networking remain subsequent work. `MachineService` is an initial standalone index; the playable case currently owns its `ComputerRuntime` controller directly.

Run `gradlew verifyRuntime` for dependency-free Java checks of round-robin accounting, continuation preservation, infinite-loop preemption, privilege linking, UUID routing, and firmware deduplication. `check` includes this task. Builds bundle Chicory runtime and parser with NeoForge's [Jar-in-Jar mechanism](https://docs.neoforged.net/toolchain/docs/dependencies/jarinjar/), pinned to the 1.7.5 API because the instruction listener is experimental.

## 14. In-game disk self-test BIOS

Client file import is documented in section 15 below; it uses the existing
backend's file-importer device contract.

1. Restart the game after building/installing the updated mod. Get **BIOS EEPROM (Disk Self-Test)** and **Disk Drive (Tier 1)** from the OpenWAC creative tab.
2. Power off a computer case, right-click it to open its component inventory, and install the test EEPROM, a tier 1 CPU, at least one tier 1 RAM module, and a spare disk. Remove the ordinary BIOS EEPROM: exactly one BIOS is allowed. Additional disks are tested individually by UUID.
3. With an empty hand, shift-right-click the case to power on. Stay within 16 blocks and watch game chat until `SELF-TEST COMPLETE` appears. Chat is limited to four messages per second, so the firmware yields and retries messages when necessary.
4. On a successful first run with a blank last block, expect `GEOMETRY PASS`, `I/O PASS`, `BOUNDS PASS`, `PERSISTENCE ARMED`, and `DISK SELF-TEST PASS`, followed by a summary of passed and failed disks.
5. To test world persistence, wait for completion, save and quit, then reopen the world. Power the case off and back on while standing nearby so you see the new results. This run should report `PERSISTENCE PASS`: the complete marker and disk UUID survived the world reload. Moving the disk into another case in the same world can be checked the same way.

The firmware uses only HAL enumeration, UUID-based calls, yield, and chat output. It verifies 512 x 2048-byte geometry, backs up block 510, writes a deterministic byte pattern, flushes, reads and compares the full block, then restores and verifies the original bytes. It also verifies that an out-of-range read and a short write are rejected. Detected write/readback failures still trigger a restoration attempt and are reported as failures.

Block 511 is the persistent test marker. If blank, the firmware writes and flushes a versioned marker containing the physical disk UUID. If the expected marker already exists, it verifies all 2048 bytes and reports persistence success. If the block contains other data, it reports `PERSISTENCE SKIP` and leaves that data intact; use a blank spare disk to exercise that check. A skipped persistence check does not prevent the other disk I/O tests from passing.

Use a spare disk and keep the case powered until completion. The marker intentionally remains in block 511, and interruption or host I/O failure during the temporary write can leave test bytes in block 510. Successful runs restore block 510 exactly. The test does not format a filesystem or load a kernel; it yields in an idle loop after its final summary.

The compiled `../../src/main/resources/data/openwac/firmware/disk-test-v1.wasm` is bundled, so running the mod does not require a compiler. To rebuild the firmware, run `tools/build-firmware.ps1 -Clang <path-to-clang>` with a Clang/LLD installation that supports `wasm32`. A direct PowerShell compiler invocation, which does not require enabling script execution, is:

```powershell
& clang --target=wasm32-unknown-unknown -Oz -std=c11 -nostdlib -ffreestanding -fno-builtin `
    '-Wl,--no-entry' '-Wl,--export=_start' '-Wl,--allow-undefined' '-Wl,--strip-all' `
    '-Wl,--initial-memory=65536' '-Wl,--max-memory=65536' '-Wl,-z,stack-size=16384' `
    docs/firmware/disk-test-v1.c -o src/main/resources/data/openwac/firmware/disk-test-v1.wasm
```

`gradlew check` executes the actual bundled binary through Chicory and HAL against file-backed disks. It covers first-run markers, cold reload, multiple disks, occupied marker blocks, missing disks, unsupported geometry, chat retry, injected readback corruption, and scratch restoration on both success and failure.

## 15. Importing compiled programs from a player's computer

The first external display is now implemented as **Tiny Text Display**,
interface `0x0400`. See [the hardware and rendering documentation](ttd.md).

**BIOS EEPROM (File Importer Test)** reads and prints imported file contents
through the real HAL. See [the diagnostic procedure](../firmware/importer-test-v1.md).

1. Obtain a **File Importer** from the OpenWAC creative tab and hold it.
2. Right-click in the air. Enter an absolute path on your own computer and press **Upload**. For example, `C:\Users\you\programs\hello.wasm` on Windows or `/home/you/programs/hello.wasm` on Linux. The basename must be ASCII; the parent directories need not be ASCII.
3. Wait for the uploaded confirmation, then install the importer in a powered-off case and power on. Keep holding the importer until uploading finishes. Closing the screen cancels an incomplete transfer.
4. The existing WASM bootloader/OS can discover interface `0x0200` and read FILE_SIZE, FILE_NAME_LENGTH, FILE_NAME and READ_BLOCK through UUID-addressed HAL calls. Responses are byte-buffer writes; successful calls return status `0`. Host code does not automatically install, execute, or interpret the uploaded file.
5. To import a new compilation, power off, remove the importer and repeat the upload. The current implementation uses explicit snapshots, not a live connection to the local file.

`OpenWacClient` registers physical-client-only packet handlers. `FileImporterScreen` remembers local paths per server/device UUID and performs bounded background file reads. Only its explicit Upload action starts a transfer. `ImporterPayloads` carries a server-issued session token, basename, length, offsets, and chunks; it has no absolute-path field. One acknowledged chunk is in flight at a time. Server packet handling validates the session and the held component, then hands filesystem work to a bounded worker in `ImporterUploads`. Sessions expire after two idle minutes and are cancelled when the component leaves the player's hands or the player disconnects.

`ImportedFileStore` publishes complete uploads as SHA-256-addressed immutable blobs and atomically replaces a small device-UUID selection record under the world directory `openwac/imports`. Incomplete uploads do not replace prior selections. `ComputerCaseBlockEntity` supplies this repository through `ComponentContext`; `FileImporterItem` creates the loader-independent `FileImporterDevice` from it. Metadata is resolved on a machine execution worker, and an active device keeps its selected blob across subsequent reuploads. Save and reload rebuilds a device from the persisted selection. Uploaded files remain available with the uploader offline.

Limits are currently fixed at 16 MiB per file, eight server upload sessions, one session per player, 16 KiB network chunks, and 256 MiB of persistent importer blobs per world. Content deduplication reuses identical blobs. Automatic collection of superseded blobs is not implemented, so new unique uploads fail when the world quota is reached. Temporary uploads are cleaned after cancellation and on the next upload service startup. No live Chicory state is involved in importer persistence. See [the device contract](true_device_interfaces.md#0x0200-host-file-importer).

## Dedicated BIOS slot and inventory migration

The **Peripheral Port** component (`openwac:peripheral_port`, interface `0x0500`)
enables the external bus. With one installed, adjacent and logically wired TTDs appear directly in
ordinary HAL enumeration and accept calls at their own UUIDs. No guest-side
port routing is required. Without a port, the case does not inspect or claim
external devices; internal disks, importers and BIOS remain available. One port
enables all six adjacent positions and up to 16 saved logical-wire targets within
16 blocks. Component changes require power off; wire connections may change
while powered, taking effect on the next tick. See [logical wires](peripheral-wire.md).

The port appears in the OpenWAC creative tab. The updated TTD BIOS diagnostics
mention installing it if no screen is found. Their TTD discovery/call ABI is
unchanged. See [peripheral port contract](true_device_interfaces.md#0x0500-peripheral-port).

The case inventory format is version 2. Existing component indices 0 through 8
are retained, and the dedicated BIOS slot is index 9. The GUI presents the BIOS
slot separately above the component row. Nine regular component positions are
still available, and all machine slots remain locked while powered.

When loading a version-1 case, the first EEPROM is moved into the new BIOS slot
without changing its item data or UUID. Other components retain their indices.
Additional old EEPROMs remain available in their original positions for removal;
boot is rejected until those invalid placements are resolved. No inventory items
are discarded during migration. Menu filters, container insertion rules and boot
validation enforce the new slot roles.

Case save version 3 retains the version-2 inventory layout and adds bounded
position/UUID wire records in `PeripheralLinks`. The `PeripheralEndpoint`
integration interface makes external block entities eligible for both adjacency
and logical links. Device protocols, ownership leases, and the HAL remain
independent of this temporary connection mechanism.

## Keyboard peripheral and capture

`openwac:keyboard` is an external `PeripheralEndpoint` using interface `0x0600`.
It shares the peripheral-port requirement, adjacency/wire discovery, permanent
UUID handling, and exclusive computer attachment model with TTD. The core
`KeyboardDevice` owns a synchronized 256-record FIFO and generation-based
leases. It supplies INFO, AVAILABLE and READ_EVENTS operations with status-only
returns. The guest consumes physical key, text and reset records through the
device interface; this does not depend on process-mailbox routing. An
installed guest kernel can expose these operations through the syscall bridge.

The server `KeyboardNetwork` authorizes one player per keyboard and one
keyboard per player. Tokens bind input to a loaded, nearby block and a specific
machine-attachment generation. Input validation, rate limiting, session limits,
heartbeats, and teardown keep packet work and queue memory bounded. Ending
capture produces a reset event; attachment loss clears transient input and
invalidates old leases. Only physical identity persists.

`KeyboardCaptureScreen` is a transparent, non-pausing input surface. It forwards
Escape to the guest and suppresses normal screen-close/navigation handling.
Capture ends through the Release keyboard button or a pointer-ray click on
the physical keyboard, with automatic cleanup on focus loss or loss of the
authorized session. Clicking a TTD opens an optional enlarged display inside
the same capture surface; View world returns without releasing the keyboard.
Rendering reuses `TtdScreen.drawDisplay`, and the peripherals stay independent.
See [keyboard usage and ABI](keyboard.md).

## TTD power lifecycle

TTD supply is independent of its persistent character buffer and cursor.
`TtdDevice` powers on when claimed, powers off on release/invalidation, and
exposes host-only `setPowered(boolean)` for a future supply/budget policy.
Disabling supply while owned retains the connection but makes guest calls
unavailable. No energy amounts or consumption rates have been introduced.

The server synchronizes power separately from cell revisions through the small
`TtdPayloads.Power` message. Initial chunk tags and explicit snapshot responses
also include current supply, so late viewers do not render stale images. World
and item saves retain cells/cursor but do not persist an operational powered
flag: reload requires a new computer attachment. The world renderer and both
enlarged-view paths show only black while unpowered. Runtime exit/crash is now
handled by the case each tick, releasing peripherals without waiting for the
once-per-second chat delivery cycle.


## RAM verification in game

With the computer powered off, shift-click a stack of RAM into it: four modules should fill the four RAM slots, with the remainder in the player inventory. Shift-click a CPU stack to install exactly one CPU. CPU and RAM stacks should merge up to 16 outside the computer. Try ordinary clicks and dragging as well: each computer slot must hold at most one item, and CPUs, RAM and EEPROMs must stay in their dedicated slots.

Boot with BIOS and CPU but no RAM to check the missing-RAM diagnostic. Power off, add one RAM module, and power on to run the bundled BIOS. Power locking must prevent hardware insertion and removal. Save and reload with one through four modules to check persistence; remove modules only while powered off and reboot to apply the new capacity. Existing computers require manual RAM installation.


## Bootloader EEPROM and passive kernel runtime

`openwac:bootloader_bios_eeprom` appears as **BIOS EEPROM (Bootloader)** in the
OpenWAC creative tab. Install it in the dedicated BIOS slot, with a CPU, RAM,
and the boot media/peripherals required by the guest bootloader. Its built-in
firmware reference `openwac:bootloader-v1` loads the exact bundled copy of
`bootloader.wasm`; it never reads a development path at runtime. The
ordinary diagnostic BIOS remains available. Replacing the supplied binary
requires updating `../../src/main/resources/data/openwac/firmware/bootloader-v1.wasm`.

`WasmComputer` is the loader-independent session boundary. The Chicory
implementation owns one passive kernel instance, shared memory accounting,
bounded asynchronous compilation, exclusive execution ownership, and bounded
exit notifications. Kernel initialization and syscalls use the caller's
continuation and instruction fuel, including HAL work. Competing callers park
in the scheduler until ownership is released. The kernel has its own WASM
memory, consumes installed RAM, and occupies no process slot.

`ProcessManager` distinguishes ordinary yields, next-tick installation waits,
blocked syscalls with sticky wake tokens, and kernel-ownership waits. Kills
inside kernel code are deferred until the call returns. Exit hooks run as
bounded tick maintenance. Kernel panic publishes a diagnostic and a machine
event, wakes waiting callers, and terminates unprivileged processes. Cold
restart discards kernel state and releases pages after execution stops.
See [the runtime contract](syscalls.md-runtime-contract) for details.


## Whole-computer crash reports

Fatal startup failures and transitions to `CRASHED` produce one ERROR report
in the server console and `logs/latest.log`, plus a short message to nearby
authorized players directing them to the server log. Reports identify the
machine UUID, dimension, position, execution tick, failing process, BIOS firmware,
CPU limits, and allocated/installed RAM pages. They retain the original failure
stage, exception message, cause chain and stack trace; a prior failed kernel
installation is included if the computer subsequently crashes. Trace text and
reports are bounded and explicitly marked when truncated.

Individual program traps, handled installation errors, nonfatal kernel panics,
and normal shutdowns do not print computer crash reports. Reports are emitted
once per power attempt; another failed power cycle may report again. Detailed
reports remain runtime-only; world saves retain only the short failure message.
After a failed boot, search the server log for `OpenWAC computer crash report`.

Run focused checks with `gradlew.bat verifyRuntime --args=--crash-reports-only`.
They include a controlled two-second watchdog timeout, a missing BIOS import,
cause-chain propagation, quiet child failures and deduplication.


## Rejected kernel installation reports

A rejected kernel is reported even when the bootloader handles `-16` and keeps
the computer running. The server prints a WARN entry beginning with
`OpenWAC kernel installation failed (-16)` to its console and `logs/latest.log`.
Nearby authorized players receive a short installation-failure message directing
them to the log. This warning is separate from a whole-computer crash report.

Each report includes the machine UUID, dimension, block position, installer PID,
kernel byte length, SHA-256, failure stage, original exception, cause chain and
bounded stack trace. Validation errors identify missing exports, expected and
actual function signatures, invalid buffer bounds, and requested/available RAM
pages. No kernel bytes or guest buffers are printed. HAL return values and retry
behavior are unchanged.

One runtime/power attempt reports each distinct kernel hash, stage and cause
once, up to sixteen distinct reports. The bounded queue remains available until
the server thread drains it, including after the installer exits. A new power
cycle resets suppression. Successful installations, already-installed rejections
(`-15`), ordinary program traps, and shutdown cancellation remain quiet.

Run focused checks with `gradlew.bat verifyRuntime --args=--kernel-reports-only`.
These checks use synthetic kernels and preserve the intentionally updated
bundled bootloader.
