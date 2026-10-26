# Host application interface (HAL)

WebAssembly guests import host functions from module `hal`. An import must use the exact field name and Wasm type below. The runtime links privileged imports only for privileged processes; an unprivileged module that declares one fails to instantiate. `hal_yield` and `hal_restart` are linked to both privilege tiers. `sys.syscall` is separately linked for ordinary processes, including unprivileged ones, and is not part of the HAL. A kernel instance has privileged HAL services, but does not receive `hal_install_kernel` or `sys.syscall`.

All pointers refer to the caller's exported linear memory named `memory`; lengths and capacities count bytes. Guest addresses and lengths are signed Wasm `i32` values and must be nonnegative. A missing `memory` export produces `-9` in calls using the common HAL adapter; invalid ranges produce `-8` unless a function documents a different mapping. Device calls and syscalls limit the combined input and output size to 256 KiB. Process binaries and kernel binaries are limited to 1 MiB. Imports are synchronous host calls; they do not retain guest pointers.

## Import index

| Import | Wasm type | Link/access | Purpose |
| --- | --- | --- | --- |
| `increment` | `() -> ()` | Privileged | Charges one fuel unit; records no externally visible value. |
| `hal_yield` | `() -> ()` | Any process | Gives up this scheduling turn. |
| `hal_restart` | `() -> i32` | Any process | Requests a computer restart; unprivileged caller gets `-1`, privileged call does not return. |
| `hal_cpu_time_ns` | `() -> i64` | Privileged | Last completed tick's elapsed runtime, in nanoseconds. |
| `hal_ram_used_pages` | `() -> i64` | Privileged | Current allocated WASM memory, in 64 KiB pages. |
| `hal_device_count` | `() -> i32` | Privileged | Current attached-device count. |
| `hal_device_info` | `(i32, i32, i32) -> i32` | Privileged | Read a device interface ID and UUID. |
| `hal_device_call` | `(i32, i32, i32, i32, i32, i32) -> i32` | Privileged | Invoke a device operation using its UUID. |
| `hal_poll_event` | `(i32, i32) -> i32` | Privileged | Poll this process's FIFO mailbox. |
| `hal_chat_print` | `(i32, i32) -> i32` | Privileged | Submit UTF-8 text to the integration's chat output. |
| `hal_spawn_process` | `(i32, i32) -> i64` | Privileged | Queue an unprivileged child. |
| `hal_spawn_process_privileged` | `(i32, i32) -> i64` | Privileged | Queue a privileged child. |
| `hal_kill_process` | `(i64) -> i32` | Privileged | Queue a process termination request. |
| `hal_wake` | `(i64) -> i32` | Privileged | Deliver a sticky wake to a process blocked in a syscall. |
| `hal_install_kernel` | `(i32, i32) -> i32` | Privileged process only | Request installation of a passive syscall kernel. |

## Device enumeration and calls

### `hal_device_count() -> i32`

Returns the number of currently attached devices. Enumeration indices are transient: additions or removals can change their meaning. Enumerate again when needed and use UUIDs as identities.

### `hal_device_info(index, output_ptr, output_capacity) -> i32`

On success, writes a 20-byte descriptor and returns `0`:

| Byte offset | Size | Encoding |
| ---: | ---: | --- |
| 0 | 4 | Numeric interface ID, little-endian `u32` |
| 4 | 16 | UUID: most-significant 64 bits then least-significant 64 bits, each big-endian (standard UUID byte order) |

Devices are enumerated in ascending UUID order. The index must be nonnegative and in range, the output capacity must be at least 20, and the output range must be writable. Errors: `-3` for invalid/unknown index or negative pointer; `-5` for capacity below 20; `-9` for missing or inaccessible guest memory. A memory range failure on this entry point is mapped to `-9`.

### `hal_device_call(uuid_ptr, operation, arguments_ptr, arguments_len, response_ptr, response_capacity) -> i32`

`uuid_ptr` addresses 16 standard UUID bytes; `operation` is passed as a 32-bit operation number. The host copies the input bytes before calling the device. Success returns status `0` and copies the device's response bytes into the output buffer, including when the response is nonempty. Negative results are device or HAL errors and copy no response. Determine response length from the operation contract in [device interfaces](device-interfaces.md).

Input and output lengths must be nonnegative; their sum may not exceed 256 KiB. The UUID range and both buffers must fit in `memory`. Charge is `8 + ceil((input length + output capacity) / 256)` fuel units. Common validation results are `-3` for negative lengths, `-8` for invalid ranges, `-9` when `memory` is absent, and `-11` when the combined bound is exceeded. An unknown/unavailable UUID returns `-7`. Negative device results pass through; consult the selected device interface for their meaning.

## Measurements

`hal_cpu_time_ns` reports the elapsed wall-clock nanoseconds recorded for the last completed computer tick; its value remains stable until the next tick completes. It begins at zero. The measurement is provided by the runtime and does not include time waiting in the executor queue or between ticks. `hal_ram_used_pages` reports currently allocated WASM pages across process and kernel memories; one page is 65,536 bytes. Both calls charge one fuel unit.

## Scheduling and mailbox

### `hal_yield() -> ()`

Suspends the current process until the scheduler gives it another turn. The process is put at the back of the ready queue and can resume in the same simulation tick if fuel remains. Unused fuel in its quantum is not consumed.

### `hal_poll_event(output_ptr, output_capacity) -> i32`

Polls the calling process's FIFO mailbox without blocking. Each process has a separate mailbox with capacity 64. On a fitting message, copies its opaque payload, removes it, and returns its byte length. Returns `-1` if the mailbox is empty. If the head message does not fit, returns `-2` and leaves it queued; a zero-byte message returns `0`, distinct from empty. Negative arguments return `-2`; missing memory or invalid ranges return `-3`; capacity above 256 KiB returns `-11`. Charge is `1 + ceil(output_capacity / 256)` fuel units. Payload meanings are defined by message producers, not by the HAL.

### `hal_chat_print(text_ptr, text_len) -> i32`

Submits at most 2,048 bytes decoded as UTF-8 to the host chat output. Returns `0` if the output predicate accepts it and `-11` if rejected. Negative/oversized length returns `-3`; invalid pointer/range returns `-8`, absent memory returns `-9`. Charge is `1 + ceil(text_len / 256)` fuel units. The integration determines where accepted text is shown.

### `hal_restart() -> i32`

Charges one fuel unit. An unprivileged caller receives `-1` and continues. A privileged caller requests a full machine restart; execution unwinds rather than returning normally.

## Process control

### `hal_spawn_process(wasm_ptr, wasm_len) -> i64` and `hal_spawn_process_privileged(wasm_ptr, wasm_len) -> i64`

Pass compiled Wasm bytes, not WAT text. A valid request allocates an ID and queues process creation; it does not mean compilation or startup succeeded, and the child is not run immediately. The process starts through the runtime's normal process entry point. The first function is available to privileged callers and creates an unprivileged process; the second creates a privileged process. Both binaries must be 8 bytes through 1 MiB and fit in caller memory. Charge is `8 + ceil(wasm_len / 16,384)` fuel units. Returns `0` for invalid size/range, process-limit exhaustion, or inability to queue; a positive value is the allocated process ID. Creation failures occur asynchronously and are reported by runtime lifecycle handling.

During boot, both spawn operations queue work until the bootloader has exited
successfully and any pending kernel initialization has completed. Children do not
compile, instantiate, or run during bootloader execution. Their reserved IDs
can be woken or killed while pending. Boot failure discards pending children.

### `hal_kill_process(process_id) -> i32`

Queues termination and returns `0` when accepted or `-1` when the request cannot be queued (including a nonpositive ID). It charges two fuel units. A process executing inside the kernel can finish that kernel call before termination is applied. The runtime's process manager caps pending kill requests.

### `hal_wake(process_id) -> i32`

Charges one fuel unit and returns `0` if the ID belongs to a current or queued process, or `-3` for a nonpositive or unknown ID. A wake is sticky: if delivered before the process blocks in a syscall, the next block completes immediately. Waking a process that is not blocked is harmless. It does not resume a process waiting for kernel ownership; that wait has a separate runtime path.

## Kernel installation

### `hal_install_kernel(wasm_ptr, wasm_len) -> i32`

Only ordinary privileged processes receive this import; the kernel instance itself does not. The binary must be between 8 bytes and 1 MiB and accessible in the caller's memory. The request charges `8 + ceil(wasm_len / 16,384)` units, then delegates compilation, instantiation, and initialization to the computer runtime. Installation runs as maintenance work and can keep the installer suspended across ticks. The kernel is a passive instance, not a process; its syscall ABI is described in [kernel runtime](kernel-runtime.md).

Returns `-3` for a binary length outside 8 bytes through 1 MiB, `-8` for an invalid input range, and `-9` if `memory` is missing. The computer returns `0` when installed, `-15` when installation cannot start because the kernel is not empty, and `-16` when compilation, linking, required exports, initialization, or compiler admission fails. Fuel charging can suspend and continue across turns; it does not return an out-of-fuel status. Kernel descriptor, initialization, retry, and failure behavior belongs to the kernel runtime contract.

During bootloader execution, compilation, instantiation, required-export
checks, and initial I/O descriptor validation finish before the call returns.
Malformed images return `-16`, release their memory, and allow the bootloader
to retry. Success returns `0` with the kernel installed; only `kernel_init`
waits until the bootloader returns from `_start` and releases its memory.
The installed kernel reserves its slot, so another installation returns `-15`.
Syscalls return `-13` until initialization completes. Deferred initialization
and descriptor revalidation finish before queued children start. Initialization
failure reports diagnostics and fails boot, since the original caller has
exited. A bootloader trap or termination discards initialization and releases
the kernel. After boot, the install call also waits for initialization and
returns its errors directly.

## Fuel and runtime limits

Guest instructions and host work are metered against the machine's per-tick fuel budget. HAL charges are shown above; guest execution and kernel work also consume fuel. Charges that exceed the current quantum suspend and continue in later turns/ticks. Process order is round-robin; yielded processes rejoin the ready queue. Exact machine fuel, quantum, memory, and process limits depend on installed hardware/configuration and are specified in [architecture](architecture.md) and [kernel runtime](kernel-runtime.md). `memory.grow` shares the machine memory budget across guest and kernel instances.

Implementation: [ChicoryHostImports.java](../src/main/java/org/mod/openwac/runtime/chicory/ChicoryHostImports.java), [ChicoryProcess.java](../src/main/java/org/mod/openwac/runtime/chicory/ChicoryProcess.java). Return to the [documentation index](README.md).
