# System calls

System calls are the boundary between a guest process and its installed
kernel. This document describes two layers separately:

1. **Runtime contract:** implemented by the Java runtime in
   `runtime/chicory/ChicoryComputer.java`, its HAL bindings, and the core
   process scheduler. The original simulator ABI is retained, with instruction
   metering during execution and bounded callbacks as described below.
2. **OS syscall ABI:** syscall numbers and behavior implemented by the
   OpenWACOS Zig kernel. The simulator still treats request bytes as opaque;
   these rules are enforced by the kernel.

See [HAL](hal.md) for host imports available to privileged code. The host
does not assign meaning to OS syscall numbers; the kernel owns that ABI.

## Quick reference

| Concern | Current contract |
| --- | --- |
| Guest import | `sys.syscall(i32 number, i32 in_ptr, i32 in_len, i32 out_ptr, i32 out_cap) -> i32` |
| Kernel required exports | `memory`, `kernel_get_io_buffers`, `kernel_syscall` |
| Kernel optional exports | `kernel_init`, `kernel_prepare_io_buffers`, `kernel_process_exited` |
| Request/response | Bytes are copied between guest memory and kernel memory. The host does not interpret request bytes or syscall numbers. |
| Combined buffer limit | `in_len + out_cap` must be at most 256 KiB. |
| Blocking | Kernel returns `-100`; host parks the caller and reruns the same number and saved request after a wake. |
| Termination | Kernel returns `-101`; host exits the caller normally. |
| Fuel | Copy work is charged before dispatch. Kernel instructions and HAL work are charged to the caller as they execute. |
| Privilege | Bit 0 of `flags` tells the kernel whether the caller is privileged. Kernel policy must enforce permissions. |

The remainder expands both layers. The current kernel implementation lives in
[`_llm/kernel/src`](../../_llm/kernel/src) and uses the TBDF API in
[`_llm/tbdf`](../../_llm/tbdf). The privileged Rust OS supervisor lives in
[`_llm/os`](../../_llm/os) and provides the device refresh, disk mount, and
keyboard-event notification work described here.

## 1. Runtime contract

### 1.1 Call path

The kernel is a passive Wasm instance, not a scheduled process. A privileged
guest installs one kernel per computer with
[`hal_install_kernel`](hal.md#hal_install_kernel). After installation, the
host calls `kernel_syscall` when a process invokes `sys.syscall`, and calls
`kernel_process_exited` when it has a queued process-exit notification.

Each process and the kernel have separate Wasm stores and memories. Guest
memory is never exposed directly to the kernel: the host snapshots the
request bytes, writes them into the kernel input buffer, then copies any
response bytes back to the guest. The kernel's state persists between calls.

The kernel has no independent scheduler turn. A syscall runs as part of the
calling process's host call, so the process cannot continue until the kernel
returns, blocks, or terminates it. A kernel may call privileged HAL imports;
those imports can wait for CPU budget or for a tick, so a syscall can remain
pending while such a host call waits. Kernel code should keep ordinary
dispatch work bounded and must not spin indefinitely.

### 1.2 Guest import: `sys.syscall`

```wat
(import "sys" "syscall"
  (func (param i32 i32 i32 i32 i32) (result i32)))
```

Arguments, in order:

| Argument | Meaning |
| --- | --- |
| `number` | OS-defined syscall number. Passed through unchanged by the host. |
| `in_ptr` | Guest-memory byte offset of the request. |
| `in_len` | Request length in bytes. |
| `out_ptr` | Guest-memory byte offset for response bytes. |
| `out_cap` | Maximum response length the guest accepts. |

The guest module must export a linear memory named `memory`. All pointer and
length arguments except `number` must be nonnegative, and both full memory
ranges must fit. The host accepts `in_len + out_cap` up to 256 KiB. It does
not validate the syscall number, request format, response format, or caller's
permission to perform a particular operation.

#### Result values

The result follows a byte-buffer convention:

- A negative value indicates an error. It may be a host status or a
  kernel-defined status; meanings can overlap, so an OS ABI should reserve
  distinct ranges or document each code by context.
- `0` means success with no response bytes.
- A positive value is the response byte count. The kernel must write that
  many bytes to its output buffer, and the count must fit the effective output
  capacity.

The host copies only the returned positive byte count. It does not clear the
unused part of the guest output buffer. On a negative result, no response
bytes are copied.

#### Host and kernel-bridge errors

| Code | Source | Meaning |
| ---: | --- | --- |
| `-3` | Host | A pointer or length argument was negative. |
| `-4` | Kernel bridge | Request exceeds the input capacity declared by `kernel_get_io_buffers`. |
| `-5` | Kernel bridge | Kernel returned a positive response length larger than the effective output capacity. |
| `-8` | Host | A guest pointer/range is outside the guest memory. |
| `-9` | Host | Guest `memory` is missing, or response memory access failed. |
| `-11` | Host | Combined buffers exceed 256 KiB, or the copy-work charge cannot fit in a tick. |
| `-13` | Kernel bridge | No kernel is installed. |
| `-14` | Kernel bridge | The kernel is marked panicked. |

The kernel bridge returns a kernel's other negative values unchanged. A
trapped kernel call is different: the host marks the kernel dead and returns
`-14` to the caller.

#### Copy cost and kernel fuel

The host charges buffer-copy work before dispatch:

```text
copy_cost = 8 + ceil((in_len + out_cap) / 256)
```

The combined buffer limit is 256 KiB. Copy charges are paid before dispatch
through the same resumable fuel meter as guest execution. A charge can span
quanta and ticks without repeating the copy or dispatch.

Kernel instructions and HAL host work are charged to the calling process as
they execute. Exhaustion parks that process's continuation, preserving exclusive
kernel ownership until the call finishes. Other callers wait in the scheduler
for ownership; no worker thread waits on a kernel mutex. An infinite kernel
loop is therefore preemptible and consumes only the computer's tick allowance.
Stack bounds, cancellation checks, and the execution watchdog also apply.

### 1.3 Kernel module ABI

The host compiles and instantiates the kernel in a separate Wasm store. It
requires an exported memory named `memory` and these typed exports:

| Export | Wasm type | Required | Purpose |
| --- | --- | :---: | --- |
| `memory` | linear memory | Yes | Persistent kernel state and the I/O buffers. |
| `kernel_get_io_buffers` | `() -> i32` | Yes | Returns a pointer to the 16-byte I/O descriptor. |
| `kernel_syscall` | `(i64, i32, i32, i32, i32) -> i32` | Yes | Dispatches one request. |
| `kernel_init` | `() -> ()` | No | Initializes kernel state once during installation. |
| `kernel_prepare_io_buffers` | `(i32, i32) -> i32` | No | Grows input/output buffers to the requested byte capacities; returns `0` on success. |
| `kernel_process_exited` | `(i64, i32) -> ()` | No | Receives process-exit notifications. |

The kernel can import functions from `hal`. It runs with privileged HAL access,
but has no `sys` import and cannot install another kernel from its own kernel
store. See [HAL imports](hal.md#quick-reference) for their signatures and
behavior.

#### `kernel_get_io_buffers` descriptor

`kernel_get_io_buffers` returns the byte offset of four consecutive little-endian `u32`
values in kernel memory:

| Descriptor offset | Field | Meaning |
| ---: | --- | --- |
| 0 | `input_ptr` | Start of the request buffer. |
| 4 | `input_capacity` | Maximum request length accepted by this kernel. |
| 8 | `output_ptr` | Start of the response buffer. |
| 12 | `output_capacity` | Maximum response length this kernel can produce. |

The descriptor and both full buffer ranges must fit in the exported memory or
installation fails with `-16`. The host checks the ranges, but does not reject
overlap between the input and output buffers; keep them disjoint so writing a
response cannot overwrite a request still in use. The host also does not
require capacities to be equal. A request larger than `input_capacity`
returns `-4` without calling `kernel_syscall`. The `out_cap` passed to the
kernel is `min(guest out_cap, output_capacity)`.

For kernels exporting `kernel_prepare_io_buffers(i32, i32) -> i32`, the host
prepares buffers before syscall dispatch only when a request exceeds a cached
capacity. Input grows up to 256 KiB and output up to 16 KiB. The host tracks
the largest successfully requested capacity for each buffer, starting with
the installed descriptor's capacities, clamped to those limits. Both sizes
passed to preparation are monotonic. Once both reach their upper bounds,
preparation is never needed again.

After successful preparation, the host queries and validates the descriptor
again because buffer addresses can change. Buffers must not shrink and must
meet the requested sizes. A negative preparation status is returned to the
guest without dispatching the syscall or advancing the cached capacities;
a trap, invalid descriptor, insufficient capacity, shrinking buffer, or
positive preparation status panics the kernel. Preparation runs under the
same serialized kernel ownership and caller fuel budget as syscall dispatch.

File reads can return up to 16 KiB per call and be continued with later calls.
Kernels without the preparation export retain their descriptor-defined fixed
buffer capacities.

#### `kernel_syscall`

```text
kernel_syscall(
    pid: i64,
    flags: i32,
    number: i32,
    in_len: i32,
    out_cap: i32
) -> i32
```

At entry, the request occupies `input_ptr .. input_ptr + in_len`. The kernel
may write at most `out_cap` bytes beginning at `output_ptr`. The arguments
mean:

| Argument | Meaning |
| --- | --- |
| `pid` | Process ID of the caller. |
| `flags` | Caller flags. Bit 0 (`1`) means privileged; all other bits are currently unset. |
| `number` | The guest's syscall number, passed through unchanged. |
| `in_len` | Request bytes available in the input buffer. |
| `out_cap` | Effective output capacity after the host clamps to the descriptor. |

Return `0` for success without data, a positive response byte count, or a
negative error. The two reserved results below are interpreted by the host
before any result reaches the guest:

| Result | Name | Host behavior |
| ---: | --- | --- |
| `-100` | `SYSCALL_BLOCK` | Park the guest caller until wake, then rerun this call. |
| `-101` | `SYSCALL_TERMINATE_CALLER` | End the guest caller as a normal exit. |

Do not use `-100` or `-101` as ordinary kernel error codes. The host treats
any other negative result as a completed syscall and passes it through. If a
positive result exceeds `out_cap`, the host returns `-5`; this is an ABI
error, not a kernel panic.

#### Kernel initialization

Installation compiles the module on a worker thread, waits until the next
simulation tick, instantiates it, and validates the required exports. It then
invokes `kernel_init` if an export with the matching `() -> ()` type exists.
Finally, the host queries `kernel_get_io_buffers` and validates the initial
descriptor and buffer ranges before marking the kernel ready. It detects
the optional `kernel_prepare_io_buffers(i32, i32) -> i32` export during
installation, but calls it on demand during syscalls as described above.

A trap or any required-export/descriptor error
fails installation with `-16`. An absent or wrongly typed optional initializer
or preparation export is treated as absent by the current host. Successful
initialization runs once; a later install is rejected with `-15`.

Descriptor-query and initialization instructions and HAL calls are charged
to the installer as they execute, preserving the continuation across fuel
boundaries. Kernel memory uses the computer and server page budgets. A failed
installation frees its memory and allows retry; a successful or panicked
kernel cannot be replaced. Compilation uses a bounded executor; installation
waits at least one simulation tick and longer if compilation is still pending.

#### Process-exit hook

If a matching `kernel_process_exited(i64, i32)` export exists, the host calls
it for process exits with:

| `reason` | Meaning |
| ---: | --- |
| `0` | Normal exit, including `SYSCALL_TERMINATE_CALLER`. |
| `1` | Trap or failure to instantiate a requested child process. |
| `2` | Killed. |

The hook has no calling process. At most one hook runs at the start of a
tick, when the kernel is idle, using the whole configured tick allowance.
Actual instruction and HAL charges consume that allowance, and other processes
can use any remaining fuel. A trap, yield, blocking call, or fuel exhaustion
panics the kernel; hooks cannot suspend. Busy kernels defer notifications.
The notification queue is bounded by the computer's process limit; spawn
admission pauses when its available notification capacity is exhausted.

The optional export is looked up by type; a missing or wrongly typed hook is
treated as absent by the current host. If the hook traps or suspends, the
kernel is marked panicked.

### 1.4 Blocking and restart behavior

To block, a syscall handler records the state it needs, then returns
`SYSCALL_BLOCK`. The host keeps the original request bytes, number, output
pointer, and output capacity while the guest is parked. Once
[`hal_wake`](hal.md#hal_wake) wakes that process ID, the host calls
`kernel_syscall` again with the same inputs. The kernel itself is not
suspended at the point where it returned `-100`; it starts the handler again
from its entry point.

This restart model has several consequences:

1. Check current state on every invocation. Do not assume the call is a
   continuation of a previous stack frame.
2. Register the caller as a waiter before returning `-100`, so a wake cannot
   race between the state check and waiter registration.
3. Make retries safe. A wake may be spurious, or another event may consume or
   change the resource before the retry runs.
4. Do not rely on changes to the guest request during the wait. The host
   passes the saved request snapshot on every retry.

Wake tokens are sticky: a wake arriving before the host begins waiting is
consumed immediately after the kernel returns `-100`. A process woken during
a tick may be scheduled again in that tick if CPU budget remains.

### 1.5 Termination, kills, and kernel failure

- If a guest `_start` returns, the process exits normally.
- Returning `SYSCALL_TERMINATE_CALLER` also ends the process normally. Its
  exit-hook reason is `0`.
- A process kill requested while it is inside a kernel call is deferred until
  the call returns. The host does not drop a kernel store in the middle of a
  call.
- A trap in `kernel_syscall` or `kernel_process_exited` marks the kernel
  panicked. The active syscall receives `-14`; subsequent syscalls receive
  `-14`. The simulator reports a kernel-panic event and kills active
  unprivileged processes. Privileged processes remain running so they can
  report or handle the failure.
- A panicked kernel cannot be reinstalled. `hal_install_kernel` returns
  `-15` after a panic.

A positive result greater than `out_cap` returns `-5` and does not panic the
kernel. A trap or an internal kernel memory failure does panic it. Panic is recorded
as a bounded `kernel_panic` machine event and machine diagnostic. Waiting
callers are resumed to observe failure; unprivileged processes are queued for
termination. Privileged processes remain available to report the panic.

Kernel state, pending compilation, and wait queues are discarded on power-off
or cold boot. Kernel pages are released once active execution has stopped.
The kernel has no independent process slot or scheduler turn; exit hooks are
bounded scheduler maintenance.

## 2. OS syscall ABI

The host does not implement these numbers; the kernel owns them. Integer
fields are little-endian. Unless stated otherwise, malformed requests return
`-3`, undersized response capacity returns `-5`, and filesystem errors map to
the OS error values in section 2.4.

### 2.1 Process calls

| # | Name | Request | Successful result |
| ---: | --- | --- | --- |
| 1 | `EXIT` | `code:i32` | No return; terminates the caller normally. |
| 2 | `ARG_LENGTH` | Empty | `length:u32` (4 response bytes). |
| 3 | `READ_ARGS` | `offset:u32` | Argument bytes starting at `offset`, up to output capacity. |
| 4 | `SPAWN` | `args_len:u32`, `args:[u8; args_len]`, remaining bytes are child Wasm | `pid:u64` (8 response bytes). |
| 5 | `WAIT` | `pid:u64` | `exit_code:i32` (4 response bytes); blocks until child exits. |
| 6 | `WRITE` | Raw bytes | `-2` (use `TXT_WRITE`). |
| 23 | `SPAWN_PRIVILEGED` | Same as `SPAWN` | `pid:u64` (8 response bytes); privileged caller only. |

Unknown syscall numbers return `-2`. The guest supplies opaque argument
bytes at spawn time; the kernel stores them for the child without imposing an
encoding. `ARG_LENGTH` returns their original byte length. `READ_ARGS` copies
from the requested offset, limited by both remaining argument bytes and
response capacity. An offset at or beyond the end returns zero bytes.
Processes not started through `SPAWN` have an empty argument buffer.

`WRITE` currently returns `-2`; use the text-output calls in section 2.2. It is
separate from the debug-only `hal_DEBUG_PRINT` import.

`SPAWN` asks the host to create an unprivileged child using
[`hal_spawn_process`](hal.md#hal_spawn_process) and is available to either
privilege tier. `SPAWN_PRIVILEGED` requires a privileged caller and uses
[`hal_spawn_process_privileged`](hal.md#hal_spawn_process-and-hal_spawn_process_privileged).
Both store the supplied argument bytes for `ARG_LENGTH` and `READ_ARGS`. The
returned PID means the host accepted the
request, not that the module successfully started. A spawn whose Wasm fails to
compile or instantiate later arrives at `kernel_process_exited` with reason
`1`.

`WAIT` is limited to children created by that caller. It returns the child's
exit code and releases the child's record. Exit codes are the value passed to
`EXIT`, `-1` for a trap or spawn failure, and `-2` for a kill. When a parent
exits, live children are detached and already-exited child records are
released; detached processes are released when they exit. The kernel
holds at most 32 children per parent and limits arguments to 4096 bytes. The
module bytes plus request fields must also fit the host's 256 KiB combined
syscall-buffer limit.

The child receives its allocated PID immediately. The host compiles
the child asynchronously, and it starts on a later tick; see
[`hal_spawn_process`](hal.md#hal_spawn_process). The exact tick schedule is
a host detail and should not be used as a synchronization mechanism.

### 2.2 Device table and file calls

The following calls handle device refresh and file access:

| # | Name | Request | Successful result |
| ---: | --- | --- | --- |
| 7 | `UPDATE_DEVICE_TABLE` | `count:u32`, then `count` records of `interface_id:u32` + `uuid:[u8;16]` | No response. |
| 8 | `FILE_OPEN` | `flags:u32`, then path bytes | `handle:u32` (4 response bytes). |
| 9 | `FILE_READ` | `handle:u32`, `length:u32` | Up to `length` bytes from current offset. |
| 10 | `FILE_WRITE` | `handle:u32`, then data bytes | No response. |
| 11 | `FILE_SEEK` | `handle:u32`, `whence:u32`, `offset:i32` | New `position:u32` (4 response bytes). |
| 12 | `FILE_CREATE` | `flags:u32`, then path bytes | `handle:u32`; no response for directory creation. |
| 13 | `FILE_CLOSE` | `handle:u32` | No response. |
| 14 | `FS_MOUNT` | `uuid:[u8;16]`, `flags:u32`, then mountpoint path | No response. |
| 15 | `FS_UNMOUNT` | Mountpoint path | No response. |
| 16 | `FS_CHILDREN` | `ordinal:u32`, then directory path | One entry record; see below. |
| 17 | `FS_SEEK` | Path | One entry record; see below. |
| 18 | `TXT_OPEN` | TTD UUID `[u8;16]`, keyboard UUID `[u8;16]` | `handle:u32` (4 response bytes). |
| 19 | `TXT_WRITE` | `handle:u32`, then UTF-8 text bytes | No response; renders to the TTD. |
| 20 | `TXT_READ` | `handle:u32`, `length:u32` | UTF-8 text/control bytes from keyboard events; may block if no bytes are available. |
| 21 | `UPDATE_IO_AVAILABLE` | Keyboard UUID | No response; wakes readers waiting on that device. |
| 22 | `PROCESS_KILL` | `pid:u64` | No response. |
| 24 | `UPDATE_FILESYSTEM` | Empty | No response; drops mounted TBDF metadata for reload. |
| 25 | `FILE_DELETE` | Path | No response; deletes a regular file. Directories and mounted roots are rejected. |
| 26 | `FILE_IMPORT` | `offset:u32`, then destination path | `next_offset:u32, size:u32`; copies at most one 256-byte importer block per call. |

`FS_MOUNT` is privileged. Its flags currently allow only bit 0, which mounts
the disk read-only. The UUID must identify an attached disk; its target path
must resolve to an existing non-root directory that is not already a mount
point. A disk cannot be mounted twice at once, including mounting the root disk
again. `FS_UNMOUNT` is
privileged and rejects mounts with nested mounts or open file handles.
Unmounting the root disk is not supported.

`FS_CHILDREN` returns one direct child selected by zero-based ordinal. Both it
and `FS_SEEK` encode the response as `id:u16`, `parent:u16`,
`is_directory:u8`, `readonly:u8`, `size:u32`, then name bytes. The positive
response length is 10 plus the name length. `FS_SEEK` resolves a full path. Paths use the
NUL-separated component format described below. Mount traversal switches to
the mounted volume's root, hides the underlying mountpoint contents, and
supports `..` across a mount boundary.

`FILE_DELETE` accepts a nonempty path to a regular file. It rejects directory
entries, mounted roots, and files that still have open handles (`-31`).
`FILE_IMPORT` accepts a block offset and destination path; the kernel finds an
attached file importer whose configured basename matches the final destination
component. The offset must be a multiple of 256. At offset zero it creates the
destination; subsequent calls resume only when the current file size equals
the offset. Each call copies at most one 256-byte block and returns the next
offset and total source size, so callers can yield between blocks. The
destination must not already exist. Callers should remove an incomplete
destination if an import fails.

`TXT_OPEN` binds a TTD UUID and a keyboard UUID. `TXT_WRITE` accepts UTF-8 and
renders through the kernel's replaceable TTD display backend, mapping
supported glyphs to CP437; unsupported or malformed characters display as
`?`. `TXT_READ` converts keyboard TEXT events to UTF-8 and Enter, Backspace,
Tab, and Escape key-down events to terminal control bytes; Backspace key-repeat
events repeat backspace. A zero-length read returns immediately. For a nonzero
requested length, it blocks with `-100` when it has produced no bytes and no
keyboard events are queued; the kernel records the process and keyboard UUID
first. If it has already produced bytes, it returns them immediately instead.
The OS calls
`UPDATE_IO_AVAILABLE` after observing queued keyboard events, which wakes
readers for that UUID. A restarted read checks the queue again, so spurious
notifications are safe. `WAIT` is the other blocking syscall. Ordinary file
and disk operations do not return `-100`.

`PROCESS_KILL` lets a process kill its own child; a privileged caller may kill
any tracked process. `UPDATE_FILESYSTEM` is privileged. It drops TBDF resident
metadata and closes file handles, so the next file operation reloads disk
metadata. Mount relationships and text handles remain. `FILE_CLOSE` can close
either a file or text handle; closing one cancels a pending keyboard-read
registration for that handle. File payload blocks are streamed through the
disk device. Device discovery is empty at kernel initialization; the OS must send
`UPDATE_DEVICE_TABLE` before filesystem calls can succeed.

`UPDATE_DEVICE_TABLE` is privileged and accepts `count:u32` followed by
`count` 20-byte interface-ID/UUID records matching `hal_device_info`. The
kernel permits at most 32 devices and validates the supplied set against the
currently attached HAL devices before updating its table. The OS should call
it at startup and whenever devices change.

The kernel uses the root disk indicated by `IS_ROOT` and mounts its TBDF
volume on first file access. A device-table update that removes a mounted disk
unmounts it and closes affected file handles. Removing a TTD or keyboard
invalidates text handles using that device and wakes any matching pending
keyboard reads. Before a usable device table is provided, file operations
return `-38` (`OW_ERR_NO_DEVICE`).

### 2.3 Paths and handles

#### Path format

A path is a sequence of NUL-terminated components, for example
`dir\0subdir\0file\0`. It has no slash separator and is rooted at the
filesystem root. An empty path names the root. Component bytes are printable
ASCII; a single component can contain at most 255 name bytes because its NUL
terminator counts toward the 256-byte path limit. `.` and `..` are supported.
Empty components are invalid. Every nonempty path must end in NUL. This
encoding cannot represent a NUL inside a name.

#### Handles and file behavior

Handles are small integers scoped to the owning process, with an independent
offset starting at zero. The kernel closes a process's
handles on exit, trap, or kill through `kernel_process_exited`. A process may
hold at most 16 handles.

`OW_FILE_READ` (`1`) and `OW_FILE_WRITE` (`2`) select access; at least one is
required. `FILE_CREATE` also accepts `OW_FILE_DIRECTORY` (`4`), which creates
a directory and returns no handle. At least one of `OW_FILE_READ` or
`OW_FILE_WRITE` must still be set for directory creation (for example, flags
`5` creates a directory). Outcomes:

- `FILE_OPEN` returns `-30` if the file does not exist.
- `FILE_CREATE` returns `-31` if the entry already exists, or `-30` if its
  parent directory does not exist. A file create returns a handle opened
  with the requested access.
- `FILE_READ` reads up to `length`, response capacity, and bytes remaining at
  the current offset. It advances by the number of bytes returned. EOF is a
  successful zero-byte response.
- `FILE_WRITE` writes the full request at the current offset and advances by
  the request length. A write past EOF extends the file and zero-fills the
  gap, following the TBDF write behavior. It returns `0`, since syscall
  positive results represent response bytes. A device failure may leave a
  partial disk update, as TBDFv1 does not provide transactions.
- `FILE_SEEK` sets the offset relative to `OW_SEEK_SET` (`0`), `OW_SEEK_CUR`
  (`1`), or `OW_SEEK_END` (`2`). Negative resulting positions return `-3`;
  seeking past EOF is allowed. Offsets and file sizes are 32-bit in this
  kernel.
- `FILE_CLOSE` releases a handle before process exit. Closing an invalid handle
  returns `-35`.

The kernel keeps mounts only in memory; the OS recreates them after startup.

### 2.4 OS syscall error values

These values are part of the OS/file ABI, not statuses returned by
the Rust `sys` host import itself:

| Code | Name | Meaning |
| ---: | --- | --- |
| `-2` | `OW_ERR_UNKNOWN` | Unknown syscall number or unsupported `WRITE`. |
| `-3` | `OW_ERR_INVALID_ARGUMENT` | Malformed request, invalid flags/path/offset, or invalid syscall argument. |
| `-5` | `OW_ERR_RESPONSE_OVERRUN` | Response does not fit the caller's output capacity. |
| `-12` | `OW_ERR_PERMISSION_DENIED` | Caller lacks required privilege, ownership, or handle access. |
| `-20` | `OW_ERR_NOT_CHILD` | `WAIT` target is not the caller's child, or `PROCESS_KILL` target is untracked. |
| `-21` | `OW_ERR_SPAWN_FAILED` | Host refused a spawn or process-control request. |
| `-22` | `OW_ERR_LIMIT_REACHED` | Process, child, waiter, mount, pending-I/O, or device limit reached. |
| `-30` | `OW_ERR_NOT_FOUND` | File, parent directory, or mountpoint does not exist. |
| `-31` | `OW_ERR_EXISTS` | Entry or disk is already mounted, unmount has child mounts/open files, or `FILE_DELETE` targets an open file. |
| `-32` | `OW_ERR_NO_SPACE` | Filesystem or kernel argument storage has no space. |
| `-33` | `OW_ERR_IS_DIRECTORY` | A directory was opened as a file. |
| `-34` | `OW_ERR_NOT_DIRECTORY` | A path component is not a directory. |
| `-35` | `OW_ERR_BAD_HANDLE` | Handle is not open for this caller. |
| `-36` | `OW_ERR_TOO_MANY_OPEN` | Per-process handle limit reached. |
| `-37` | `OW_ERR_IO` | Device I/O failed or filesystem is corrupt. |
| `-38` | `OW_ERR_NO_DEVICE` | No usable disk/device table is available. |

The kernel centralizes mapping from TBDF status codes to these OS errors.

## 3. Kernel limits and implementation notes

The kernel has a 128-record process table, 32 tracked children per parent,
32 waiters per child, 4096 bytes of arguments per child, 16 open handles per
process, 32 devices, eight mounted volumes, and 64 pending keyboard reads. The
aggregate backing-disk capacity, including the root disk, is limited to 8 MiB.
This is a disk-geometry limit; TBDF streams file payloads instead of retaining
whole disk images. Supported disk block sizes range from 32 to 262140 bytes.
These limits are fixed at compile time. Mount records and process/handle
metadata live only in kernel memory and disappear when the kernel is reinstalled.

The simulator remains responsible for buffer validation, process scheduling,
process creation/kill queues, kernel restart behavior, and accounting. The
kernel is responsible for syscall policy, ownership, file handles, process
records, mount namespace traversal, and signaling blocked callers with
`hal_wake`.
