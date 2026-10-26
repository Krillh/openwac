# Kernel runtime contract

For guest kernel developers: this page defines the Java runtime's kernel bridge. The kernel is a guest Wasm module; it defines OS syscall policy. See [HAL](hal.md) for privileged host imports and [architecture](architecture.md) for scheduling and lifecycle.

## Kernel installation

A privileged process installs the kernel through `hal_install_kernel(i32 wasm_ptr, i32 wasm_len) -> i32`. The byte range must be valid and contain 8 bytes to 1 MiB. The host copies the bytes and charges `8 + ceil(wasm_len / 16384)` fuel before submitting compilation. A missing computer returns `-16`. Only one installation attempt can be active or succeed: once installation has begun, another attempt, including after a successful install or kernel panic, returns `-15`.

During bootloader execution, compilation, instantiation, required-export
checks, and initial descriptor validation complete inside the install call.
Failures return `-16` and permit retry, allowing the bootloader to react to
malformed images. Success returns `0` with an installed kernel whose
`kernel_init` has not run. Kernel memory is already allocated and shares the
machine budget with the bootloader. Initialization waits until the bootloader
returns from `_start` successfully and releases its memory. Syscalls return
`-13` during this interval. Queued processes wait for initialization and
descriptor revalidation. Bootloader failure discards queued children and
releases the kernel. Deferred initialization failure reports diagnostics and
fails boot before children are created. Initialization uses a metered
maintenance continuation with the bootloader's process ID for diagnostics.

Compilation runs on a bounded compiler executor. The installer parks until at least the next simulation tick and remains parked if compilation is still running. The compiled module is instantiated in its own Wasm store and memory. Descriptor checks run as metered execution and may continue across fuel grants. For installation after boot, initialization also runs in the installer's continuation before the call returns. Failed installation returns `-16`, records diagnostics, releases kernel memory, and permits retry. The host does not accept replacement after installation succeeds.

Installation requires an exported linear memory named `memory` and these exact function signatures:

| Export | Signature | Requirement |
| --- | --- | --- |
| `kernel_get_io_buffers` | `() -> i32` | Required; returns descriptor address. |
| `kernel_syscall` | `(i64, i32, i32, i32, i32) -> i32` | Required; handles a request. |
| `kernel_init` | `() -> ()` | Optional; called once if present with this type, after bootloader exit for installation during boot. |
| `kernel_prepare_io_buffers` | `(i32, i32) -> i32` | Optional; enables on-demand buffer growth. |
| `kernel_process_exited` | `(i64, i32) -> ()` | Optional; receives queued process exits. |

A missing or incorrectly typed required export or an invalid initial descriptor fails installation with `-16`. Initialization traps return `-16` for installation after boot; deferred initialization traps fail boot. Optional exports are recognized only when their type matches; absent or wrong-typed optional exports are treated as unavailable. Kernel memory is charged to the runtime's allocated-page budgets, including successful growth; see [architecture](architecture.md) for memory accounting.

`kernel_get_io_buffers` returns the address of a 16-byte descriptor in kernel memory. It contains four little-endian `u32` values in this order: input pointer, input capacity, output pointer, output capacity. The descriptor and both complete ranges must fit in kernel memory. The host does not check that input and output ranges are disjoint, so a kernel must keep them separate if it needs to preserve request bytes while producing a response.

If `kernel_prepare_io_buffers` is available, the host requests larger capacities only when needed. Input requests can grow to 256 KiB and output requests to 16 KiB. Requested capacities are monotonic; after successful preparation the host rereads and validates the descriptor, requiring capacities not to shrink and to meet the request. Return `0` to accept preparation. A negative status returns to the caller without dispatch; a positive status, trap, invalid descriptor, or inadequate/shrunk buffers panics the kernel. Without this export, the installed descriptor capacities remain fixed. A request larger than the current kernel input capacity returns `-4` before dispatch.

## `sys.syscall` host import

Guest processes import:

```wat
(import "sys" "syscall"
  (func (param i32 i32 i32 i32 i32) (result i32)))
```

The arguments are `number`, request pointer, request length, response pointer, and response capacity. The `number` is forwarded unchanged. The host requires guest memory exported as `memory`; it validates both memory ranges, snapshots request bytes before dispatch, and copies only the positive number of response bytes returned by the kernel. Guest and kernel memories are separate. Request contents, syscall number meanings, response formats, ownership policy, and authorization are not interpreted by the host.

All pointer and length arguments must be nonnegative. The sum of request length and guest response capacity may not exceed 256 KiB. Copy work is charged before dispatch as `8 + ceil((in_len + out_cap) / 256)`. Charging can pause and resume across scheduler quanta/ticks without repeating the snapshot or dispatch. Kernel instructions and privileged HAL work are charged to the current caller as they execute.

The kernel receives `kernel_syscall(pid, flags, number, in_len, out_cap)`. `pid` is the caller's 64-bit process ID; bit zero of `flags` is set for privileged callers (other bits are currently zero); `number` is the unchanged guest number; `in_len` is the saved request length; and `out_cap` is the effective capacity, clamped to the kernel's output buffer and, when preparation is enabled, 16 KiB. Request bytes have already been placed at the descriptor's input pointer. The kernel writes any response at the descriptor's output pointer.

Return `0` for success with no response, a positive response byte count, or a negative error. The positive count must not exceed effective `out_cap`; otherwise the bridge returns `-5` without panicking. Negative values other than the two reserved control values pass through unchanged.

| Code | Host meaning |
| ---: | --- |
| `-3` | A pointer or length argument is negative. |
| `-4` | Request is larger than kernel input capacity. |
| `-5` | Kernel response count exceeds effective output capacity. |
| `-8` | Guest memory range is invalid. |
| `-9` | Guest `memory` is missing, or response copy access failed. |
| `-11` | Combined buffers exceed 256 KiB. Fuel exhaustion suspends execution rather than returning this code. |
| `-13` | No kernel is installed/ready. |
| `-14` | Kernel is panicked, or a kernel trap was caught. |
| `-15` | Kernel installation already began, succeeded, or panicked. |
| `-16` | Kernel installation failed, or no computer context is available to install. |

A kernel may define its own negative error values. The bridge does not reserve a namespace for them apart from `-100` and `-101`; OS ABIs should avoid ambiguity with host statuses.

## Blocking and wake

Returning `-100` (`SYSCALL_BLOCK`) parks the caller. Before returning it, the kernel should record whatever waiter state it needs. The runtime saves the syscall number, original request snapshot, guest output pointer, and output capacity. After wake, it calls `kernel_syscall` again from its entry point with the same values; it does not preserve the kernel's Wasm stack frame. Handlers therefore need to recheck state and tolerate retries or spurious wakeups.

The privileged `hal_wake(i64 pid) -> i32` import sets a sticky wake token for a live process, including a process still pending startup. A wake sent before the caller parks is consumed after the kernel returns `-100`. For an ordinary blocked process it makes the process schedulable; wake returns `0` when accepted and `-3` when the PID is not available. Wake is distinct from scheduler resumption for kernel ownership: callers waiting because another process owns the serialized kernel are resumed when ownership is released, without consuming a syscall wake token.

A syscall runs synchronously in its caller's execution context. The kernel is one passive Wasm instance per computer, with one owner at a time; the owner retains exclusivity across resumable fuel quanta. Other callers park in `KERNEL_WAIT` and resume when ownership is released. This is scheduler coordination, not a second kernel process or a worker thread waiting on a kernel mutex. Ordinary guest yield/fuel exhaustion can suspend execution while preserving the execution thread's continuation. In contrast, `-100` ends the kernel handler invocation and restarts it on wake.

## Process exit notifications

When the optional matching `kernel_process_exited(i64 pid, i32 reason) -> ()` export exists, the runtime queues process lifecycle notifications. Reasons are `0` for normal exit (including `-101`), `1` for trap or failed child instantiation, and `2` for kill. At most one notification runs per scheduler maintenance turn, only while the kernel is idle. It receives the remaining tick fuel allowance; actual instructions and HAL calls consume that allowance. Notifications do not have a calling guest process. A callback that traps, blocks, yields, or otherwise suspends panics the kernel. Notification queue capacity is bounded by the machine process limit, and spawn admission accounts for queued callbacks.

## Termination, kill, and panic

Returning `-101` (`SYSCALL_TERMINATE_CALLER`) ends the calling process as a normal exit. A guest `_start` that returns also exits normally. A requested kill takes effect at a safe execution boundary; if the process is in a kernel call, the kill is deferred until the call returns so the host does not discard kernel state mid-call. Exit hooks report normal exit as reason `0`, kill as `2`, and traps/start failures as `1`.

A trap in `kernel_syscall` or the exit callback panics the kernel. The active syscall receives `-14`; later calls also receive `-14`. The runtime emits a `kernel_panic` machine event and diagnostic, wakes blocked callers so they can observe failure, and queues termination of active unprivileged processes. Privileged processes remain available to report or respond. A malformed positive response length (`-5`) is not itself a panic. The panicked kernel cannot be replaced. Kernel state and wait queues are cleared on computer close/power-off; memory is released once active execution has stopped.

## Scope of the guest OS ABI

This page defines the host bridge available to guest kernels. OpenWACOS's
bootloader, kernel, OS, shell, and guest syscall API are maintained in a separate
repository. Consult that repository for guest behavior and development.
Built-in media integration and proposed hardware support for guest scheduling
are tracked in [implementation gaps](implementation-gaps.md).

Implementation: [ChicoryComputer.java](../src/main/java/org/mod/openwac/runtime/chicory/ChicoryComputer.java), [ChicoryHostImports.java](../src/main/java/org/mod/openwac/runtime/chicory/ChicoryHostImports.java).

