# Device interfaces

Each attached device has a UUID and an interface ID. Discover devices with
[`hal_device_count` and `hal_device_info`](hal.md#device-discovery-and-access),
then call one by UUID with `hal_device_call`. Interface IDs identify the
operation and byte-layout contract; they are not device addresses.

## Quick reference

All multibyte integers are little-endian. An operation receives an opaque
argument byte slice and a caller-sized response buffer. It returns a negative
status on failure or `0` on success, including operations that write response
bytes. Numeric values and strings are written into the response buffer; the
return value is never a byte count. This follows the status-only HAL contract.

| Interface ID | Device | Operation IDs |
| --- | --- | --- |
| `0x0100` | Persistent disk | `00` root query, `01` set root, `10` block size, `11` block count, `20` read block, `30` write block |
| `0x0200` | Host file importer | `00` file size, `01` read 256-byte block, `02` file name, `03` file-name length |
| `0x0300` | Serial monitor | `00` write, `01` read, `02` peek, `03` available bytes |
| `0x0400` | Tiny Text Display (TTD) | `00` geometry, `10` read cells, `20` write cells, `21` fill, `22` copy, `30` set cursor, `31` get cursor |
| `0x0500` | Peripheral port | `00` port information |
| `0x0600` | Keyboard | `00` info, `01` available events, `10` read events |

## Common operation rules

- The `operation` parameter is a 32-bit unsigned operation ID.
- Device-specific operation errors are the shared status values listed at the
  end of this document. HAL-level failures are described in [HAL](hal.md).
- `hal_device_call` is privileged. A device's interface contract does not
  grant guest code access on its own.
- Buffers are binary and may contain zero bytes. Text operations state when
  they expect ASCII; strings are not implicitly NUL-terminated.

## `0x0100` Persistent disk

This device exposes a fixed-size, writable block device. The default geometry
is 32 blocks of 256 bytes each. Simulation configuration can choose a
different nonzero block count and block size. Its backing file is stored under
the simulation runtime root at `data/disks/<uuid>`.

| Op | Name | Arguments | Response | Behavior |
| --- | --- | --- | --- | --- |
| `0x00` | `IS_ROOT` | None | 1 byte | `0` or `1` for the current root flag. |
| `0x01` | `SET_ROOT` | First byte: `0` or `1` | None | Changes the root flag. |
| `0x10` | `BLOCK_SIZE` | None | `u32` | Configured bytes per block. |
| `0x11` | `BLOCK_COUNT` | None | `u32` | Configured number of blocks. |
| `0x20` | `READ_BLOCK` | `block:u32` | One full block | Reads the selected block. |
| `0x30` | `WRITE_BLOCK` | `block:u32`, then one full block | None | Replaces the selected block. |

For `READ_BLOCK`, the response capacity must be at least the configured block
size. For `WRITE_BLOCK`, the argument consists of the four-byte block number
followed by exactly one full block. `IS_ROOT` writes its one-byte response;
`SET_ROOT` reads the first argument byte. Block indices outside
`0..BLOCK_COUNT` return `OUT_OF_BOUNDS`. Short block indices return
`ARGUMENT_OVERRUN`; an undersized write buffer returns `ARGUMENT_OVERRUN`,
while an oversized write buffer returns `INVALID_ARGUMENT`.

Example configuration:

```toml
[[computers.devices]]
type = "disk"
is_root = true
# uuid is optional; omitted UUIDs are generated.
block_count = 32
block_size = 256
remove_on_drop = false
```

`remove_on_drop = true` removes the backing file when the device is dropped.

## `0x0200` Host file importer

For an in-game read-and-chat diagnostic, use **BIOS EEPROM (File Importer
Test)**. See [the test procedure](../firmware/importer-test-v1.md).

This read-only device exposes a snapshot of a regular file selected on the
**player's client machine**, including on dedicated servers. Hold a File
Importer, right-click in the air, enter an absolute local path, and press
**Upload**. The client uploads only the ASCII basename and bytes; the absolute
path stays in the client's local settings. Guest code and server packets cannot
choose or change a client path or initiate a file read without pressing Upload.

Uploads are limited to 16 MiB and use acknowledged 16 KiB chunks. There is one
session per player, at most eight sessions per server, and a two-minute idle
timeout. The server requires the player to hold the same importer throughout
the transfer. Client disk reads and server disk writes run on separate bounded
workers, rather than game threads. An incomplete or failed upload leaves the
previous selection intact; publication of a complete snapshot is atomic.

Install the importer in a powered-off case after uploading, then power on.
The device captures its uploaded selection on its first HAL invocation on the
execution worker and reads it in 256-byte blocks; the final block can be
shorter. Every successful operation returns status `0`, including reads.
Determine the final read length from FILE_SIZE and the requested block offset.
An importer without uploaded content reports DEVICE_BROKEN.

| Op | Name | Arguments | Response | Behavior |
| --- | --- | --- | --- | --- |
| `0x00` | `FILE_SIZE` | None | `file_size:u64` | Size of the uploaded snapshot. |
| `0x01` | `READ_BLOCK` | `block:u32` | Up to 256 bytes | Reads the requested block; final block may be partial. |
| `0x02` | `FILE_NAME` | None | ASCII bytes | Configured basename, without terminator. |
| `0x03` | `FILE_NAME_LENGTH` | None | `u32` | Basename byte length. |

The `READ_BLOCK` response capacity must fit the bytes in that block. A block
outside the file returns `OUT_OF_BOUNDS`; a short block index returns
`ARGUMENT_OVERRUN`. If the source is truncated or unreadable after creation,
the operation returns `DEVICE_BROKEN`. Insufficient response space returns
`RESPONSE_OVERRUN`.

The server stores content-addressed snapshots and per-device selection records
under the world's `openwac/imports/` directory. Include this directory in world
backups. Uploaded content remains readable when its player disconnects and
across world saves and server restarts. The client does not need to stay online
for subsequent guest reads. The server never interprets an uploaded filename
as a host path and never executes uploaded bytes directly.

Recompiling a local file does not change an already imported snapshot. Power
off the case, take out the importer, right-click it and upload again, then
reinstall and boot. Existing running devices retain their original immutable
snapshot. Client path settings are saved locally in
`config/openwac-importer-paths.properties`, keyed by server and importer UUID;
opening the screen does not automatically re-upload a remembered path.

Server blob storage is bounded to 256 MiB per world. Identical content shares
one SHA-256 blob. Older blobs are retained so active snapshots stay valid;
there is currently no automatic garbage collection. Once that limit is reached,
new unique content is rejected. Do not remove blobs while a server is running.

## `0x0300` Serial monitor

The serial monitor connects guest operations to host console I/O. Each device
has a configured host-side `name`. Host input is queued as raw bytes in a FIFO
with a maximum capacity of 64 KiB. A host input submission that would exceed
the capacity is rejected whole; it does not append a partial submission.

| Op | Name | Arguments | Response | Behavior |
| --- | --- | --- | --- | --- |
| `0x00` | `WRITE` | Bytes to write | None | Writes bytes to host stdout and flushes. |
| `0x01` | `READ` | None | Up to response capacity bytes | Copies and consumes queued input. |
| `0x02` | `PEEK` | None | Up to response capacity bytes | Copies queued input without consuming it. |
| `0x03` | `AVAILABLE` | None | `u32` | Number of queued bytes. |

`READ` and `PEEK` require a nonempty response buffer, even when there is no
input; with capacity they return `0` when the FIFO is empty. `AVAILABLE`
requires at least four response bytes. `WRITE` accepts raw bytes and returns
`0` when host output succeeds. Input argument bytes to `READ`, `PEEK`, and
`AVAILABLE` are rejected. Output errors return `DEVICE_BROKEN`.

Example configuration:

```toml
[[computers.devices]]
type = "serial_monitor"
name = "terminal"
# uuid is optional; omitted UUIDs are generated.
```

The configured name is used by the host console to address the device; guests
still select it by UUID through the HAL.

## `0x0400` Tiny Text Display (TTD)

Fixed white-on-black text hardware: 60 columns by 30 rows, one CP437 byte per
cell. No colors, Unicode decoding, escape processing, wrapping, scrolling, or
newline handling are performed by the device. Each byte, including bytes 0
through 31, directly selects its graphical glyph from the supplied atlas.
The client renders 9 x 16-pixel glyphs, for a 540 x 480 logical screen.

All coordinates and dimensions below are little-endian `u32`, measured in
character cells. Origin is the top left; x increases rightward and y downward.
`rect` means `x:u32, y:u32, width:u32, height:u32` (16 bytes). Rectangles must
be nonempty and entirely inside the display. There is no implicit clipping.
Every successful operation returns status `0`, including queries and reads.

| Op | Name | Arguments | Response |
| --- | --- | --- | --- |
| `0x00` | `GEOMETRY` | None | `columns:u32, rows:u32` (8 bytes). |
| `0x10` | `READ_CELLS` | `rect` | `width * height` CP437 bytes, row-major. |
| `0x20` | `WRITE_CELLS` | `rect`, then exactly `width * height` CP437 bytes | None. |
| `0x21` | `FILL` | `rect`, then `character:u8` | None. |
| `0x22` | `COPY` | Source `rect`, then `destination_x:u32, destination_y:u32` | None. |
| `0x30` | `SET_CURSOR` | `x:u32, y:u32, visible:u8, blink:u8` (10 bytes) | None. |
| `0x31` | `GET_CURSOR` | None | Same 10-byte cursor record. |

Boolean bytes must be 0 or 1. The cursor is a white underline in the bottom
two glyph pixels, drawn over the cell without modifying its byte. Blink is
client-rendered, approximately half a second on and half a second off.
The initial buffer contains spaces (byte 32) and the cursor is hidden.

COPY has memmove semantics: overlapping source and destination rectangles work
in every direction. It does not erase the source. A WASM terminal driver can
scroll by copying rows upward and filling the last row with spaces.

Unknown operations return INVALID_OPERATION (`-2`); truncated headers or
payloads return ARGUMENT_OVERRUN (`-4`); extra payload bytes or invalid booleans
return INVALID_ARGUMENT (`-3`); insufficient response space returns
RESPONSE_OVERRUN (`-5`); invalid rectangles or cursor coordinates return
OUT_OF_BOUNDS (`-10`). Rejected calls leave state unchanged. Removed, unloaded,
or detached devices return DEVICE_NOT_AVAILABLE (`-7`) through their old
attachment. Guest memory ranges are independently validated by the HAL.

The NeoForge block is `openwac:ttd`. Place it face-adjacent to a computer case
with a Peripheral Port installed; the powered case discovers it through the
ordinary UUID device bus. Without a port the case neither claims nor enumerates
external devices. All six
neighbor directions are supported. The reusable Peripheral Wire item adds
explicit same-dimension logical links within 16 blocks, without line of sight.
See [wire setup](peripheral-wire.md). A display can
be attached to only one computer at a time; the first case to claim it owns it
until that case powers off, unloads, or loses its connection. Other connected powered
cases can claim it afterward. Hot attachment and removal update device
enumeration; there is currently no device-change event delivery.

Right-click a display with an empty hand for an enlarged, read-only view.
Keyboard input is provided by the separate `0x0600` peripheral; mouse input is
future hardware. The panel turns black, including its cursor, when the computer
powers off or its connection is lost; the retained cell contents become visible
again when supply returns. UUID, contents, cursor state and revision persist
with the block entity; normal survival drops carry that state into the item.
Clients receive initial snapshots with chunk data and batched dirty-row spans
afterward. Power transitions synchronize separately from cells and are also
included in initial chunk/snapshot synchronization. Supply is transient and
starts off after reload until a computer claims the display. Cursor blinking
itself does not generate server packets. The host can disable supply while
retaining an attachment for future power-budget policies; unpowered calls
return DEVICE_NOT_AVAILABLE (`-7`). No energy accounting is implemented yet.

## `0x0500` Peripheral port

The `openwac:peripheral_port` component enables the computer's external device
bus. Install it in any regular component slot with the case powered off. It
has a persistent UUID and one interface ID, like other internal components.
One port enables all six directly adjacent block positions plus up to 16 saved
logical-wire targets within 16 blocks. Additional ports do not increase these
limits. Wires never force chunks to load and do not require line of sight.

The port acts as a bus switch: external devices appear directly in
`hal_device_count` / `hal_device_info`, retaining their UUIDs and interface IDs.
Calls go directly to the external device UUID through `hal_device_call`. Guest
code does not enumerate subdevices through the port or wrap device requests
in port calls. Existing TTD drivers and BIOS modules need no ABI changes.
Internal BIOS, disk and importer components are not gated by the port.

| Op | Name | Arguments | Response |
| --- | --- | --- | --- |
| `0x00` | `INFO` | None | `adjacent_positions:u32` (currently 6), little-endian. |

INFO reports supported connection positions, not the current attached device
count; it describes the six automatic adjacent positions, not the separate
logical-wire limit. Use ordinary HAL discovery for attached devices. Success is status `0`;
unknown operations return `-2`, unexpected arguments `-3`, and response
capacities below four bytes `-5`. Adding/removing the port requires powering
off the case. Shutdown releases external-device ownership and invalidates
old attachments; the next boot rebuilds the device bus from installed hardware.

## `0x0600` Keyboard

The implemented keyboard exposes a bounded FIFO of physical down/up/repeat,
committed Unicode text, and reset events. INFO (`0x00`) writes five `u32`
fields: record size (24), capacity (256), queued count, dropped count, and
capture-active flag. AVAILABLE (`0x01`) writes the queued count. READ_EVENTS
(`0x10`) writes a `u32` count followed by that many 24-byte records; response
capacity must be at least 28 bytes, and all operations take empty arguments.
Success is status `0`. Record fields and validation are documented in the
[implemented keyboard ABI](device_interfaces.md#0x0600-keyboard).

The block requires a peripheral port and supports adjacency and logical wires.
Clicking it begins explicit client capture without requiring an enlarged
screen. Escape is guest input; release uses a visible button or another click
on the keyboard. TTD viewing is optional and does not change the keyboard's
address or input destination. See [keyboard usage](keyboard.md).

## Shared device status codes

| Code | Name | Meaning |
| ---: | --- | --- |
| `0` | `OK` | Operation succeeded; response data, if any, was written to the caller buffer. |
| `-1` | `UNKNOWN_ERROR` | Generic/unknown failure. |
| `-2` | `INVALID_OPERATION` | Operation ID is not supported by this interface. |
| `-3` | `INVALID_ARGUMENT` | Argument value or shape is invalid. |
| `-4` | `ARGUMENT_OVERRUN` | Argument buffer is shorter than required. |
| `-5` | `RESPONSE_OVERRUN` | Response buffer is too short. |
| `-6` | `DEVICE_BROKEN` | Device I/O failed or device state is unusable. |
| `-7` | `DEVICE_NOT_AVAILABLE` | Device UUID is not attached/available (HAL result). |
| `-8` | `POINTER_INVALID` | HAL guest pointer or range is invalid. |
| `-9` | `MEMORY_INVALID` | HAL guest memory is missing or inaccessible. |
| `-10` | `OUT_OF_BOUNDS` | Requested block/index is outside the device. |
| `-11` | `SYSCALL_TOO_EXPENSIVE` | HAL operation cannot fit the per-tick work budget. |
| `-12` | `PERMISSION_DENIED` | Caller lacks privilege. |
| `-13` | `KERNEL_NOT_INSTALLED` | No syscall kernel is installed. |
| `-14` | `KERNEL_PANICKED` | Installed syscall kernel has panicked. |
| `-15` | `KERNEL_ALREADY_INSTALLED` | Kernel is installed, installing, or unavailable after panic. |
| `-16` | `KERNEL_INVALID` | Kernel could not be compiled, instantiated, or initialized. |

The runtime defines these values in `device_status`. A specific device may
use only a subset. Device methods return their status directly through
`hal_device_call`; the shared numbers are not a separate transport layer.

## NeoForge persistence adaptation

Tier 1 disks use 512 blocks of 2048 bytes. Existing world images remain at
`openwac/disks/<uuid>.disk`, with their version-1 header unchanged. Root flags
are persisted in adjacent `<uuid>.disk.root` files and default to false when
absent. Include both files in backups. Each successful WRITE_BLOCK is forced
to storage; this interface has no separate FLUSH operation.

The original TOML examples describe the standalone backend, not a NeoForge
configuration loader. The serial-monitor contract is reserved here and is not
yet implemented in the mod.
