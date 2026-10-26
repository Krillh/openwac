# Development

This page is for contributors changing Java runtime code or bundled firmware.
For the document index, start at [the docs directory](README.md). The Java
architecture is in [architecture.md](architecture.md).

## Toolchain and commands

The project targets Java 25, Minecraft 26.3, and NeoForge 26.3.0.94. The
Gradle wrapper pins Gradle 9.8. Chicory runtime and WASM parser are both
packaged with the mod and strictly constrained to the 1.7.5 release line
(`[1.7.5,1.7.6)`, preferring 1.7.5). Use the wrapper so the Gradle version
matches the project.

On Windows:

```powershell
.\gradlew.bat build
.\gradlew.bat runClient
.\gradlew.bat verifyRuntime
.\gradlew.bat check
.\gradlew.bat verifyRuntime --args=--disks-only
.\gradlew.bat verifyRuntime --args=--kernel-reports-only
.\gradlew.bat verifyRuntime --args=--crash-reports-only
.\gradlew.bat verifyRuntime --args=--boot-handoff-only
```

Use `runClient` for manual in-game checks. `verifyRuntime` compiles and runs
the standalone Java checks without Minecraft startup or a test framework.
`check` depends on `verifyRuntime`; it is not a replacement for playing
through client/server behavior. The focused switches above select disk, kernel
report, or crash report checks. With no switch, the suite covers scheduling
and fuel accounting, Chicory memory/kernel behavior, device dispatch, TTD,
metrics, firmware deduplication, continuations and preemption, hostile modules
and pointers, cold boot, disk behavior, and the shipped disk self-test firmware.

The bootloader check loads the bundled resource directly; no bootloader binary
under `docs/` is required. `--boot-handoff-only` checks that bootloader exit
precedes kernel initialization and child process creation, including failure
and cancellation paths.

Manual play checks are still needed for Minecraft integration: case inventory
and power flow, client screens and rendering, nearby chat and packet behavior,
external peripheral discovery, chunk/world save and reload, and actual player
interaction. See [user-guide.md](user-guide.md) for diagnostic gameplay and
[peripherals.md](peripherals.md) for renderer and peripheral implementation
details.

## Firmware inputs and bundled images

Diagnostic EEPROMs are development tools. Their release lifecycle is recorded
in [confirmed product direction](implementation-gaps.md#confirmed-product-direction).

The mod's diagnostic firmware sources live in [firmware](firmware/). The default script
is `tools/build-firmware.ps1`; it invokes `clang` targeting
`wasm32-unknown-unknown` with freestanding C flags and emits exactly four
images into `bundled-data/firmware/`:

| Source | Bundled resource |
| --- | --- |
| `docs/firmware/disk-test-v1.c` | `disk-test-v1.wasm` |
| `docs/firmware/importer-test-v1.c` | `importer-test-v1.wasm` |
| `docs/firmware/ttd-test-v1.c` + `ttd-firmware.h` | `ttd-test-v1.wasm` |
| `docs/firmware/importer-ttd-test-v1.c` + `ttd-firmware.h` | `importer-ttd-test-v1.wasm` |

Pass a compiler explicitly when needed:
`tools/build-firmware.ps1 -Clang C:\path\to\clang.exe`.
The WAT file describes the small diagnostic BIOS. Bootstrap and bootloader
resources are maintained separately from this four-image build. Runtime loading
uses the bundled resources. After changing C firmware source, rebuild and review
the resulting WASM resource as part of the same change.

OpenWACOS bootloader, kernel, OS, and shell source code and build instructions
belong in their separate repository. The `docs/` directory is not a source or
artifact location for the OpenWACOS bootloader. Here, maintain the bundled
artifact selection and host integration. Update
`bundled-data/firmware/bootloader-v1.wasm` from an upstream build and record the upstream version and artifact
hash when replacing it.

The top-level [bundled-data directory](../bundled-data/README.md) is the
repository location for shipped firmware and future built-in floppy
images. Gradle packages its `firmware/` and `floppies/` contents beneath
`data/openwac/`, including development runs. The bootloader EEPROM uses
`data/openwac/firmware/bootloader-v1.wasm` through its existing firmware
reference. Put future built-in floppy images under `bundled-data/floppies/`;
floppy support and OpenWACOS release images remain planned work.

Keep guest entry and imports aligned with the runtime: guest programs export
`_start: () -> ()`; automatic Wasm start sections are disabled. Pointer-based
HAL calls use the caller's exported linear memory named `memory`. Build
freestanding modules using the supported `hal`/`sys` function imports; the host
does not provide WASI or imported memory. The bootstrap illustrates the exports and yield/chat
imports. See [hal.md](hal.md) for function signatures, privilege, pointer rules
and return codes. Kernel module exports and installation requirements are in
[kernel-runtime.md](kernel-runtime.md).

Record the compiler version and build command when updating firmware.
Outstanding build provenance and tooling work is tracked in
[implementation gaps](implementation-gaps.md).

Build definitions: [build.gradle](../build.gradle),
[gradle.properties](../gradle.properties),
[build-firmware.ps1](../tools/build-firmware.ps1), and
[RuntimeChecks.java](../src/runtimeChecks/java/org/mod/openwac/RuntimeChecks.java).
