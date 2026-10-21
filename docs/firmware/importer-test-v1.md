# File importer test BIOS

For output to a Tiny Text Display instead of chat, use the separate
**BIOS EEPROM (File Importer to TTD)** described in [TTD diagnostic BIOS images](./ttd-tests.md).

The bundled `openwac:importer-test-v1` firmware reads file contents through
interface `0x0200` and prints them through `hal_chat_print`. It does not execute
the imported files. Source: [importer-test-v1.c](./importer-test-v1.c).

## In-game procedure

1. Restart the game with the updated mod. Create a small local text file, for
   example a file containing `Hello from my computer!` and a second line.
2. Get a **File Importer** and **BIOS EEPROM (File Importer Test)** from the
   OpenWAC creative tab.
3. Hold the importer, right-click in the air, enter the absolute path to the
   text file, and press **Upload**. Wait for the uploaded confirmation.
4. Power off a computer case. Install the uploaded importer and the test
   EEPROM. Remove any other BIOS EEPROM: the case allows exactly one.
5. With an empty hand, shift-right-click to power on. Stay within 16 blocks.
   Watch chat for the importer UUID, filename, byte count, and file contents.
   Content lines begin with `| `.
6. Expect `IMPORTER PASS: entire file read and printed.` for each readable
   importer, followed by `IMPORTER TEST COMPLETE: N passed, M failed.`

Multiple importers are processed individually in UUID enumeration order.
An unconfigured or unreadable importer reports a stage-specific error with
its negative HAL status; the BIOS continues to other importers. Empty files
print `(empty file)` and count as successful reads. No attached importer
produces a `NO IMPORTER` diagnostic.

## Output and ABI

The BIOS queries FILE_NAME_LENGTH, FILE_NAME, and FILE_SIZE, then reads each
256-byte block with a little-endian `u32` block index. It calculates the final
block's exact length from the `u64` file size. All calls expect status `0` on
success; no return value is interpreted as data or a byte count.

UTF-8 sequences and CRLF line endings are handled across device block
boundaries. LF and CRLF split chat lines; long lines are split into messages
of at most 512 bytes without splitting UTF-8 characters. Other control bytes,
invalid UTF-8, and the Minecraft formatting marker are escaped as `\xHH`.
Binary WASM files therefore produce escaped content rather than being run.

Chat permits four messages per second per computer. The firmware yields and
retries `-11` responses, preserving pending output. Start with a small file:
printing a large or binary file can take a long time. The BIOS yields forever
after its summary. Power-cycle to rerun it; upload again to test a changed file.

The compiled image is bundled at
`src/main/resources/data/openwac/firmware/importer-test-v1.wasm`. Rebuild both
diagnostic BIOS images with `tools/build-firmware.ps1 -Clang <clang-path>`.
Only the compiled WASM image is loaded by Chicory.
