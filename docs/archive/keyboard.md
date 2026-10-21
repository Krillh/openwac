# Keyboard peripheral

## Using it

1. Obtain a **Keyboard** from the OpenWAC creative tab and place it near the
   computer. It occupies the bottom quarter of its block space.
2. Install a **Peripheral Port** in the case, then connect the keyboard by
   direct adjacency or with a [Peripheral Wire](peripheral-wire.md).
3. Power on the computer and right-click the keyboard. It must have a live
   attachment before capture can start. Only one player can capture a keyboard
   at a time, and a player captures only one keyboard at a time.
4. The world remains visible. Physical key presses, releases, repeats, and
   typed characters now go to the computer. Normal player movement and actions
   are intercepted by the capture UI. A keyboard does not require a screen.
5. To exit, click **Release keyboard**, or click the keyboard block with the
   cursor. **Escape is forwarded to the guest and does not exit capture.**

While capturing, click a visible TTD block to open its enlarged view while
continuing to type. **View world** returns to the transparent view without
ending capture. The keyboard and screen are separate peripherals and need not
be adjacent to each other or share any direct device association. The enlarged
view is only a client convenience; input always goes to the selected keyboard.

The client projects mouse clicks into the world using the camera and pointer
position, so clicking the physical keyboard releases it even without a TTD
window. Mouse clicks are not delivered as guest keyboard events. Minecraft/OS
global shortcuts handled before screen input are not redefined by this device.
Ordinary Escape key events are explicitly captured.

Capture automatically ends on loss of window focus, player death/disconnect,
dimension change, moving more than eight blocks from the keyboard, block
removal/unload, or loss/change of the keyboard's machine attachment. The client
sends a heartbeat once per second; the server ends unresponsive sessions after
five seconds. Automatic termination prevents stuck input or capture on a stale
device; manually exiting still uses the button or physical keyboard click.

## Guest input

The keyboard is interface **`0x0600`**, `openwac:keyboard`, version 1.0. It is
discovered and invoked directly through the same HAL as other peripherals.
See [the wire contract](device_interfaces.md#0x0600-keyboard).

The guest polls READ_EVENTS (`0x10`). Every response starts with a little-endian
`u32 count`, followed by `count` 24-byte records. Successful calls return status
`0`, not a record count. A buffer of 28 bytes reads at most one event, 244 bytes
reads at most ten, and 6148 bytes can drain the whole 256-record queue. An empty
queue writes count zero. AVAILABLE (`0x01`) and INFO (`0x00`) provide query data.

Events distinguish physical down/up/repeat from committed Unicode text. On
Minecraft 26.3, physical keys use SDL scancodes (Escape is 41, A is 4, Return is
40); the separate keycode follows the current layout. Modifiers use normalized
flags rather than raw SDL masks. Guest drivers should use physical events for
navigation/shortcuts and TEXT events for text entry, avoiding duplicate insertion
from KEY_DOWN and TEXT. Conversion from Unicode to CP437 for TTD belongs in
the guest, not in the Java keyboard.

Starting and ending capture append RESET events. On FIFO overflow, the device
discards queued events, increments its dropped counter, and enqueues RESET
before the new event. RESET tells the guest to clear all held-key state. Events
and capture state are transient; detaching clears the queue and invalidates old
handles. The physical UUID survives saves, reloads, and normal survival drops.
Existing diagnostic BIOS images do not poll this interface or echo keyboard
input; guest software needs a keyboard driver to consume these events.

## Java and multiplayer behavior

- `KeyboardDevice` contains the synchronized bounded FIFO and machine lease.
  It has no Minecraft dependencies and does not feed process mailboxes.
- `KeyboardBlockEntity` implements `PeripheralEndpoint`, so it works with
  adjacency and wires without changes to HAL routing.
- `KeyboardNetwork` owns server-issued session tokens and checks the player,
  dimension, range, block identity, attachment generation and interaction
  permission before accepting input. A session cannot continue into another
  computer after the keyboard is reassigned.
- Input packets carry fixed-size scalar fields; no client-selected world path
  or arbitrary event buffer is accepted. Invalid values terminate capture.
  There are at most 64 sessions per server. Input is limited to 128 events per
  second with a burst allowance of 256 events; exceeding that limit ends capture.
- `KeyboardCaptureScreen` intercepts keyboard events, keeps Escape as guest
  input, enables native text input while open so committed characters produce
  TEXT events, and supplies the explicit release button. It uses the shared TTD
  renderer only when an enlarged display is requested.

This is an input device, not a terminal emulator or host clipboard interface.
It does not execute commands, edit guest text, or map keys to a particular TTD.
