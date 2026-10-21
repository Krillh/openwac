# Logical peripheral wire

The **Peripheral Wire** (`openwac:peripheral_wire`) is a reusable item in the
OpenWAC creative tab. It creates a logical connection, without placing cable
blocks or drawing a physical wire. There is no line-of-sight requirement.

## Connect and disconnect

1. Install a **Peripheral Port** in the computer case. It is still required
   for both adjacent and wired peripherals to appear in HAL discovery.
2. Hold a Peripheral Wire and right-click a computer case, then right-click a
   TTD or Keyboard. You can also click the peripheral first and the case second. Chat confirms the
   selected endpoint and completed connection.
3. The endpoints must be in the same dimension and at most **16 blocks apart**,
   measured as straight-line distance between block centers, including height.
   Walls do not matter. Both endpoint chunks must be loaded when linking.
4. The powered case attaches the peripheral on its next tick. Existing BIOS
   diagnostics discover hardware only once, so power-cycle to rerun them after
   creating the link.

Repeat the same two clicks to remove an existing link. Crouch-right-click a
computer with the wire to remove **all** its saved wire links. Crouch-use the
wire in the air to cancel its pending first endpoint. Automatic adjacency is
independent: unlinking an adjacent screen does not disable its automatic
connection. Move it away or remove the port if it must remain disconnected.

The wire is not consumed. A computer supports up to **16 saved wire links**,
in addition to its six automatic adjacent positions. Extra ports do not
increase these limits. A pending first click is associated with the selecting
player and dimension; another player cannot complete that selection. Normal
server interaction restrictions and the computer's container lock are checked
for both endpoints. The wire handles its click before either block opens a UI.

## Identity and lifetime

Links are saved on the computer case, not on the wire item. Each record stores
the target position and permanent peripheral UUID. They survive case/world
saves, chunk reloads, and server restarts. A different device placed at the
same position does not inherit the old connection. Restoring the same device
UUID at the same position restores the connection. Moving a device to another
position requires linking that new position.

Neither linking nor attachment force-loads chunks. If either endpoint unloads,
its live attachment is released and old guest handles become unavailable.
The saved link stays dormant and is retried when both endpoints are loaded.
Links to destroyed endpoints remain saved until removed or cleared; clicking a
replacement peripheral at that position replaces the stale target record.

Peripherals still have exclusive live ownership. Multiple cases may save links
to the same screen, but the first powered case to claim it owns it until it
detaches or unloads. A waiting case can attach afterward. Wiring does not steal
a device from a running computer. A direct and a wired connection to the same
position produce only one attached device.

## Architecture

`PeripheralEndpoint` is a small NeoForge integration interface for block
entities that adapt external hardware. TTD implements it by delegating to its
loader-independent device/lease implementation. Other external hardware can
implement the same interface to participate in automatic adjacency and wires.

`PeripheralConnections` owns the temporary range and link-count policy.
`PeripheralWireItem` validates endpoint selection and modifies the case's
saved links. `ComputerCaseBlockEntity.refreshPeripherals` merges adjacent and
wired candidates, checks identity and loaded chunks, and claims/releases the
corresponding core devices. At most 22 candidate positions are examined.

The HAL is unchanged: each connected peripheral appears directly with its own
UUID and interface ID and is invoked through `hal_device_call`. There is no
wire device interface, nested enumeration, or path routing in guest code.
A future cable or network system can replace the candidate discovery policy
without changing device implementations or WASM drivers. Case save version 3
adds `PeripheralLinks` while retaining the version-2 BIOS slot layout.
