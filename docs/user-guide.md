# Player and server operator guide

OpenWAC adds placeable computers assembled from components. The computer runs its firmware on the server; nearby players see its chat output and use attached peripherals in game.

This guide describes the current development build. Planned release media,
EEPROM workflows, and diagnostic-item removal are tracked in
[confirmed product direction](implementation-gaps.md#confirmed-product-direction).

## First boot

1. Find **Computer Case**, **CPU (Tier 1)**, **RAM (Tier 1, 1 MiB)**, and a BIOS EEPROM in the OpenWAC creative tab (or obtain them from your server operator).
2. Place the case and right-click it to open its inventory. Install the CPU in the CPU slot, RAM in a RAM slot, and one BIOS EEPROM in the BIOS slot.
3. The case has nine ordinary component slots, one dedicated BIOS slot, one CPU slot, and four dedicated RAM slots. Regular components such as a disk drive, file importer, or peripheral port go in the ordinary slots. Each slot holds one item.
4. Close the inventory and **shift-right-click the case with an empty hand** to turn it on. Shift-right-click again to turn it off. Components are locked while it is on. Startup or firmware failures are reported in chat and recorded in the server log.

The host requires at least one RAM module; **OpenWACOS currently needs 2 MiB to boot**, so install two tier-one RAM modules for it. Install **exactly one** BIOS EEPROM; a second EEPROM cannot go in an ordinary component slot. The CPU, RAM, and EEPROM must each be in their dedicated slot. A peripheral port is only needed for external peripherals; see [Peripheral setup and controls](peripherals.md).

For disk boot in this build, use the bundled **BIOS EEPROM (Bootloader)** and a disk drive. The bootloader looks for a valid guest system on the disk; a blank disk is not a complete operating system. The ordinary **BIOS EEPROM** currently runs the bundled bootstrap firmware. The test EEPROMs below are temporary development diagnostics:

| BIOS EEPROM | What it does | Output |
| --- | --- | --- |
| BIOS EEPROM | Runs the small bundled bootstrap image | Chat |
| BIOS EEPROM (Bootloader) | Loads guest software from a disk | Chat / guest-defined |
| BIOS EEPROM (Disk Self-Test) | Exercises disk read/write operations | Chat |
| BIOS EEPROM (File Importer Test) | Reads uploaded files without executing them | Chat |
| BIOS EEPROM (TTD Write Test) | Checks a Tiny Text Display and draws a glyph chart | Chat and TTD |
| BIOS EEPROM (File Importer to TTD) | Prints uploaded text on a Tiny Text Display | Chat and TTD |

The diagnostic EEPROMs overwrite display contents when applicable. Use a screen whose current contents you can replace. See [TTD diagnostic instructions](firmware/ttd-tests.md) and [file importer test instructions](firmware/importer-test-v1.md) for expected results.

For the **Disk Self-Test**, use a spare disk and stay within 16 blocks for chat output. Expect geometry, I/O, bounds, and disk PASS messages followed by `SELF-TEST COMPLETE`. The test temporarily overwrites block 510 and restores it on completion; interruption or I/O failure can leave test bytes there. It writes a persistent marker to blank block 511 and leaves occupied marker blocks intact. Save/reload, then power-cycle to check `PERSISTENCE PASS`. Keep the case powered until the test finishes.

## Import a local file

1. Put a **File Importer** in your inventory and hold it.
2. Right-click in the air to open the importer screen. Enter an absolute path to a readable regular file on the computer running the game client, then choose **Upload**.
3. Keep holding that importer until the uploaded confirmation. Power off the case, install the importer in an ordinary component slot and a compatible BIOS, then power on.

The client reads the local file only after this explicit upload action. The screen uploads its basename and contents; it does not send the local path to the server. Files must be at most 16 MiB and have an ASCII basename of at most 255 characters. The selected file must remain unchanged during upload. An upload can be canceled by closing the screen; timed-out uploads need to be restarted. Uploading stores a snapshot, so changing the local file does not update an already-uploaded file. Upload it again to replace the snapshot.

The importer is a read-only guest device. The file-importer diagnostic BIOS prints its contents; it does not execute uploaded programs. The TTD importer diagnostic accepts text bytes as documented in [TTD tests](firmware/ttd-tests.md). To replace an active snapshot, power off, remove the importer, upload again, reinstall, and boot. Transfers allow one session per player, eight per server, and expire after two idle minutes.

## Save data, disks, and backups

The computer inventory, machine ID, power state, peripheral links, and component IDs are saved with the Minecraft world. Guest disk contents, imported-file snapshots, and uploaded firmware blobs are stored beneath that world's `openwac` directory:

| Data | World directory |
| --- | --- |
| Disk contents | `openwac/disks/` |
| Imported file snapshots and metadata | `openwac/imports/` |
| Uploaded firmware images | `openwac/firmware/` |

Stop the server before making a backup. Copy the complete world save, including its `openwac` directory. Restoring only the Minecraft region/player data can leave disks, imports, or custom firmware missing; restoring only `openwac` can leave inventory items without their associated data. Keep the world and `openwac` data from the same backup point.

Power off before removing a disk. Moving the same disk item between cases in the same world preserves its data. Empty backing files may be reclaimed on ejection. Permanent disposal, including creative deletion or `/clear`, can delete the backing files; the disposal code checks online inventories and nearby dropped items to avoid deleting ordinary transfers. Use world backups to preserve data you need.

## Troubleshooting

- **Case reports startup failure:** Check that the CPU, at least one RAM module, and exactly one BIOS EEPROM are installed in their dedicated slots. Remove all components except the minimum set, then power-cycle. Read the detailed crash report in the server log.
- **Firmware or guest crashes:** Nearby players receive a crash notice. Check the server log for the OpenWAC crash report, including the machine location, BIOS reference, and failure details. A bad disk image or invalid guest module may need to be replaced; back up disk data before destructive recovery.
- **Kernel installation fails:** Search the server console or `logs/latest.log` for `OpenWAC kernel installation failed (-16)`. This warning includes the rejected module hash, failure stage, and cause, even if the bootloader continues. Whole-computer failures begin `OpenWAC computer crash report`. Power-cycle after correcting a startup fault.
- **Importer says it cannot read the file:** Confirm the file exists on the client machine, use an absolute path, check the ASCII basename and 16 MiB limit, then upload again. The server cannot browse the client's files itself.
- **Peripheral is missing:** Confirm a Peripheral Port is installed in an ordinary component slot and the peripheral is connected/linked correctly. See [Peripheral setup and controls](peripherals.md) for wire range, keyboard use, and TTD details.
- **Disk appears blank after moving it:** A disk drive's identity is saved with its item. Keep the same disk item when moving it, and restore the matching world's `openwac/disks/` data together with the world save.
- **Scratch or diagnostic files appear on disk:** They may have been written by firmware or guest software during a test or boot. Disk contents persist across power cycles; use a backup or a known-good disk before clearing or overwriting data.

See [Documentation index](README.md) for operator, developer, and device references. Build and firmware workflows are in [Development](development.md); detailed peripheral controls are in [Peripheral setup and controls](peripherals.md).
