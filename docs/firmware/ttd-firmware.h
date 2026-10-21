/* Shared freestanding helpers for the TTD diagnostic BIOS images. */
#ifndef OPENWAC_TTD_FIRMWARE_H
#define OPENWAC_TTD_FIRMWARE_H
typedef unsigned char u8;
typedef unsigned int u32;
typedef unsigned long long u64;
typedef int i32;
_Static_assert(sizeof(u32) == 4 && sizeof(u64) == 8, "wasm32 integer widths");
#define IMPORT(name) __attribute__((import_module("hal"), import_name(#name)))
IMPORT(hal_device_count) extern i32 hal_device_count(void);
IMPORT(hal_device_info) extern i32 hal_device_info(i32 index, u8 *out, i32 capacity);
IMPORT(hal_device_call) extern i32 hal_device_call(const u8 *uuid, i32 operation,
        const u8 *args, i32 length, u8 *out, i32 capacity);
IMPORT(hal_chat_print) extern i32 hal_chat_print(const char *text, i32 length);
IMPORT(hal_yield) extern void hal_yield(void);
enum { MAX_CELLS = 4096, GEOMETRY = 0, READ_CELLS = 0x10, WRITE_CELLS = 0x20,
       FILL = 0x21, COPY = 0x22, SET_CURSOR = 0x30, GET_CURSOR = 0x31 };
static u8 screen[16], descriptor[20], request[16 + MAX_CELLS], response[MAX_CELLS], cells[MAX_CELLS];
static u32 columns, rows;
static char diagnostic[256];
static u32 diagnostic_length;
static void bytes_copy(u8 *out, const u8 *in, u32 count) { for (u32 i = 0; i < count; ++i) out[i] = in[i]; }
static i32 bytes_equal(const u8 *a, const u8 *b, u32 count) {
    for (u32 i = 0; i < count; ++i) if (a[i] != b[i]) return 0;
    return 1;
}
static u32 get32(const u8 *p) { return (u32)p[0] | ((u32)p[1] << 8) | ((u32)p[2] << 16) | ((u32)p[3] << 24); }
static u64 get64(const u8 *p) { return get32(p) | ((u64)get32(p + 4) << 32); }
static void put32(u8 *p, u32 n) { for (u32 i = 0; i < 4; ++i) p[i] = (u8)(n >> (8 * i)); }
static void log_append(const char *s) { while (*s && diagnostic_length < sizeof(diagnostic)) diagnostic[diagnostic_length++] = *s++; }
static void log_number(u64 n) {
    char digits[20]; u32 count = 0;
    do { digits[count++] = (char)('0' + n % 10); n /= 10; } while (n);
    while (count && diagnostic_length < sizeof(diagnostic)) diagnostic[diagnostic_length++] = digits[--count];
}
static void log_print(void) {
    while (hal_chat_print(diagnostic, (i32)diagnostic_length) == -11) hal_yield();
    diagnostic_length = 0;
}
static void log_text(const char *s) { diagnostic_length = 0; log_append(s); log_print(); }
static void log_error(const char *stage, i32 status) {
    diagnostic_length = 0; log_append("FAIL: "); log_append(stage); log_append(" (HAL ");
    if (status < 0) { log_append("-"); log_number(0u - (u32)status); } else log_number((u32)status);
    log_append(")"); log_print();
}
static void log_uuid(const char *label, const u8 *id) {
    static const char hex[] = "0123456789abcdef";
    diagnostic_length = 0; log_append(label);
    for (u32 i = 0; i < 16; ++i) {
        if (i == 4 || i == 6 || i == 8 || i == 10) log_append("-");
        diagnostic[diagnostic_length++] = hex[id[i] >> 4]; diagnostic[diagnostic_length++] = hex[id[i] & 15];
    }
    log_print();
}
static i32 screen_call(u32 operation, u32 length, u32 capacity) {
    return hal_device_call(screen, (i32)operation, request, (i32)length, response, (i32)capacity);
}
static void rect(u32 x, u32 y, u32 width, u32 height) {
    put32(request, x); put32(request + 4, y); put32(request + 8, width); put32(request + 12, height);
}
static i32 screen_geometry(void) {
    i32 status = screen_call(GEOMETRY, 0, 8);
    if (status != 0) { log_error("TTD GEOMETRY", status); return 0; }
    columns = get32(response); rows = get32(response + 4);
    if (columns < 16 || columns > 128 || rows < 14 || rows > 64 || columns * rows > MAX_CELLS) {
        log_text("FAIL: unsupported TTD geometry for diagnostic BIOS."); return 0;
    }
    return 1;
}
static i32 write_rect(u32 x, u32 y, u32 width, u32 height) {
    rect(x, y, width, height);
    for (u32 row = 0; row < height; ++row)
        bytes_copy(request + 16 + row * width, cells + (y + row) * columns + x, width);
    return screen_call(WRITE_CELLS, 16 + width * height, 0);
}
static i32 fill_rect(u32 x, u32 y, u32 width, u32 height, u8 value) {
    rect(x, y, width, height); request[16] = value;
    i32 status = screen_call(FILL, 17, 0);
    if (status == 0) for (u32 row = 0; row < height; ++row) for (u32 col = 0; col < width; ++col)
        cells[(y + row) * columns + x + col] = value;
    return status;
}
static i32 copy_rect(u32 x, u32 y, u32 width, u32 height, u32 target_x, u32 target_y) {
    rect(x, y, width, height); put32(request + 16, target_x); put32(request + 20, target_y);
    i32 status = screen_call(COPY, 24, 0);
    if (status == 0) {
        for (u32 row = 0; row < height; ++row) bytes_copy(response + row * width, cells + (y + row) * columns + x, width);
        for (u32 row = 0; row < height; ++row) bytes_copy(cells + (target_y + row) * columns + target_x, response + row * width, width);
    }
    return status;
}
static i32 verify_cells(void) {
    rect(0, 0, columns, rows);
    i32 status = screen_call(READ_CELLS, 16, columns * rows);
    if (status != 0) { log_error("TTD READ_CELLS", status); return 0; }
    if (!bytes_equal(cells, response, columns * rows)) { log_text("FAIL: TTD cell readback differs from written bytes."); return 0; }
    return 1;
}
static i32 set_cursor(u32 x, u32 y, i32 visible, i32 blink) {
    put32(request, x); put32(request + 4, y); request[8] = (u8)visible; request[9] = (u8)blink;
    return screen_call(SET_CURSOR, 10, 0);
}
static void label(u32 x, u32 y, const char *text) {
    while (*text && x < columns) cells[y * columns + x++] = (u8)*text++;
}
static void summary(const char *label_text, u32 passed, u32 failed) {
    diagnostic_length = 0; log_append(label_text); log_number(passed); log_append(" passed, "); log_number(failed); log_append(" failed."); log_print();
}
static void idle(void) { for (;;) hal_yield(); }
#endif
