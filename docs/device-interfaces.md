# Implemented device interfaces

This page is the guest-facing ABI for device interfaces implemented by the Java runtime. Device calls use the HAL transport; its imports, guest-memory rules, and privilege requirements are documented in [HAL](hal.md). Operation arguments and responses below describe only the device payloads.

## Registry and common rules

Each attached device has one UUID and one interface ID. Operation numbers are local to an interface; the currently implemented interfaces are:

| Interface ID | Name | Device | Operations |
| --- | --- | --- | --- |
| `0x0001` | `openwac:eeprom` | BIOS EEPROM | `00` INFO, `01` READ |
| `0x0100` | `openwac:disk` | Disk | `10` BLOCK_SIZE, `11` BLOCK_COUNT, `20` READ_BLOCK, `30` WRITE_BLOCK |
| `0x0200` | `openwac:file_importer` | File importer | `00` FILE_SIZE, `01` READ_BLOCK, `02` FILE_NAME, `03` FILE_NAME_LENGTH |
| `0x0400` | `openwac:ttd` | Tiny Text Display | `00` GEOMETRY, `10` READ_CELLS, `20` WRITE_CELLS, `21` FILL, `22` COPY, `30` SET_CURSOR, `31` GET_CURSOR |
| `0x0500` | `openwac:peripheral_port` | Peripheral port | `00` INFO |
| `0x0600` | `openwac:keyboard` | Keyboard | `00` INFO, `01` AVAILABLE, `10` READ_EVENTS |

All these interface descriptors are version 1.0. HAL discovery returns the numeric ID and UUID; it does not return the interface name or version.

Device payload integers are little-endian and unaligned. `u8`, `u32`, and `u64` use 1, 4, and 8 bytes. Device payload strings/byte arrays have no implicit terminator. All successful device calls return status `0`, including calls that produce response bytes; determine response length from the operation definition. Only returned bytes are copied, and unused response capacity is left unchanged. A negative status has no response bytes.

### Status codes

| Code | Name | Meaning in device calls |
| ---: | --- | --- |
| `0` | `OK` | Success; response may be empty or contain operation data. |
| `-1` | `UNKNOWN_ERROR` | Generic failure (not emitted by the current device implementations). |
| `-2` | `INVALID_OPERATION` | Unsupported operation ID. |
| `-3` | `INVALID_ARGUMENT` | Invalid argument value/shape; EEPROM uses this for all its validation errors, including unsupported operations and short response buffers. |
| `-4` | `ARGUMENT_OVERRUN` | Argument buffer is shorter than required. |
| `-5` | `RESPONSE_OVERRUN` | Response capacity is too small. |
| `-6` | `DEVICE_BROKEN` | Device I/O failed or state is unusable. |
| `-7` | `DEVICE_NOT_AVAILABLE` | Device UUID is unavailable, or an attached display/keyboard lease is no longer valid. |
| `-10` | `OUT_OF_BOUNDS` | Requested block, file block, display coordinate, or rectangle lies outside the device. |

The runtime also defines `-8` through `-16` for HAL memory/permission and kernel conditions; those are described in [HAL](hal.md), and are not device-operation errors emitted by these interfaces. A particular device uses only a subset of the shared statuses. Validation order below is relevant where multiple conditions are invalid.

## `0x0001` BIOS EEPROM

An EEPROM is read-only firmware media. Maximum image length is 1 MiB.

| Op | Name | Arguments | Response |
| --- | --- | --- | --- |
| `0x00` | INFO | Empty | `image_length:u32`, `maximum_length:u32`, `read_only:u32` (12 bytes; maximum is 1 MiB, read-only is `1`). |
| `0x01` | READ | `offset:u32`, `length:u32` (8 bytes) | Exactly `length` image bytes. |

READ accepts only values representable as nonnegative Java integers, requires `offset + length <= image_length`, and requires `length <= response_capacity`. Zero-length reads are valid, including at image end. Unexpected argument length, bad range/value, insufficient response capacity, or unsupported op returns `-3`. Missing or invalid firmware returns `-6`. Firmware is cached for the lifetime of the device object. There is no guest write operation.

## `0x0100` Disk

The tier-one disk has 512 blocks of 2048 bytes. Block numbers are zero-based (`0..511`). The interface exposes blocks only; it does not define files or a filesystem.

| Op | Name | Arguments | Response |
| --- | --- | --- | --- |
| `0x10` | BLOCK_SIZE | Empty | `block_size:u32` (4 bytes). |
| `0x11` | BLOCK_COUNT | Empty | `block_count:u32` (4 bytes). |
| `0x20` | READ_BLOCK | `block:u32` (exactly 4 bytes) | One full block. |
| `0x30` | WRITE_BLOCK | `block:u32`, then exactly one full block (2052 bytes for tier one) | Empty. |

Geometry queries require empty arguments. A short transfer header returns `-4`; unexpected query/read arguments return `-3`. Unsupported op returns `-2`, including the retired `0x00` and `0x01` root designation operations. Guest software chooses disk roles using its own policy or on-disk data. Transfer block index is interpreted unsigned and checked before transfer payload/response length; invalid index returns `-10`. READ_BLOCK response capacity below block size returns `-5`. WRITE_BLOCK short data returns `-4`, oversized data `-3`. Short query response capacity returns `-5`. Failed media operations return `-6`. Successful writes complete their storage flush before returning; there is no FLUSH operation.

## `0x0200` File importer

This interface reads a host-selected file snapshot; guest arguments cannot select a path. The importer reads in 256-byte blocks.

| Op | Name | Arguments | Response |
| --- | --- | --- | --- |
| `0x00` | FILE_SIZE | Empty | `file_size:u64` (8 bytes). |
| `0x01` | READ_BLOCK | `block:u32` (exactly 4 bytes) | Up to 256 file bytes. |
| `0x02` | FILE_NAME | Empty | ASCII basename bytes, no terminator. |
| `0x03` | FILE_NAME_LENGTH | Empty | `filename_length:u32` (4 bytes). |

For READ_BLOCK, `offset = unsigned(block) * 256`; offset at or beyond file size returns `-10`. Response size is `min(256, file_size - offset)`, so the final block can be short. Empty files have no readable block. Query FILE_NAME_LENGTH before allocating a FILE_NAME response. Query arguments and extra READ_BLOCK bytes return `-3`; a short block index returns `-4`; insufficient response capacity returns `-5`. Invalid/unreadable source, or a backing file truncated after its initial metadata capture, returns `-6`. Source initialization occurs on the first operation that passes operation/argument checks; a failed initialization leaves that device instance broken.

## `0x0400` Tiny Text Display (TTD)

The display is 60 columns by 30 rows. Each cell is one CP437 byte, row-major in transfers. The device does not decode UTF-8 or interpret control characters. Coordinates start at `(0,0)` at top left. A rectangle is `x:u32, y:u32, width:u32, height:u32` (16 bytes); it must have positive dimensions and fit completely in the display. No clipping is performed.

| Op | Name | Arguments | Response |
| --- | --- | --- | --- |
| `0x00` | GEOMETRY | Empty | `columns:u32, rows:u32` (8 bytes: `60,30`). |
| `0x10` | READ_CELLS | Rectangle | `width * height` CP437 bytes. |
| `0x20` | WRITE_CELLS | Rectangle, then exactly `width * height` CP437 bytes | Empty. |
| `0x21` | FILL | Rectangle, then `character:u8` (17 bytes total) | Empty. |
| `0x22` | COPY | Source rectangle, then `destination_x:u32, destination_y:u32` (24 bytes total) | Empty. |
| `0x30` | SET_CURSOR | `x:u32, y:u32, visible:u8, blink:u8` (10 bytes) | Empty. |
| `0x31` | GET_CURSOR | Empty | `x:u32, y:u32, visible:u8, blink:u8` (10 bytes). |

Cursor booleans must be 0 or 1, and position must be in the display even when hidden. READ/WRITE/FILL/COPY first require the 16-byte rectangle header and validate its bounds, then check the operation-specific payload size. A short header or payload returns `-4`; extra bytes, unexpected query args, or invalid booleans return `-3`; invalid rectangle/cursor coordinates return `-10`; insufficient response capacity returns `-5`; unsupported operation returns `-2`. COPY snapshots source bytes before writing the destination, including for overlapping regions. The initial cell contents are spaces and cursor starts hidden at `(0,0)`. Cursor updates do not modify cell contents.

Calls use an attachment lease. A detached, invalidated, or unpowered display returns `-7` for device operations.

## `0x0500` Peripheral port

The port's INFO operation reports the number of adjacent external connection positions, not the number of currently attached devices.

| Op | Name | Arguments | Response |
| --- | --- | --- | --- |
| `0x00` | INFO | Empty | `adjacent_positions:u32` (4 bytes; currently `6`). |

Unsupported op returns `-2`, unexpected arguments `-3`, and response capacity below four bytes `-5`. Connected peripherals are independently discovered and called by their own UUID; the port has no nested enumeration or forwarding operation.

## `0x0600` Keyboard

Keyboard events form a bounded, transient FIFO of at most 256 records, shared by processes using this device lease. Each event record is 24 bytes.

| Op | Name | Arguments | Response |
| --- | --- | --- | --- |
| `0x00` | INFO | Empty | Five `u32`: `record_bytes` (24), `capacity` (256), `queued`, `dropped`, `capturing` (`0`/`1`) (20 bytes). |
| `0x01` | AVAILABLE | Empty | `queued:u32` (4 bytes). |
| `0x10` | READ_EVENTS | Empty | `count:u32`, then `count` event records. |

READ_EVENTS requires at least 28 response bytes even when the queue is empty. It consumes at most `floor((response_capacity - 4) / 24)` records and returns exactly `4 + count * 24` bytes. AVAILABLE is only a queue snapshot; it does not reserve records. INFO and AVAILABLE require at least 20 and 4 response bytes respectively. All operations require empty arguments; unsupported op returns `-2`, nonempty arguments `-3`, and insufficient response space `-5`.

| Event offset | Size | Field |
| ---: | ---: | --- |
| 0 | 4 | `type:u32`: 1 KEY_DOWN, 2 KEY_UP, 3 KEY_REPEAT, 4 TEXT, 5 RESET. |
| 4 | 4 | `scancode:u32`: physical SDL scancode (`0..511`); zero for TEXT/RESET. |
| 8 | 4 | `keycode:u32`: layout-aware SDL keycode; zero for TEXT/RESET. |
| 12 | 4 | `modifiers:u32`: SHIFT=1, CONTROL=2, ALT=4, SUPER=8, CAPS_LOCK=16, NUM_LOCK=32. |
| 16 | 4 | `codepoint:u32`: Unicode scalar for TEXT, zero otherwise. |
| 20 | 4 | `sequence:u32`: incrementing sequence value, wrapping modulo 2^32. |

The host may emit RESET around capture state changes and on queue overflow; overflow clears queued events and increments the dropped count by the number discarded. Release of the device lease clears the queue. Calls through an invalidated lease return `-7`.

## Related documentation

Use [HAL](hal.md) for transport imports, discovery record encoding, memory ranges, and privilege. The kernel's device-call bridge belongs in [kernel runtime](kernel-runtime.md). Physical attachment, TTD rendering/font/capture, and peripheral wiring belong in [peripherals](peripherals.md). Player workflows belong in [user guide](user-guide.md). Persistence and design rationale belong in [architecture](architecture.md).

Implementation: [core/device](../src/main/java/org/mod/openwac/core/device/),
[DeviceResult.java](../src/main/java/org/mod/openwac/core/device/DeviceResult.java),
[DiskDriveItem.java](../src/main/java/org/mod/openwac/integration/neoforge/item/DiskDriveItem.java).
