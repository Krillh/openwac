# TTD diagnostic BIOS images

Two additional EEPROMs are available in the OpenWAC creative tab. Both contain
compiled WASM that uses the normal HAL, device UUIDs, and status-only calls.
They overwrite the screen contents; use a screen whose current image you do
not need to retain. Neither image executes imported programs.

## BIOS EEPROM (TTD Write Test)

1. Restart the game with the updated mod. Place a **Tiny Text Display** directly
   beside, above, or below a computer case.
2. Power off the case, install **BIOS EEPROM (TTD Write Test)** in the dedicated
   BIOS slot, and a **Peripheral Port** in a component slot. Exactly one BIOS
   must be installed.
3. Empty-hand shift-right-click the case to power on. Stay nearby to see chat
   diagnostics and right-click the screen for the enlarged view.
4. Expect a box-drawing border, a chart containing all 256 CP437 byte glyphs,
   PASS labels and a blinking cursor. On the current 60x30 screen the glyphs
   occupy five rows, with 58 glyphs per row except the last.
5. Chat should report `TTD TEST PASS` and finish with
   `TTD TEST COMPLETE: N passed, M failed.`

Every discovered TTD is tested separately. The firmware queries geometry,
fills and reads back spaces, writes and verifies a deterministic byte pattern,
checks horizontal and vertical overlapping COPY, sets and reads back the
cursor, and checks out-of-bounds writes (`-10`), truncated writes (`-4`), and
short query response buffers (`-5`). It confirms rejected operations did not
change cell memory, then writes and verifies the final visual chart. Visual
correctness of the atlas, orientation and rendering is judged in-game; byte
readback alone cannot establish that the client renders the right glyphs.

Failures are reported in chat with the stage and HAL code, and testing
continues with other screens. No attached screen produces `NO TTD`.
Source: [ttd-test-v1.c](./ttd-test-v1.c).

## BIOS EEPROM (File Importer to TTD)

1. Create a small ASCII or CP437 text file on your own computer. For example:

   ```text
   Hello from the file importer!
   This line is printed on TTD.
   ```

2. Hold a **File Importer**, right-click in the air, enter the absolute local
   path, and press **Upload**. Wait for confirmation.
3. Power off the case. Install that importer and a **Peripheral Port** in
   component slots, and **BIOS EEPROM (File Importer to TTD)** in the dedicated
   BIOS slot. Keep a TTD adjacent to the case.
4. Power on. The screen shows a title, filename, and imported contents. Its
   bottom row reports progress/completion. Chat reports
   `IMPORTER -> TTD PASS` for each file and ends with
   `IMPORTER -> TTD COMPLETE: N passed, M failed.`

The first suitable TTD in UUID enumeration order is used. All discovered file
importers are read individually, with filename separators between their
contents. The firmware reads filename length, filename, and 64-bit file size,
then reads 256-byte blocks, using the exact remaining byte count on the last
block. Empty files print `(empty file)`. A failed importer is reported and the
firmware continues to the next importer; a display failure stops further output.

The guest implements wrapping and scrolling using WRITE_CELLS, COPY and FILL.
LF advances to the next line; CR returns to column zero (including CRLF split
across blocks); tabs expand to four-column stops. Every other byte directly
selects its CP437 glyph. There is no UTF-8 decoding: ASCII is suitable for an
initial test, and extended characters should be encoded as CP437. Binary WASM
files appear as glyphs rather than being executed.

The title and filename occupy the first two rows; the last row is reserved for
status. Longer files scroll the body, leaving the final portion visible. There
is no keyboard input or retained scrollback in this diagnostic. Device calls
remain bounded and the firmware yields between file blocks. At completion it
sets the cursor and verifies the final cell contents through READ_CELLS.

No importer produces a visible instruction and a chat diagnostic. No usable
display produces a chat diagnostic. To rerun, power-cycle the case; to display
a recompiled/changed file, upload it again first.
Source: [importer-ttd-test-v1.c](./importer-ttd-test-v1.c).

## Build

`tools/build-firmware.ps1 -Clang <path-to-clang>` rebuilds all four C diagnostic
images. These two use [ttd-firmware.h](./ttd-firmware.h) for shared HAL helpers.
Compiled resources are `data/openwac/firmware/ttd-test-v1.wasm` and
`data/openwac/firmware/importer-ttd-test-v1.wasm`. Each EEPROM references its
bundled image rather than storing another copy of the binary in item data.
The firmware retains its final image and yields indefinitely after completion.
