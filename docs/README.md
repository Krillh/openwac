# OpenWAC documentation

Start with the page that matches your role. The current Java implementation is authoritative for what works today. The archive preserves older design notes; treat proposed or unverified behavior there as historical context. Current proposals and unverified items are collected in [implementation gaps](implementation-gaps.md).

For the intended release experience, see [confirmed product direction](implementation-gaps.md#confirmed-product-direction).

These docs cover the mod, hardware, and host runtime contracts. OpenWACOS's
bootloader, kernel, OS, and shell are developed in a separate repository; their
internals and guest syscall API belong there. Built-in media, RAM compatibility,
and hardware support for guest scheduling are in scope here.

## Choose a page

| Audience | Page | Purpose |
|---|---|---|
| Players and server operators | [User guide](user-guide.md) | First boot, BIOS, file upload, disks, backups, and troubleshooting |
| Players wiring devices | [Peripherals](peripherals.md) | Connections, display, keyboard capture, and integration behavior |
| Guest program developers | [Device interfaces](device-interfaces.md) | Implemented device operations, payloads, and layouts |
| Guest kernel developers | [Kernel runtime](kernel-runtime.md) | Kernel bridge, syscalls, blocking, and process lifecycle |
| Guest Wasm developers | [HAL](hal.md) | Host imports, call ABI, scheduling, and process control |
| Maintainers | [Architecture](architecture.md) | Ownership, lifecycle, scheduling, safety, and persistence |
| Contributors | [Development](development.md) | Build setup, firmware sources, and verification commands |
| Researchers and contributors | [Implementation gaps](implementation-gaps.md) | Proposed, incomplete, or unverified work |

## Reading paths

- **Boot and use a computer:** [User guide](user-guide.md), then [Peripherals](peripherals.md) as needed.
- **Write a guest program:** [HAL](hal.md), then [Device interfaces](device-interfaces.md).
- **Build a guest OS:** [Kernel runtime](kernel-runtime.md), then [HAL](hal.md) and [Device interfaces](device-interfaces.md).
- **Change host code:** [Architecture](architecture.md), the relevant interface page, then [Development](development.md).

## Historical archive map

The files in [archive](archive/) are retained as historical reference. Use these current pages for the consolidated documentation:

Archived references to disk root flags, IS_ROOT/SET_ROOT operations, and `.disk.root` metadata describe a removed feature. See the current [disk interface](device-interfaces.md#0x0100-disk) for supported operations.

| Archived file | Current coverage |
|---|---|
| [architecture.md](archive/architecture.md) | [Architecture](architecture.md), [User guide](user-guide.md), [Implementation gaps](implementation-gaps.md) |
| [device_interfaces.md](archive/device_interfaces.md) | [Device interfaces](device-interfaces.md), [HAL](hal.md) |
| [true_device_interfaces.md](archive/true_device_interfaces.md) | [Device interfaces](device-interfaces.md); proposed or unverified notes belong in [Implementation gaps](implementation-gaps.md) |
| [hal.md](archive/hal.md) | [HAL](hal.md) |
| [syscalls.md](archive/syscalls.md) | Host bridge: [Kernel runtime](kernel-runtime.md), [HAL](hal.md). Guest OS API belongs in the separate OpenWACOS repository. |
| [dynamic_libraries_plan.md](archive/dynamic_libraries_plan.md) | [Implementation gaps](implementation-gaps.md) |
| [keyboard.md](archive/keyboard.md) | [Peripherals](peripherals.md) |
| [peripheral-wire.md](archive/peripheral-wire.md) | [Peripherals](peripherals.md), [Architecture](architecture.md) |
| [ttd.md](archive/ttd.md) | [Peripherals](peripherals.md), [Device interfaces](device-interfaces.md) |
| [ttd-font.md](archive/ttd-font.md) | [Peripherals](peripherals.md) |

## Firmware and diagnostics

The [firmware directory](firmware/) contains the mod's diagnostic firmware sources and diagnostic recipes. OpenWACOS bootloader source and builds belong in the separate OpenWACOS repository; the mod consumes its bundled resource, not a file under `docs/`. See [Development](development.md) for diagnostic build inputs, bundled artifact integration, and verification commands, and [User guide](user-guide.md) for player-facing setup and recovery.
