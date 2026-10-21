# Peripherals

This page explains how players connect and use the supported external devices,
then records the rendering and capture behavior maintained by the integration.
The [device interface reference](device-interfaces.md) has exact operation
numbers, argument layouts, and status codes. Computer assembly and firmware
setup are in the [user guide](user-guide.md).

TTD is the currently implemented screen, with a wider lineup planned; see
[confirmed product direction](implementation-gaps.md#confirmed-product-direction).
The geometry and glyph rules below describe TTD specifically.

## Connect a peripheral

A **Peripheral Port** installed in a computer case enables discovery of external
hardware. One port enables all six directly adjacent block positions; additional
ports do not add positions. The powered computer attaches supported peripherals
it finds and lists each device directly through the regular HAL. Guest software
calls each peripheral by its own UUID. The port itself has an interface, but
does not forward or enumerate other devices.

Adjacency is automatic and works without a wire. For a farther screen or
keyboard, use the reusable **Peripheral Wire**:

1. Right-click the computer case and a TTD or Keyboard with the wire, in either
   order. Chat reports selection and connection. Both endpoints must be in the
   same dimension and within 16 blocks by straight-line distance between block
   positions, including height. Walls do not matter.
2. Both endpoint chunks must already be loaded. Linking never loads chunks.
   The case needs a Peripheral Port for the device to attach.
3. Repeat the endpoint clicks to toggle that saved link off. Crouch-right-click
   the case to clear all its wire links. Crouch-use the wire in the air to
   cancel a pending selection.

The wire is not consumed. Each computer saves at most 16 wire links, in addition
to its six adjacent candidates. The limit is link records, not connected
devices; extra ports do not raise it. Server interaction permissions and the
case container lock apply to both endpoints. A selection belongs to its player
and dimension. The wire handles endpoint clicks before a case or peripheral UI
opens.

Links are stored on the case as target position plus the peripheral's permanent
UUID. They survive world saves and restarts. Replacing a device at the same
position does not silently reconnect it; linking the replacement updates the
saved identity. Moving a device requires linking its new position. Destroyed
targets can leave dormant records until unlinked or cleared. If a linked chunk
unloads, the live attachment ends and is retried when both chunks are loaded.

Live ownership is exclusive: only one powered computer claims a given device
at a time. Another case can attach after the owner detaches or unloads. A wire
does not take a device away from a running computer. Direct adjacency and a
wire to the same endpoint still produce one attachment. Losing the port
disconnects external peripherals; power off before changing installed case
components. The bundled diagnostics discover devices once, so restart them
after connecting hardware. Other guests can enumerate devices again at runtime.

## Tiny Text Display (TTD)

The TTD is a 60 by 30 cell display. Each cell stores one literal CP437 byte; a
fresh display contains spaces. It does not interpret terminal controls, decode
UTF-8, or convert Minecraft text. Guest terminal software handles text
conversion, control characters, wrapping, and scrolling. The full device ABI is
in [interface `0x0400`](device-interfaces.md#0x0400-tiny-text-display-ttd).

The cell contents, cursor, revision, and permanent UUID persist in the world
and normal survival item state. Ownership and power do not persist. A powered
computer supplies display power while attached; disconnecting, losing the
port, unloading the owner, or stopping its runtime turns the panel black but
retains its contents. It becomes visible again when powered by a computer.
TTDs do not execute WASM themselves.

Right-click a display with an empty hand for an enlarged view. The
view closes if the display disappears or you move more than eight blocks away.
A Keyboard capture can also show a TTD while keeping input directed to the
selected keyboard; see below.

## Keyboard capture

A Keyboard is a separate peripheral and does not need a TTD. Right-click an
attached Keyboard to capture input while the world stays visible. Physical
presses, releases, repeats, and committed text go to its guest device. Use the
**Release keyboard** button or click the keyboard block to end capture.
By default, Escape ends capture and Alt temporarily returns control to the world.
Release Alt, then press it again to resume capture. The on-screen capture
toggles can forward these keys to the guest instead; toggling Escape capture
also sets Alt capture to the same value, after which Alt can be toggled separately.

During capture, click a visible TTD to open its enlarged view. **View world**
returns to the transparent world view without releasing the keyboard. The
screen and keyboard can be connected to different computers; display viewing
does not redirect input. Mouse clicks are not keyboard events. Clicking the
physical keyboard releases capture. Some operating-system shortcuts are
handled before Minecraft receives input and are outside capture control.

Capture ends on window focus loss, death, disconnect, dimension change, moving
more than eight blocks away, keyboard removal or unload, or loss/change of its
computer attachment. The server closes a session without a heartbeat for five
seconds. Only one player may capture a given keyboard, and a player may capture
one keyboard at a time. Events and capture state are transient: detaching clears
queued events and invalidates old guest handles, while the keyboard UUID
persists.

The keyboard interface is `0x0600`; its exact event record and operations are
in [interface `0x0600`](device-interfaces.md#0x0600-keyboard). Guest drivers
should use physical key events for navigation and TEXT events for text input,
avoiding duplicate text insertion. The host does not convert typed Unicode to
CP437. Capture is input hardware, not a terminal editor, command executor, or
clipboard interface.

## Maintainer notes: rendering and synchronization

The shared atlas is `openwac:textures/display/cp437.png`, a 288 by 128 image
arranged as 32 columns by 8 rows of 9 by 16 pixel glyphs. The cell byte is
unsigned: let `code = Byte.toUnsignedInt(character)`, then source X is
`(code & 31) * 9` and source Y is `(code >>> 5) * 16`.
Render the complete glyph cell (including black background) with nearest
filtering and no mipmaps, preserving its 9:16 aspect ratio. The world renderer
batches glyphs; the enlarged screen uses the same atlas. Neither uses
Minecraft's font renderer or per-display GPU textures.

`TtdBuffer` owns bounded, synchronized cell and cursor state, revisions, and
dirty spans. The block entity persists that state and sends at most one changed
delta per server tick to clients tracking its chunk. Deltas carry changed row
spans and cursor state; power changes are separate. Initial chunk data and
resynchronization carry a full 1,800-cell snapshot. Clients reject out-of-order
deltas and request a snapshot when a revision gap appears. Snapshot requests
are retried client-side at most once per second per display and limited
server-side to one per 200 ms per player, within 64 blocks. Client packets
cannot mutate guest display memory.

## Maintainer notes: capture and attachment

`PeripheralEndpoint` adapts world block entities to the core device lease.
`ComputerCaseBlockEntity` merges six loaded adjacent positions with up to 16
saved identity-pinned links, claims/release leases, and attaches devices
directly to the normal device bus. It does not force-load chunks. Discovery and
invocation remain independent of wire topology.

`KeyboardDevice` owns the bounded transient FIFO and capture generation.
`KeyboardNetwork` issues server session tokens and validates player, dimension,
range, block identity, interaction permission, and attachment generation.
Input packets carry scalar event fields; sessions are capped at 64 and input
has a 128 events/second rate with a 256 event burst. `KeyboardCaptureScreen`
starts native text input, routes key and text events, sends periodic heartbeats,
and shares the TTD renderer for optional viewing. Its focus and world-click
handling are client-side; the server remains authoritative for session validity.

Implementation: [PeripheralWireItem.java](../src/main/java/org/mod/openwac/integration/neoforge/item/PeripheralWireItem.java),
[KeyboardCaptureScreen.java](../src/main/java/org/mod/openwac/integration/neoforge/client/KeyboardCaptureScreen.java),
[KeyboardNetwork.java](../src/main/java/org/mod/openwac/integration/neoforge/network/KeyboardNetwork.java),
[TtdBlockEntity.java](../src/main/java/org/mod/openwac/integration/neoforge/blockentity/TtdBlockEntity.java),
[TtdNetwork.java](../src/main/java/org/mod/openwac/integration/neoforge/network/TtdNetwork.java).
