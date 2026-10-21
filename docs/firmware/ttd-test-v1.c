/* Screen write/readback diagnostic. Deliberately replaces existing screen contents. */
#include "ttd-firmware.h"
static i32 test_screen(void) {
    log_uuid("Testing TTD ", screen);
    if (!screen_geometry()) return 0;
    i32 status = fill_rect(0, 0, columns, rows, ' ');
    if (status != 0) { log_error("TTD FILL", status); return 0; }
    if (!verify_cells()) return 0;
    for (u32 i = 0; i < columns * rows; ++i) cells[i] = (u8)(i * 37u + 11u);
    status = write_rect(0, 0, columns, rows);
    if (status != 0) { log_error("TTD WRITE_CELLS", status); return 0; }
    if (!verify_cells()) return 0;
    status = copy_rect(1, 1, columns - 3, 1, 2, 1);
    if (status != 0) { log_error("horizontal overlapping COPY", status); return 0; }
    if (!verify_cells()) return 0;
    status = copy_rect(1, 2, columns - 2, rows - 3, 1, 1);
    if (status != 0) { log_error("vertical overlapping COPY", status); return 0; }
    if (!verify_cells()) return 0;
    status = set_cursor(2, rows - 2, 1, 1);
    if (status != 0) { log_error("TTD SET_CURSOR", status); return 0; }
    status = screen_call(GET_CURSOR, 0, 10);
    if (status != 0) { log_error("TTD GET_CURSOR", status); return 0; }
    if (get32(response) != 2 || get32(response + 4) != rows - 2 || response[8] != 1 || response[9] != 1) {
        log_text("FAIL: TTD cursor readback mismatch."); return 0;
    }
    rect(columns, 0, 1, 1); request[16] = '!';
    status = screen_call(WRITE_CELLS, 17, 0);
    if (status != -10) { log_error("out-of-bounds WRITE expected -10", status); return 0; }
    rect(0, 0, 1, 1);
    status = screen_call(WRITE_CELLS, 16, 0);
    if (status != -4) { log_error("short WRITE expected -4", status); return 0; }
    status = screen_call(GEOMETRY, 0, 7);
    if (status != -5) { log_error("short GEOMETRY response expected -5", status); return 0; }
    if (!verify_cells()) return 0;
    // Leave a stable, readable visual result rather than only transient patterns.
    status = fill_rect(0, 0, columns, rows, ' ');
    if (status != 0) { log_error("final FILL", status); return 0; }
    for (u32 x = 1; x < columns - 1; ++x) { cells[x] = 0xc4; cells[(rows - 1) * columns + x] = 0xc4; }
    for (u32 y = 1; y < rows - 1; ++y) { cells[y * columns] = 0xb3; cells[y * columns + columns - 1] = 0xb3; }
    cells[0] = 0xda; cells[columns - 1] = 0xbf;
    cells[(rows - 1) * columns] = 0xc0; cells[rows * columns - 1] = 0xd9;
    label(2, 1, "OpenWAC TTD WRITE TEST");
    // On the implemented 60x30 screen all 256 byte glyphs fit in rows 3 through 7.
    u32 glyph_columns = columns - 2, glyph_rows = rows - 8;
    for (u32 code = 0; code < 256 && code / glyph_columns < glyph_rows; ++code)
        cells[(3 + code / glyph_columns) * columns + 1 + code % glyph_columns] = (u8)code;
    label(2, rows - 5, "FILL / WRITE / READ: PASS");
    label(2, rows - 4, "COPY / CURSOR: PASS");
    label(2, rows - 3, "BOUNDS: PASS");
    label(4, rows - 2, "TTD TEST PASS");
    status = write_rect(0, 0, columns, rows);
    if (status != 0) { log_error("final chart WRITE", status); return 0; }
    if (!verify_cells()) return 0;
    log_text("TTD TEST PASS: fill, write/readback, overlapping copy, cursor and bounds.");
    return 1;
}
__attribute__((export_name("_start"))) void _start(void) {
    log_text("OpenWAC TTD write test BIOS v1.");
    i32 count = hal_device_count(); u32 found = 0, passed = 0, failed = 0;
    if (count < 0 || count > 64) { log_error("device count", count); failed = 1; }
    else for (i32 index = 0; index < count; ++index) {
        i32 status = hal_device_info(index, descriptor, sizeof(descriptor));
        if (status != 0) { log_error("discovery", status); ++failed; continue; }
        if (get32(descriptor) != 0x0400) continue;
        bytes_copy(screen, descriptor + 4, 16); ++found;
        if (test_screen()) ++passed; else ++failed;
    }
    if (!found) log_text("NO TTD: install a Peripheral Port, place a screen adjacent to the case, and reboot.");
    summary("TTD TEST COMPLETE: ", passed, failed); idle();
}
