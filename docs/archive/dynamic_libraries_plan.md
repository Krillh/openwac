# OpenWAC libraries managed by the OS

Status: proposed implementation plan. The functionality described here is not yet implemented.

## Summary and scope

Implement libraries registered in a per-computer host registry and linked only
when a process starts. The guest OS resolves dependencies and controls
registration lifetime. The host shares parsed library code across processes,
while each process owns its execution state, globals, and writable memory.

Support an OpenWAC library ABI with two profiles:

- **Memoryless:** scalar functions with no linear memory.
- **Process memory:** executable and libraries use one ordinary linear memory
  belonging to that process.

Defer runtime loading, general LLVM/Emscripten dynamic linking compatibility,
shared writable memory between processes, and cross-module function pointers.

The existing [device interfaces](true_device_interfaces.md) provide access to
disk blocks and imported file bytes. Filesystem paths and library lookup remain
guest OS responsibilities; this feature does not change device operations.

## Host changes

### Registry and lifetime

- Add a platform-neutral library registry owned by each computer runtime. Keep
  Chicory types within the runtime adapter.
- Registration copies, validates, hashes, and parses library bytes without
  instantiating them or running guest initialization.
- Deduplicate identical bytes within that computer. Retain one parsed module
  with separate registration references and process references.
- Use opaque positive 64-bit handles, scoped to the computer and never reused
  during its runtime lifetime.
- Releasing a registration prevents future attachment through that handle.
  Already admitted processes retain their references and continue normally.
- Reclaim parsed code when its last registration and process reference
  disappear. Do not retain an additional invisible cache in v1.
- Clear registrations on power-off or reboot. Release process references only
  after execution actually terminates, including cancellation and failed
  initialization.
- Default limits: 32 retained library modules and 16 MiB of retained library
  binaries per computer. Preserve existing per-module structure limits. Count
  modules retained only by running processes toward these limits.

### HAL and process creation

Introduce privileged lifecycle operations, documented alongside existing
process control in [HAL](hal.md):

| Operation | Contract |
| --- | --- |
| `hal_library_register` | Accept library bytes and return a registration handle or an error. |
| `hal_library_release` | Release one registration handle. |
| `hal_spawn_process_linked` | Accept executable bytes, a complete mapping of library import namespaces to registration handles, and requested child privilege. |

Use bounded, versioned little-endian descriptors for the new operations.
Preserve existing spawn functions and their behavior for standalone modules.

- Linked spawn follows existing deferred process creation semantics: a returned
  process ID means admission, not successful startup.
- Pin all supplied handles when accepting the request, before placing it in
  the spawn queue. Release pins on rejection, cancellation, or startup failure.
- Permit at most 16 library bindings per process. Reject duplicate namespaces,
  invalid handles, missing dependencies, dependency cycles, and incompatible
  function signatures.
- Reserve `hal` and the runtime ABI namespace; libraries cannot replace host
  imports.
- Resolve library dependencies exclusively through the admitted binding set.
  Neither Java paths nor guest filesystem paths are interpreted by the host.
- Produce bounded process failure diagnostics identifying dependency,
  signature, initialization, or quota failures.
- Extend queued spawn requests, process factories, engine inputs, and host
  services to carry immutable linked-process specifications.

### Linking and scheduling

- Instantiate each distinct admitted library once per process, in dependency
  order, using the shared parsed module.
- Create a process-local linking environment. Register library exports under
  their declared namespaces, then instantiate the executable.
- Run optional library `_initialize: () -> ()` exports in dependency order,
  followed by executable `_start: () -> ()`.
- Reject automatic WASM start sections in the new ABI; initialization must
  occur through explicitly scheduled exports.
- Execute every instance on the process's existing virtual thread. Libraries
  receive no separate scheduler entries or fuel grants.
- Install metering and cancellation listeners on every instance. Charge library
  instructions, initialization, and host work against the same process fuel
  counter.
- Enforce aggregate active call depth and operand-stack limits across module
  boundaries, including nested library calls.
- Keep parsing and linking off the Minecraft server thread. Bound registration,
  copying, and initialization work; include registration in cancellation and
  watchdog handling.

## Library ABI and memory

### Dependencies and exports

- Use standard WASM imports in section 2 for function dependencies.
- Add a versioned `openwac.link` custom section declaring ABI version, module
  role, direct dependency namespaces, and memory-layout requirements.
- Use versioned logical names such as `lib:math/v1`. Guest paths remain OS
  package metadata.
- Require the declared dependency set to match the library namespaces actually
  imported. Reject unsupported ABI versions.
- Support scalar arguments and results. Buffer pointers refer to the process
  memory profile.
- Libraries execute with their process's privilege tier; dependency loading
  cannot grant additional HAL capabilities.

### Memoryless profile

- Permit modules with no memory definition or import and no data segments.
- Allocate no linear-memory pages for these instances.
- Keep writable WASM globals private to each process instance.
- Allow a memoryless executable and its memoryless libraries to run with zero
  linear-memory allocation.

### Process memory profile

- The host creates one memory object for the linked process. All participating
  modules import it as `openwac_rt.memory` and re-export it as `memory` where
  HAL access is required.
- Extend both binary preflight and parsed-module validation to admit only the
  specific runtime memory/global imports defined by this ABI.
- Assign each module an aligned data region from its declared size and
  alignment. Supply its address through an immutable `openwac_rt.data_base`
  global.
- Require position-independent data addressing and validate active data
  segments against the module's assigned region.
- Place a 64 KiB process stack after the data regions and expose a shared
  mutable `openwac_rt.stack_pointer`; place the heap after the stack and expose
  immutable `openwac_rt.heap_base`.
- Use one process allocator. Modules must not independently allocate
  overlapping heap or stack regions.
- Reject module-defined memories in this profile and reject imported tables in
  v1. Existing standalone modules retain their current profile.
- Memory is private to the process. Sharing an ordinary memory among its
  instances does not enable WASM threads.

### Resource accounting

- Replace reservation of every process's configured maximum with accounting
  for allocated pages.
- Charge initial allocation and successful `memory.grow` atomically against
  process, computer, and server limits. Return WASM growth failure when
  allocation cannot be admitted.
- Count the process memory once regardless of how many instances import it.
  Release its accounting once when execution terminates.
- Preserve the current 256 MiB server memory ceiling and existing process and
  thread limits.
- Bound retained module bytes and decoded structures separately from linear
  memory. Document that small instance metadata and initialized private data
  still consume memory even when code is shared.

## OS, kernel, and toolchain requirements

### OS loader and library manager

- Implement library file lookup above the existing block-device interface; no
  disk-device ABI change is required.
- Parse executable and library manifests, resolve the complete dependency
  graph, enforce compatible ABI versions, and reject cycles before spawn.
- Maintain logical library names mapped to installed files, content hashes,
  and registration handles.
- Register dependencies as needed and submit the complete binding set when
  creating a process.
- Release idle registrations according to OS memory policy. Host process
  references protect active users.
- Register an updated library under a new handle; existing processes continue
  using their original code.
- Rebuild registrations from disk after reboot. Do not persist host handles.

### Kernel boundary

- Keep registration, release, and linked spawn privileged.
- Expose process creation through the guest kernel; applications request
  executable paths or package identities.
- Resolve all dependencies before process startup. Applications cannot acquire
  additional libraries during execution.
- If loading services are separate from the kernel, use a privileged loader
  service; do not pass host registration capabilities to unprivileged
  applications.
- Report loader and startup failures through the guest process-management
  interface.
- The current kernel syscall bridge is reserved rather than implemented. This
  plan defines its required behavior; implementing a complete kernel remains
  a separate prerequisite for unprivileged application launch.

### Toolchain and documentation

- Supply linker/compiler examples and a manifest generator for the OpenWAC ABI.
- Provide a memoryless math example and a buffer-processing library using
  process memory.
- Clearly identify unsupported conventional C/C++ features, especially
  cross-module function pointers and standard dynamic-linker compatibility.
- Update HAL documentation, architecture documentation, and guest headers when
  implementation begins. Explain the distinction between registered code,
  per-process instances, and process memory.

## Validation and implementation order

1. Implement registry ownership, deduplication, reference lifetime, and bounded
   admission.
2. Add linked process specifications and memoryless library execution.
3. Add process memory layout, restricted runtime imports, and page accounting.
4. Add OS/kernel contracts, toolchain examples, and documentation.

Acceptance checks must cover:

- Two processes share one parsed library while maintaining independent writable
  globals and fuel counters.
- A memoryless library allocates zero linear-memory pages.
- A process and multiple memory-using libraries share one memory without
  overlapping initialization regions.
- Library loops, initialization, yielding, cancellation, and nested calls
  preserve scheduler fairness and enforce aggregate limits.
- Releasing a registration during queued or suspended execution cannot
  invalidate the process.
- Missing dependencies, cycles, signature mismatches, privilege violations,
  and quota failures cleanly release resources.
- Memory growth failure, process exit, failed startup, power-off, and reboot
  leave correct accounting.
- Existing standalone firmware and spawn behavior remain compatible.

Measure retained parsed modules and allocated pages explicitly; do not use JVM
heap measurements alone as proof of sharing.
