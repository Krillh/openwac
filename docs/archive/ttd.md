# Tiny Text Display

## Using a screen

A separate [Keyboard peripheral](keyboard.md) now supplies input. Click its
block to capture keys while viewing the world, or click a TTD during capture
to enlarge it without losing keyboard input. Escape goes to the guest; use
the release button or click the keyboard to exit capture.

For ready-to-run guest programs, see [TTD diagnostic BIOS images](../firmware/ttd-tests.md):
**BIOS EEPROM (TTD Write Test)** exercises screen operations, while
**BIOS EEPROM (File Importer to TTD)** displays uploaded file contents.

1. Obtain **Tiny Text Display** from the OpenWAC creative tab. Place it directly
   beside a computer case, above it, or below it. Its front faces the placing
   player. Install a **Peripheral Port** in one of the case's component slots
   while powered off, and put the BIOS EEPROM in its dedicated slot.
2. Power on the case. With the port installed, its guest can discover interface `0x0400` with the normal
   HAL enumeration and use the display's permanent UUID for device calls.
3. Draw through the cell operations below. A fresh screen is blank; the mod
   does not automatically redirect chat output or import file contents to it.
4. Read the display on its block face, or empty-hand right-click for a larger
   view. The enlarged view closes when the screen disappears or the player
   moves more than eight blocks away.

For a screen farther away, use a **Peripheral Wire** to click the case and
screen in either order. Logical links work within 16 blocks without line of
sight. See [wire usage and lifetime](peripheral-wire.md).

The display has 60 x 30 cells. Each cell is exactly one CP437 byte, rendered in
white on black using the supplied 9 x 16 atlas. There is no VGA emulation and
no Minecraft-font or UTF-8 conversion. The guest terminal driver implements
control characters, wrapping and scrolling. Glyph bytes are always literal.

## Guest operations

Full ABI: [interface 0x0400](true_device_interfaces.md#0x0400-tiny-text-display-ttd).
All integers are little-endian; response data goes into caller buffers and
`hal_device_call` returns status only.

For example, to write `Hello!` at column 2, row 3, call operation `0x20` with:

```text
02 00 00 00  03 00 00 00  06 00 00 00  01 00 00 00  48 65 6c 6c 6f 21
    x=2          y=3         width=6       height=1      CP437 bytes
```

The argument length is 22 bytes, response capacity is zero, and success returns
zero. A geometry query (`0x00`, no arguments, 8-byte response) writes the column
count and row count. Drivers should query dimensions rather than assume them.

To clear the display, use FILL (`0x21`) with rectangle `(0,0,32,18)` and byte
`0x20`. To scroll upward one line, COPY (`0x22`) rectangle `(0,1,32,17)` to
`(0,0)`, then FILL rectangle `(0,17,32,1)` with spaces. COPY safely handles
overlap. A cursor is optional: SET_CURSOR (`0x30`) takes x, y and two boolean
bytes for visibility and blinking. Cursor state does not change cell contents.

## Java responsibilities

- `TtdBuffer`: bounded cell storage, atomic rectangle operations, cursor state,
  immutable snapshots, revisions, and dirty spans. Synchronized for guest
  workers, Minecraft persistence, and client reads.
- `TtdDevice`: interface validation and exclusive attachment leases. Invalidated
  leases reject stale calls without touching Minecraft objects. A separate host
  supply flag controls whether the display is powered, independently of memory.
- `ComputerCaseBlockEntity`: polls the six neighboring positions plus up to
  sixteen saved logical-wire targets in loaded chunks,
  enables discovery only with a Peripheral Port installed, attaches claimed
  screens directly to the HAL bus before boot, handles hot attachment/removal, and
  releases ownership on shutdown. No neighboring chunks are force-loaded.
- `TtdBlockEntity`: owns the UUID/core state, saves/restores it, and sends at
  most one dirty update per server tick to clients tracking its chunk. Power
  transitions have their own small update, even when no cells change.
- `TtdPayloads` / `TtdNetwork`: bounded codecs, versioned deltas, full-snapshot
  requests with range checks and a per-player request rate limit. Clients have
  no drawing or mutation packet; only the server's guest runtime draws.
- `TtdClientNetwork`: applies ordered deltas and requests a full snapshot after
  a revision gap. Requests retry at most once per second per display while it
  is rendered or open.
- `TtdRenderer` / `TtdScreen`: draw the supplied atlas in the world and enlarged
  UI. The world renderer submits all glyphs in one geometry batch. Blink is
  animated locally without additional network messages or guest memory writes.

Dirty updates carry the smallest span covering changed cells on each affected
row, plus the current cursor. A cursor-only update contains no spans. Initial
chunk data and explicit resynchronization contain all 1,800 bytes. CPU time,
memory, packet count and decoded payload sizes are bounded by fixed geometry.

## Lifetime

The Peripheral Port acts as a bus switch. TTDs keep their own UUIDs and interface
IDs in ordinary HAL discovery, and guest code calls the TTD UUID directly.
There is no nested port enumeration or forwarding ABI. Without a port, external
screens are neither claimed nor exposed; BIOS, disks and file importers installed
inside the case remain available. One port enables all six adjacent positions;
installing extra ports does not expand the attachment range. A Peripheral Wire
adds logical connections to devices within 16 blocks. Power off to add
or remove the port.

Only one powered computer can own a screen at a time. Another adjacent case
can claim it when the owner detaches or unloads. Display contents and its UUID
survive world saves, chunk reloads, server restarts, and normal survival
breaking/replacement. Powering off the computer releases ownership and turns
the panel black, including its cursor and any enlarged view. The cell buffer
is retained and becomes visible when power returns, unless guest software
replaces it. Disconnecting/unloading the owning computer, losing the port or
wire connection, and runtime exit/crash also remove display power. A different
eligible computer may subsequently claim and power the display.

Supply state is transient and is not restored as powered from disk or item
data. On reload a screen remains off until claimed by a running computer.
Clients receive current power with initial chunk data and explicit snapshot
requests, plus standalone updates on later changes. These updates do not alter
cell revisions or require retransmitting the frame.

`TtdDevice.setPowered(boolean)` lets a future host power policy suspend supply
while retaining ownership and memory. Calls through an unpowered display's
lease return DEVICE_NOT_AVAILABLE (`-7`). The current policy supplies power
when a computer attaches and removes it when that attachment ends; energy
storage, consumption rates and power budgets are not implemented yet. Screens
do not execute WASM themselves.

TTD itself remains display hardware only. Keyboard input is a separate device;
mouse input, a graphical framebuffer, multiblock screens, and physical cable
routing remain future work.
