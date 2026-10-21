/* Imports CP437/ASCII text through interface 0x0200 and renders it through TTD 0x0400. */
#include "ttd-firmware.h"
static u8 importer[16], file_block[256], file_info[8], file_name[1024], file_request[4];
static u32 cursor_x, cursor_y;
static i32 output_broken;
static i32 file_call(u32 op, u32 arg_length, u8 *out, u32 capacity) {
    return hal_device_call(importer, (i32)op, file_request, (i32)arg_length, out, (i32)capacity);
}
static i32 output_status(i32 status, const char *stage) {
    if (status != 0 && !output_broken) { output_broken = 1; log_error(stage, status); }
    return status == 0;
}
static void flush_line(void) {
    if (!output_broken) output_status(write_rect(0, cursor_y, columns, 1), "file display WRITE_CELLS");
}
static void newline(void) {
    flush_line(); cursor_x = 0;
    if (++cursor_y >= rows - 1) {
        cursor_y = rows - 2;
        if (!output_broken && output_status(copy_rect(0, 3, columns, rows - 4, 0, 2), "file display scroll COPY"))
            output_status(fill_rect(0, cursor_y, columns, 1, ' '), "file display scroll FILL");
    }
}
static void character(u8 byte) {
    if (output_broken) return;
    if (byte == '\r') { cursor_x = 0; return; }
    if (byte == '\n') { newline(); return; }
    if (byte == '\t') {
        u32 count = 4 - (cursor_x % 4);
        for (u32 i = 0; i < count; ++i) character(' ');
        return;
    }
    if (cursor_x == columns) newline();
    cells[cursor_y * columns + cursor_x++] = byte;
}
static void text(const char *s) { while (*s && !output_broken) character((u8)*s++); }
static void status_line(const char *s) {
    for (u32 x = 0; x < columns; ++x) cells[(rows - 1) * columns + x] = ' ';
    label(0, rows - 1, s);
    if (!output_broken) output_status(write_rect(0, rows - 1, columns, 1), "status WRITE_CELLS");
}
static i32 show_file(void) {
    log_uuid("Reading importer ", importer);
    i32 status = file_call(3, 0, file_info, 4);
    if (status != 0) { log_error("FILE_NAME_LENGTH", status); return 0; }
    u32 name_length = get32(file_info);
    if (!name_length || name_length > sizeof(file_name)) { log_text("FAIL: unsupported filename length."); return 0; }
    status = file_call(2, 0, file_name, name_length);
    if (status != 0) { log_error("FILE_NAME", status); return 0; }
    status = file_call(0, 0, file_info, 8);
    if (status != 0) { log_error("FILE_SIZE", status); return 0; }
    u64 size = get64(file_info);
    if (size > (u64)0x100000000ULL * 256) { log_text("FAIL: file exceeds importer block-index range."); return 0; }
    for (u32 x = 0; x < columns; ++x) cells[columns + x] = ' ';
    for (u32 x = 0; x < name_length && x < columns; ++x) cells[columns + x] = file_name[x];
    if (!output_status(write_rect(0, 1, columns, 1), "filename WRITE_CELLS")) return 0;
    if (cursor_y != 2 || cursor_x != 0) newline();
    text("--- "); for (u32 i = 0; i < name_length; ++i) character(file_name[i]); text(" ---\n");
    status_line("Reading file...");
    for (u64 offset = 0; offset < size && !output_broken; offset += 256) {
        u32 count = size - offset < 256 ? (u32)(size - offset) : 256;
        put32(file_request, (u32)(offset / 256));
        status = file_call(1, 4, file_block, count);
        if (status != 0) { flush_line(); log_error("importer READ_BLOCK", status); text("\n[READ FAILED]\n"); return 0; }
        for (u32 i = 0; i < count && !output_broken; ++i) character(file_block[i]);
        flush_line(); hal_yield();
    }
    if (size == 0) text("(empty file)");
    flush_line();
    if (output_broken) return 0;
    log_text("IMPORTER -> TTD PASS: entire file read and written to the screen.");
    return 1;
}
__attribute__((export_name("_start"))) void _start(void) {
    log_text("OpenWAC importer-to-TTD BIOS v1. Files are displayed as CP437 text.");
    i32 count = hal_device_count(), found_screen = 0; u32 found = 0, passed = 0, failed = 0;
    if (count < 0 || count > 64) { log_error("device count", count); idle(); }
    for (i32 index = 0; index < count; ++index) {
        i32 status = hal_device_info(index, descriptor, sizeof(descriptor));
        if (status != 0) { log_error("screen discovery", status); continue; }
        if (get32(descriptor) == 0x0400) {
            bytes_copy(screen, descriptor + 4, 16);
            if (screen_geometry()) { found_screen = 1; break; }
        }
    }
    if (!found_screen) { log_text("NO TTD: install a Peripheral Port, place a screen adjacent to the case, and reboot."); idle(); }
    log_uuid("Using TTD ", screen);
    if (!output_status(fill_rect(0, 0, columns, rows, ' '), "initial screen FILL")) idle();
    if (!output_status(set_cursor(0, 2, 0, 0), "initial SET_CURSOR")) idle();
    label(0, 0, "OpenWAC File Importer -> TTD");
    if (!output_status(write_rect(0, 0, columns, 1), "title WRITE_CELLS")) idle();
    cursor_x = 0; cursor_y = 2;
    for (i32 index = 0; index < count && !output_broken; ++index) {
        i32 status = hal_device_info(index, descriptor, sizeof(descriptor));
        if (status != 0) { log_error("importer discovery", status); ++failed; continue; }
        if (get32(descriptor) != 0x0200) continue;
        bytes_copy(importer, descriptor + 4, 16); ++found;
        if (show_file()) ++passed;
        else { ++failed; text("\n[IMPORTER FAILED]\n"); flush_line(); }
    }
    if (!found) { text("No file importer installed.\nUpload a file and reboot."); flush_line(); log_text("NO IMPORTER: upload a file, install the importer and reboot."); }
    status_line(failed || output_broken ? "DONE: failures (see chat)" : !found ? "DONE: no importer" : "DONE: all files displayed");
    if (!output_broken) {
        i32 status = set_cursor(cursor_x < columns ? cursor_x : columns - 1, cursor_y, 1, 1);
        if (!output_status(status, "final SET_CURSOR")) ++failed;
        else if (!verify_cells()) ++failed;
    }
    summary("IMPORTER -> TTD COMPLETE: ", passed, failed); idle();
}
