# Implemented device interfaces

This document describes the current Java implementations in OpenWAC. The
broader backend contract is in [true_device_interfaces.md](true_device_interfaces.md);
reserved interfaces and functions there are not necessarily implemented here.
Filesystem and terminal software belong in the WASM environment.

## Interface registry

Each attached device has one permanent UUID and exactly one interface ID.
Operation numbers are local to that interface. All implemented descriptors
currently declare version 1.0; HAL discovery exposes the numeric interface ID
and UUID, not those version fields or the Java interface name.

| Interface ID | Interface name | Implementation | Operations |
| --- | --- | --- | --- |
| `0x0001` | `openwac:eeprom` | `BiosEeprom` | `00` INFO, `01` READ |
| `0x0100` | `openwac:disk` | `DiskDevice` | `00` IS_ROOT, `01` SET_ROOT, `10` BLOCK_SIZE, `11` BLOCK_COUNT, `20` READ_BLOCK, `30` WRITE_BLOCK |
| `0x0200` | `openwac:file_importer` | `FileImporterDevice` | `00` FILE_SIZE, `01` READ_BLOCK, `02` FILE_NAME, `03` FILE_NAME_LENGTH |
| `0x0400` | `openwac:ttd` | `TtdDevice` attachment | `00` GEOMETRY, `10` READ_CELLS, `20` WRITE_CELLS, `21` FILL, `22` COPY, `30` SET_CURSOR, `31` GET_CURSOR |
| `0x0500` | `openwac:peripheral_port` | `PeripheralPortDevice` | `00` INFO |
| `0x0600` | `openwac:keyboard` | `KeyboardDevice` attachment | `00` INFO, `01` AVAILABLE, `10` READ_EVENTS |

`0x0300` (serial monitor) is reserved by the backend document and is **not
implemented** in the Java mod. The former provisional disk interface ID `2`
and its INFO/multi-block READ/WRITE/FLUSH operations are no longer supported.

## HAL transport and byte layouts

Only privileged processes can link the discovery and device-call imports from
WASM module `hal`. See [HAL](hal.md) for the rest of the host ABI.

- `hal_device_count() -> i32` returns the attached-device count.
- `hal_device_info(index, output_ptr, output_capacity) -> i32` writes the
  following 20-byte record and returns status `0`:

  | Offset | Size | Field |
  | ---: | ---: | --- |
  | 0 | 4 | Numeric interface ID, little-endian `u32` |
  | 4 | 16 | UUID: most-significant 64 bits followed by least-significant 64 bits, both big-endian |

- `hal_device_call(uuid_ptr, operation, arguments_ptr, arguments_len,
  response_ptr, response_capacity) -> i32` resolves the UUID and invokes that
  device's `device_call(DeviceCall)` implementation. There is no interface
  selector in the request payload.

Enumeration uses Java UUID natural ordering; indices are temporary, not device
identities. Retain the UUID for calls. The bus rejects duplicate UUIDs and
supports at most 64 attached devices.

All multibyte **device payload** integers below are little-endian, with no
implicit alignment or padding. `u8`, `u32`, and `u64` occupy 1, 4, and 8 bytes.
Strings have no implicit NUL terminator. Operation IDs are 32-bit bit patterns;
unsupported IDs return the device-specific error described below.

**A successful device call always returns status `0`, even when it writes
response bytes.** Negative results are errors. Neither data values nor byte
counts are returned through the status. Determine the response length from the
operation contract, or query the relevant length first. Only the actual response
bytes are copied into guest memory; unused response capacity is not cleared.

Pointers are byte offsets in the caller's exported linear memory named
`memory`. The host validates the full UUID, argument, and response ranges
before invoking a device, including zero-length ranges. Argument length plus
response capacity must not exceed **256 KiB**. This includes unused response
capacity. The host copies arguments before dispatch and charges fuel for host
work; it can suspend while paying that charge. Byte buffers do not expose
Java references or host filesystem paths to guests.

## `0x0001`: BIOS EEPROM

Implementation: `core/device/eeprom/BiosEeprom.java`.

| Op | Name | Arguments | Response |
| --- | --- | --- | --- |
| `0x00` | INFO | Empty | `image_length:u32`, `maximum_length:u32`, `read_only:u32` (12 bytes). |
| `0x01` | READ | `offset:u32`, `length:u32` (8 bytes) | Exactly `length` firmware bytes. |

The maximum firmware size is **1 MiB**; `read_only` is `1`. READ requires
`offset + length <= image_length` and enough response capacity. Zero-length
reads are valid, including at the end of the image. Although encoded as
32-bit fields, offset and length are checked as nonnegative Java integers;
values with the high bit set are rejected.

EEPROM retains its original validation convention: unknown operations,
unexpected argument lengths, invalid offsets/lengths, **and undersized response
buffers** return `INVALID_ARGUMENT` (`-3`). It does not use `-2`, `-4`, or `-5`
for those conditions. Missing or invalid firmware reported by the repository
returns `DEVICE_BROKEN` (`-6`).

Firmware is loaded lazily and cached for the lifetime of the device instance.
Host-side programming changes an item's firmware reference while preserving
its UUID. Built-in images are bundled references; custom images are deduplicated
SHA-256 blobs under the world's `openwac/firmware` directory. There is no guest
EEPROM-write operation. The case requires exactly one EEPROM in its dedicated
BIOS slot; that slot is separate from the nine regular component slots.

## `0x0100`: Persistent disk

Implementation: `core/device/storage/DiskDevice.java`.

The Tier 1 item exposes **512 blocks of 2048 bytes**, totaling **1 MiB** of
guest-addressable data. New media contains zeroes. Block numbers are zero-based;
the last valid block is `511`. The Java implementation provides raw blocks,
not files, paths, partitions, or permissions.

| Op | Name | Arguments | Response |
| --- | --- | --- | --- |
| `0x00` | IS_ROOT | Empty | `is_root:u8`, either `0` or `1`. |
| `0x01` | SET_ROOT | First byte: `is_root:u8`, either `0` or `1` | Empty. |
| `0x10` | BLOCK_SIZE | Empty | `block_size:u32` (4 bytes). |
| `0x11` | BLOCK_COUNT | Empty | `block_count:u32` (4 bytes). |
| `0x20` | READ_BLOCK | `block:u32` (exactly 4 bytes) | Exactly one full block. |
| `0x30` | WRITE_BLOCK | `block:u32`, followed by exactly one full block | Empty. |

SET_ROOT requires at least one argument byte, validates the first byte, and
**ignores trailing argument bytes**. The root flag defaults to false, is
persistent, and is independent of disk contents. It does not enforce a unique
root disk or change the host's EEPROM boot procedure.

READ_BLOCK requires response capacity of at least BLOCK_SIZE. WRITE_BLOCK on
Tier 1 requires exactly **2052 argument bytes**. There are no multi-block
transfers or FLUSH opcode; each successful write is flushed before success is
returned. Query BLOCK_SIZE/BLOCK_COUNT rather than assuming item geometry.

Validation and errors:

- Unsupported operation: `-2`.
- Unexpected arguments on IS_ROOT or geometry queries: `-3`.
- Missing SET_ROOT byte or fewer than four block-index bytes: `-4`.
- Invalid SET_ROOT flag: `-3`.
- Unsigned block index outside `0 <= block < block_count`: `-10`.
- Extra READ_BLOCK arguments: `-3`; short read response: `-5`.
- Short WRITE_BLOCK payload: `-4`; oversized write payload: `-3`.
- Insufficient IS_ROOT/geometry response capacity: `-5`.
- Failed media I/O, busy image, corrupt header, or geometry mismatch: `-6`.

For transfers, the block range is checked after the four-byte index is present
and before the remaining payload/response size. Failed writes caused by host
I/O may have partially modified media; they are not transactions.

Images live at `openwac/disks/<uuid>.disk` within the world, with a versioned
32-byte host header outside guest capacity. The persistent root flag is in
`<uuid>.disk.root`. Include both in backups. Geometry queries do not allocate
images; media access opens them lazily on a worker. Moving the item between
cases in the same world preserves its UUID and data; another world needs the
corresponding backing files as well.

## `0x0200`: File importer

Implementation: `core/device/importer/FileImporterDevice.java`.

The item exposes a read-only snapshot uploaded from a **player's client
machine**. Right-click the held importer, select an absolute local path, and
press Upload. Only the ASCII basename and bytes are sent to the server; the
client path is not a guest-visible or server-selectable path.

| Op | Name | Arguments | Response |
| --- | --- | --- | --- |
| `0x00` | FILE_SIZE | Empty | `file_size:u64` (8 bytes). |
| `0x01` | READ_BLOCK | `block:u32` (exactly 4 bytes) | Up to 256 bytes. |
| `0x02` | FILE_NAME | Empty | ASCII basename bytes, without terminator. |
| `0x03` | FILE_NAME_LENGTH | Empty | `filename_length:u32` (4 bytes). |

Query FILE_NAME_LENGTH before allocating a FILE_NAME response. For READ_BLOCK,
calculate `offset = block * 256` and, if `offset < file_size`,
`response_length = min(256, file_size - offset)`. The final block may be short;
the return status remains `0`. An empty file has size zero and no readable
blocks. Data does not include padding beyond the file's end.

- Unsupported operation: `-2`.
- Unexpected query arguments or extra READ_BLOCK arguments: `-3`.
- Fewer than four block-index bytes: `-4`.
- Response buffer too small for the requested value, name, or block: `-5`.
- Block offset at or beyond the captured file size: `-10`.
- No uploaded file, invalid source, unreadable/truncated backing file, or
  failed read: `-6`.

Source resolution and metadata capture happen on the first invocation that
passes operation/argument checks. The selected snapshot stays fixed for that
device instance; re-upload and reboot to select new content. If initialization
fails, that instance remains broken until recreated. FILE_SIZE and name queries
return captured metadata; reads still access the server backing file.

Uploads are limited to **16 MiB per file**, use acknowledged 16 KiB chunks,
and permit one session per player and eight per server. The item must remain
held during upload. Complete files are published atomically under the world's
`openwac/imports` directory and survive player disconnects and server restarts.
The basename must be nonempty ASCII, at most 255 bytes, with no path separators
or control characters. World blob storage is capped at 256 MiB; identical
content is deduplicated, and superseded blobs are currently retained. These
are host upload policies, not guest filesystem APIs. See
[the importer procedure](architecture.md#15-importing-compiled-programs-from-a-players-computer).

## `0x0400`: Tiny Text Display (TTD)

Implementation: `core/device/display/TtdDevice.java`, backed by `TtdBuffer`.

A fixed **60-column by 30-row**, white-on-black display. Each cell holds one
CP437 byte. The supplied 32-by-8 glyph atlas uses 9-by-16-pixel glyphs, giving
a 540-by-480-pixel logical screen. All 256 byte values select glyphs directly;
there is no UTF-8 decoding or interpretation of newline, tab, or escape bytes.
Terminal behavior belongs in the guest driver.

Coordinates are zero-based with the origin at the top left. A `rect` is
`x:u32, y:u32, width:u32, height:u32` (16 bytes). Rectangles must be nonempty
and fully inside the display; no clipping is performed. Cell arrays are
row-major, containing `width * height` bytes without row padding.

| Op | Name | Arguments | Response |
| --- | --- | --- | --- |
| `0x00` | GEOMETRY | Empty | `columns:u32, rows:u32` (8 bytes). |
| `0x10` | READ_CELLS | `rect` | `width * height` CP437 bytes. |
| `0x20` | WRITE_CELLS | `rect`, then exactly `width * height` CP437 bytes | Empty. |
| `0x21` | FILL | `rect`, then `character:u8` (17 bytes total) | Empty. |
| `0x22` | COPY | Source `rect`, then `destination_x:u32, destination_y:u32` (24 bytes total) | Empty. |
| `0x30` | SET_CURSOR | `x:u32, y:u32, visible:u8, blink:u8` (10 bytes) | Empty. |
| `0x31` | GET_CURSOR | Empty | Same 10-byte cursor record. |

COPY snapshots the source before modifying the destination, so overlap works
in every direction. It does not erase the source. Scrolling can be implemented
by copying rows and filling the vacated area with spaces.

Cursor booleans must be `0` or `1`, and its position must be inside the screen
even when hidden. The cursor is a white underline over the bottom two pixels
of its cell; it does not modify cell memory. Blink is animated on the client,
approximately half a second on and half a second off. Initial cell bytes are
spaces (`0x20`) and the cursor is hidden at `(0,0)`.

- Unsupported operation: `-2`.
- Short rectangle/cursor headers or short operation payloads: `-4`.
- Extra bytes, unexpected query arguments, or invalid boolean values: `-3`.
- Insufficient query/read response capacity: `-5`.
- Invalid coordinates, zero dimensions, or rectangles outside the screen: `-10`.
- An expired attachment lease or unpowered display: `-7`.

Rectangle bounds are checked before the operation-specific trailing payload
length. Rejected argument/bounds checks do not change cells or cursor state.
Complete reads/writes, fill, copy and snapshots are synchronized on the buffer.

The external block requires an installed Peripheral Port. It can connect by
adjacency or a saved logical-wire link and appears directly in normal HAL
discovery. Only one computer may own a live attachment at a time; detachment
invalidates old handles. UUID, cells, cursor and revision survive saves and
normal survival breaking/replacement. Powering off or disconnecting the
computer turns the screen black and hides the cursor while retaining cell
memory. Restoring supply displays the retained image unless the guest redraws
it. Supply state is transient and initially off until claimed; host power
control does not change the guest operation layouts. Clients receive initial
snapshots, changed-row spans and separate power-transition messages;
right-click opens a larger read-only view. See [TTD usage](ttd.md),
[atlas layout](ttd-font.md), and [diagnostic BIOS images](../firmware/ttd-tests.md).

## `0x0500`: Peripheral port

Implementation: `core/device/PeripheralPortDevice.java`.

The installed component is a **bus switch**. Without one, the computer does
not discover or claim external peripherals. With one, connected peripherals
appear directly in `hal_device_count`/`hal_device_info`, retaining their own
UUIDs and interfaces. Calls target those UUIDs directly; the port provides no
nested discovery or forwarding operation. Internal BIOS, disk and file-importer
components do not require a port.

| Op | Name | Arguments | Response |
| --- | --- | --- | --- |
| `0x00` | INFO | Empty | `adjacent_positions:u32` (4 bytes), currently `6`. |

INFO reports the six automatic face-adjacent positions, **not** the attached
device count, total device limit, or wire-link count. Unsupported operations
return `-2`, unexpected arguments `-3`, and response capacity below four bytes
`-5`. Successful INFO returns status `0` and writes the value into the buffer.

One port enables both the six adjacent positions and up to **16 saved wire
links**, with a **16-block** straight-line distance limit in the same dimension.
Multiple ports do not expand those limits. Peripheral Wire links require no
line of sight, do not force chunks to load, and pin the target's UUID as well
as its position. Unloaded links remain saved and reconnect when available.
Changing the port item requires power off; logical wires can change while
powered. Existing one-shot BIOS diagnostics need a reboot to enumerate again.
See [wire controls and lifetime](peripheral-wire.md).

## `0x0600`: Keyboard

Implementation: `core/device/input/KeyboardDevice.java`. External block:
`openwac:keyboard`. Requires a Peripheral Port and an adjacent or logical-wire
connection. A server-authorized nearby player starts capture by clicking the
block. Input capture is independent of the screen and forwards Escape.
See [keyboard usage](keyboard.md).

| Op | Name | Arguments | Response |
| --- | --- | --- | --- |
| `0x00` | INFO | Empty | `record_bytes:u32` (24), `capacity:u32` (256), `queued:u32`, `dropped:u32`, `capturing:u32` (0 or 1): 20 bytes. |
| `0x01` | AVAILABLE | Empty | `queued:u32`: 4 bytes. |
| `0x10` | READ_EVENTS | Empty | `count:u32`, followed by `count` 24-byte event records. |

All fields are little-endian and successful status is `0`. READ_EVENTS requires
at least **28 bytes of capacity**, even when empty. It consumes at most
`floor((response_capacity - 4) / 24)` records. The response writes exactly
`4 + count * 24` bytes; if empty, only the zero count is written. Unused buffer
capacity is unchanged. AVAILABLE is a snapshot, not a reservation for a later
read. The queue is shared by processes on the owning computer.

| Event offset | Size | Field |
| ---: | ---: | --- |
| 0 | 4 | `type:u32`: 1 KEY_DOWN, 2 KEY_UP, 3 KEY_REPEAT, 4 TEXT, 5 RESET. |
| 4 | 4 | `scancode:u32`: SDL physical scancode, 0..511. Zero for TEXT/RESET. |
| 8 | 4 | `keycode:u32`: layout-aware SDL keycode. Zero for TEXT/RESET. |
| 12 | 4 | `modifiers:u32`: SHIFT=1, CONTROL=2, ALT=4, SUPER=8, CAPS_LOCK=16, NUM_LOCK=32. |
| 16 | 4 | `codepoint:u32`: Unicode scalar for TEXT, zero for other types. |
| 20 | 4 | `sequence:u32`: server-generated sequence number, wrapping modulo 2^32. |

Scancodes come from Minecraft 26.3's SDL keyboard input, not GLFW key constants.
For example, Escape is 41 and its layout keycode is 27. The TEXT event is
separate from the physical key event and can represent non-ASCII characters.
Repeated presses are classified using the client's held-key state. Modifier
flags for TEXT reflect the latest keyboard event. RESET fields other than
type and sequence are zero; guests must clear their held-key state on RESET.

The FIFO holds 256 records. Overflow discards its existing contents, adds their
count to `dropped` (modulo 2^32), then inserts RESET before the incoming event.
Starting/ending a capture session also appends RESET. Normal reads are FIFO
and atomic. Session end preserves queued events for reading; machine detachment
clears the transient queue and invalidates the attachment. No input is persisted
across device recreation or server restarts; the device UUID is persistent.

Unsupported operations return `-2`; nonempty arguments return `-3`; short
responses return `-5` without consuming records. An expired attachment returns
`-7`. There is no guest operation to start client capture, choose a player,
inject events, or inspect the host clipboard. All host input passes through
server-validated capture sessions.

## Status codes and implementation boundaries

| Status | Name | Current meaning |
| ---: | --- | --- |
| `0` | OK | Success; response bytes, if any, were copied into guest memory. |
| `-1` | UNKNOWN_ERROR | Defined shared value; not normally returned by these device operations. |
| `-2` | INVALID_OPERATION | Unsupported device opcode, except EEPROM's legacy `-3` convention. |
| `-3` | INVALID_ARGUMENT | Invalid shape/value; HAL also uses it for negative argument length or response capacity. |
| `-4` | ARGUMENT_OVERRUN | Missing required device argument bytes; EEPROM instead uses `-3`. |
| `-5` | RESPONSE_OVERRUN | Device response buffer too small; EEPROM instead uses `-3`. Also enforced by DeviceBus if a device returns too many bytes. |
| `-6` | DEVICE_BROKEN | Device I/O failure or unusable state. DeviceBus also maps uncaught device RuntimeExceptions to this result. |
| `-7` | DEVICE_NOT_AVAILABLE | UUID is absent from the bus or an external attachment has expired. |
| `-8` | POINTER_INVALID | Device-call UUID, argument, or response range is invalid in guest memory. |
| `-9` | MEMORY_INVALID | Guest exported memory is unavailable. |
| `-10` | OUT_OF_BOUNDS | Invalid disk/importer block or TTD coordinates/rectangle. |
| `-11` | SYSCALL_TOO_EXPENSIVE | Device-call argument length plus response capacity exceeds 256 KiB. |
| `-12` | PERMISSION_DENIED | Defined shared value; unprivileged device imports are currently rejected at linking instead of returning this status. |

Errors are not translated to a generic transport status: negative device
results pass through the HAL. Validation order can determine which error is
returned when several inputs are invalid. These rules concern device calls;
other HAL imports can use different return conventions.

Kernel statuses `-13` through `-16` in the broader backend specification are
not currently defined by Java `DeviceStatus` or emitted by these devices.
There is no Java serial-monitor device, guest filesystem API, pixel framebuffer
interface, or guest port-routing protocol in the current implementation.
