# Bundled data

This directory holds binary artifacts shipped with the mod. Gradle copies
`firmware/` and `floppies/` into `data/openwac/` in the mod JAR and development
runtime resources. Markdown files and `.gitkeep` placeholders are excluded.

| Repository path | Packaged path | Use |
| --- | --- | --- |
| `firmware/bootloader-v1.wasm` | `data/openwac/firmware/bootloader-v1.wasm` | BIOS EEPROM (Bootloader), item `openwac:bootloader_bios_eeprom`. |
| `firmware/bootstrap-v1.wasm` | `data/openwac/firmware/bootstrap-v1.wasm` | Current diagnostic default BIOS EEPROM. |
| `firmware/*-test-v1.wasm` | `data/openwac/firmware/*-test-v1.wasm` | Development diagnostic EEPROMs. |
| `floppies/` | `data/openwac/floppies/` | Future built-in floppy images, including OpenWACOS for the first official release. |

Replace `firmware/bootloader-v1.wasm` with a compiled artifact from the separate
OpenWACOS repository to update the default bootloader. Keep its source and
build instructions upstream; record the upstream version and artifact hash
when updating the binary. The item loads the packaged classpath resource.

The four C diagnostic images are rebuilt here by `tools/build-firmware.ps1`.
See [Development](../docs/development.md) for their source paths and toolchain.
Keep these binaries here rather than maintaining copies under
`src/main/resources/`.

`floppies/` reserves a packaging location; floppy hardware and image installation
are not implemented yet. Add release images when that support is available.
Bundled floppy images are distribution templates. Floppies and hard disks are
separate media types in this mod; `floppies/` holds the bundled floppy images.
The existing world-local `openwac/disks/` directory stores mutable hard disk
contents.
