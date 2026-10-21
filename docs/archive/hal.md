# WebAssembly HAL

Guest modules import host functions from the Wasm module `hal`. The runtime
does not add imports to a module: declare each function the guest calls using
the exact name and signature shown here.

The Java runtime implements kernel installation, the `sys.syscall` bridge,
sticky wake tokens, and process-exit callbacks. Kernel instructions and HAL
work are metered as they execute. See [System calls](syscalls.md) for the
passive kernel ABI and the bounded exit-callback policy.

## Quick reference

| Import | Wasm signature | Access                            | Result |
| --- | --- |-----------------------------------| --- |
| `increment` | `() -> ()` | Privileged                        | Records one hardware operation. |
| `hal_yield` | `() -> ()` | Any process                       | Suspends until a later round-robin turn, possibly in the same tick. |
| `hal_restart` | `() -> i32` | Privileged                        | Powers off and boots again; returns `-1` only for an unprivileged caller. |
| `hal_cpu_time_ns` | `() -> i64` | Privileged                        | Elapsed nanoseconds of the computer's last completed execution tick. |
| `hal_ram_used_pages` | `() -> i64` | Privileged                        | Allocated WASM pages across the computer, including kernel memory. |
| `hal_device_count` | `() -> i32` | Privileged                        | Number of attached devices. |
| `hal_device_info` | `(i32, i32, i32) -> i32` | Privileged                        | Writes interface ID and UUID to guest memory. |
| `hal_device_call` | `(i32, i32, i32, i32, i32, i32) -> i32` | Privileged                        | Calls a device operation by UUID. |
| `hal_poll_event` | `(i32, i32) -> i32` | Privileged                        | Removes the next fitting message from this process's mailbox. |
| `hal_spawn_process` | `(i32, i32) -> i64` | Privileged                        | Queues an unprivileged child process. |
| `hal_spawn_process_privileged` | `(i32, i32) -> i64` | Privileged                        | Queues a privileged child process. |
| `hal_kill_process` | `(i64) -> i32` | Privileged                        | Queues a process kill. |
| `hal_install_kernel` | `(i32, i32) -> i32` | Privileged                        | Installs the calling computer's syscall kernel. |
| `hal_wake` | `(i64) -> i32` | Privileged                        | Wakes a process blocked in a syscall. |
| `hal_DEBUG_PRINT` | `(i32, i32) -> i32` | Debug host build only; privileged | Writes guest bytes and a newline to host stdout. |

For buffer calls, `i32` pointer and length values are byte offsets and byte
counts in the guest's exported linear memory named `memory`. Values must be
nonnegative and the full range must fit in memory. HAL buffers are limited to
16 MiB; some operations have a smaller work bound. `i64` is used for process
IDs. The UUID byte sequence is the standard 16-byte UUID representation.

The caller must be privileged to import every function except `hal_yield`
and `hal_restart`. The latter checks privilege when called and returns `-1`
for an unprivileged caller.
The boot program and privileged children run with that tier. Unprivileged
modules that import a privileged function fail to instantiate because that
import is not linked. Unprivileged programs make kernel requests through the
`sys` module; see [System calls](syscalls.md).

## Computer metrics

`hal_cpu_time_ns() -> i64` reports elapsed wall-clock nanoseconds for the last
completed execution tick, including guest/kernel execution, HAL work and
scheduler waits during that tick, excluding executor queue delay and time
between ticks. It starts at zero and is stable until the next completed tick;
50,000,000 ns is a 20 Hz tick's allowance. `hal_ram_used_pages() -> i64` reports
currently allocated 65,536-byte WASM pages, shared across guest and kernel
memories. Both imports require privilege and cost one fuel unit.

## Imports used by the installed kernel

The current kernel imports these privileged HAL functions from its separate
Wasm store:

| Import | Kernel use |
| --- | --- |
| [`hal_device_count`](#hal_device_count) | Enumerate attached devices. |
| [`hal_device_info`](#hal_device_info) | Get a device's interface ID and UUID. |
| [`hal_device_call`](#hal_device_call) | Read and write disks and communicate with display and keyboard devices. |
| [`hal_spawn_process`](#hal_spawn_process-and-hal_spawn_process_privileged) | Spawn an unprivileged process. |
| [`hal_spawn_process_privileged`](#hal_spawn_process-and-hal_spawn_process_privileged) | Spawn a privileged process. |
| [`hal_kill_process`](#hal_kill_process) | Request process termination. |
| [`hal_wake`](#hal_wake) | Resume a process waiting for external I/O. |

`hal_install_kernel` is used by the privileged boot program to install the
kernel. The installed kernel does not import it and cannot replace itself.
The kernel's imports are declared in
[`_llm/kernel/src/syscalls.zig`](../../_llm/kernel/src/syscalls.zig); device
calls are wrapped by the kernel's display and keyboard modules.

## Device discovery and access

### `hal_device_count`

```wat
(import "hal" "hal_device_count" (func (result i32)))
```

Returns the number of devices currently attached to the computer. Device
indices can change when devices are added or removed.

### `hal_device_info`

```wat
(import "hal" "hal_device_info"
  (func (param i32 i32 i32) (result i32)))
```

Parameters are `index`, `output_ptr`, and `output_capacity`. On success the
host writes this 20-byte record and returns `0`:

| Offset | Size | Contents |
| ---: | ---: | --- |
| 0 | 4 | Interface ID, little-endian `u32` |
| 4 | 16 | Device UUID bytes |

Devices are enumerated in ascending UUID order. Use the UUID from this record
for later calls; do not retain an index as a device identity. Returns `-3`
for a negative index/pointer or an unknown index, `-5` if capacity is below
20, and `-9` if the memory export or access is invalid.

### `hal_device_call`

```wat
(import "hal" "hal_device_call"
  (func (param i32 i32 i32 i32 i32 i32) (result i32)))
```

Parameters are `uuid_ptr`, `operation`, `arguments_ptr`, `arguments_len`,
`response_ptr`, and `response_capacity`. The UUID pointer addresses 16 bytes;
the operation is interpreted as an unsigned 32-bit operation number. The host
copies the argument buffer, calls the device, and copies response bytes back
only when the device returns a positive byte count. The result is therefore
negative on failure, zero for success without response bytes, or a positive
response length. Argument consumption is not reflected in the result.

The call requires privilege. Argument and response buffers are each limited
to 16 MiB, and their combined size may not exceed 256 KiB. The host charges 8
units plus one per 256 combined bytes, rounded up. A call waits for a later
tick if its charge does not fit the remaining budget; it returns `-11` if its
charge cannot fit alongside one scheduler quantum. Device operation numbers
and device-specific results are in [Device interfaces](device_interfaces.md).

Common HAL errors include `-3` for invalid arguments, `-7` if the UUID is not
available, `-8` for an invalid pointer/range, `-9` for missing or inaccessible
guest memory, and `-11` for an operation too expensive to fit in one tick.
Negative device results pass through unchanged, so their meanings depend on
the selected interface.

## Scheduling and messages

### `hal_yield`

```wat
(import "hal" "hal_yield" (func))
```

Suspends the caller until a later round-robin turn, then resumes after the
call. Yielding gives up the current turn and refunds unused fuel. The process
may run again in the same simulation tick if budget remains.

### `hal_restart`

```wat
(import "hal" "hal_restart" (func (result i32)))
```

Requests a full power cycle of the calling computer. An unprivileged caller
receives `-1` and execution continues. For a privileged caller, including the
installed kernel, the call never returns: its guest execution unwinds, the old
runtime shuts down, and the computer boots again from its installed BIOS.
All processes and kernel memory are discarded, peripheral leases are released
and reacquired, and persistent disks retain their contents. The server waits
for the old runtime to finish before starting the new one. Costs one fuel unit.

### `hal_poll_event`

```wat
(import "hal" "hal_poll_event"
  (func (param i32 i32) (result i32)))
```

Parameters are `output_ptr` and `output_capacity`. This is a nonblocking FIFO
poll of the caller's mailbox. A fitting message is copied and removed; the
result is its byte length. Message bytes are opaque to the HAL. Returns `-1`
when empty, `-2` for negative arguments or when the next message is too large
to fit, `-3` for missing/invalid guest memory, and `-11` when capacity
exceeds the 256 KiB operation bound. A message that does not fit stays queued.
An empty message returns `0`, distinct from an empty mailbox. The charge is
one unit plus one per 256 bytes of capacity, rounded up.

## Process control

### `hal_spawn_process` and `hal_spawn_process_privileged`

```wat
(import "hal" "hal_spawn_process" (func (param i32 i32) (result i64)))
(import "hal" "hal_spawn_process_privileged"
  (func (param i32 i32) (result i64)))
```

Both take a pointer and byte length for a Wasm binary in guest memory. The
ordinary form creates an unprivileged child; the privileged form creates a
privileged child and is available only to privileged callers. The module
starts at its exported `_start` function and must declare all imports it uses.
Pass compiled Wasm bytes, not WAT source text.

A positive result is the allocated process ID and means the request was
queued, not that compilation or startup succeeded. `0` indicates invalid
length or memory, empty input, exhausted process-ID space, or a request queue
failure. Privileged spawn by an unprivileged caller returns `-12`. A valid
request costs 8 units plus one per 16 KiB of Wasm, rounded up; an impossible
charge returns `-11`. Spawn requests are processed by the simulator after the
call, so the child does not execute immediately.

### `hal_kill_process`

```wat
(import "hal" "hal_kill_process" (func (param i64) (result i32)))
```

Queues a kill request for the positive process ID. Returns `0` when queued,
`-1` for process ID zero or a queue-lock failure, `-12` without privilege,
and `-11` if the charge cannot fit in one tick. The operation costs 2 units.
The target may finish a kernel call before the pending kill takes effect.

### `hal_wake`

```wat
(import "hal" "hal_wake" (func (param i64) (result i32)))
```

Wakes a process parked in a blocking syscall. The wake is sticky: if it arrives
before the process blocks, the next block completes immediately. Spurious
wakes are harmless. Returns `0` for a process on this computer, `-3` for a
nonpositive or unknown ID, `-12` without privilege, or `-11` if the one-unit
charge cannot fit in a tick.

### `hal_install_kernel`

```wat
(import "hal" "hal_install_kernel" (func (param i32 i32) (result i32)))
```

Takes a pointer and length for a Wasm kernel binary. The host compiles it,
instantiates the kernel, reads its `kernel_get_io_buffers` descriptor, and calls optional
`kernel_init`. Installation suspends the caller until at least the next tick while
compilation runs on a bounded executor. It remains suspended across later
ticks if compilation has not finished. The kernel is a separate passive
instance, sharing the computer RAM pool without occupying a process slot. A successful install returns `0`. It returns `-12` without
privilege, `-15` if installation is in progress, a kernel is already installed, or it has panicked, `-16` if
compilation/instantiation/required exports/initialization fail, `-3` for
invalid length, `-8` or `-9` for memory errors, and `-11` for an impossible
charge. The charge is 8 units plus one per 16 KiB of binary, rounded up, plus
fuel consumed during kernel instantiation, descriptor lookup and `kernel_init`,
charged as execution proceeds. Failed installation releases kernel resources
and permits retry; a panicked kernel cannot be replaced. See [System calls](syscalls.md) for the
kernel ABI.

Rejected kernel candidates returning `-16` also generate a bounded server-log
warning and a short nearby-player message, independently of whether the
computer crashes. Reports identify the module SHA-256, installer, failure stage
and original cause. Identical retries are suppressed for that power attempt;
at most sixteen distinct kernel failures are reported. Successful installs,
`-15` rejections and shutdown cancellation do not generate these warnings.

## Debug output

### `hal_DEBUG_PRINT`

```wat
(import "hal" "hal_DEBUG_PRINT" (func (param i32 i32) (result i32)))
```

This import is registered only when the host is built with debug assertions
and is available only to privileged modules. It writes the requested guest
bytes to host stdout, followed by a newline. The payload is limited to 256
KiB and costs one unit plus one per 256 bytes, rounded up. It returns `0` on
success, `-11` when too large or too expensive, `-1` for a negative pointer
or length, `-2` for unavailable or invalid guest memory, and `-3` for a host
stdout write error.

## Import declarations in C

The companion C header [hal.h](./hal.h) provides the declarations, constants,
and import-name macros. Its fixed-width integer signatures map to the Wasm
signatures above when targeting `wasm32`. `HAL_EXPORT_START`
declares the `_start` export; it does not declare a HAL import. The `DEBUG`
macro controls whether the header declares `hal_DEBUG_PRINT`, independently
of whether the host was built with debug assertions.

## CPU budget

Each computer's installed CPU supplies the total fuel budget per tick and
maximum process count; tier 1 provides 65,536 fuel and 16 processes, with a
256-unit quantum. At least one RAM module is required; tier 1 modules provide
1 MiB each, with four dedicated slots. Allocated guest and kernel memory share
this capacity, and `memory.grow` returns -1 when capacity is exhausted.

Processes run round-robin, refund unused quantum, and can receive a final
partial quantum. Ordinary yields may resume within the same tick. Kernel
installation uses a distinct next-tick wait; blocked syscalls require a sticky
wake, and kernel-ownership waiters resume when the instance becomes available.
Kernel instructions, buffer copies, and privileged HAL work consume the active
caller's fuel as they execute. Charges can continue across quanta and ticks.
Kernel exit callbacks consume at most one whole tick allowance and cannot
suspend. All execution remains off the Minecraft tick thread.
