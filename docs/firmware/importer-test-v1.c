/* OpenWAC importer diagnostic BIOS. Prints file contents, never executes them.
 * Freestanding WASM; rebuild with tools/build-firmware.ps1.
 */
typedef unsigned char u8;
typedef unsigned int u32;
typedef unsigned long long u64;
typedef int i32;
_Static_assert(sizeof(u32) == 4 && sizeof(u64) == 8, "wasm32 integer widths");
#define IMPORT(name) __attribute__((import_module("hal"), import_name(#name)))
IMPORT(hal_device_count) extern i32 hal_device_count(void);
IMPORT(hal_device_info) extern i32 hal_device_info(i32 index, u8 *out, i32 capacity);
IMPORT(hal_device_call) extern i32 hal_device_call(const u8 *uuid, i32 op,
        const u8 *args, i32 length, u8 *out, i32 capacity);
IMPORT(hal_chat_print) extern i32 hal_chat_print(const char *text, i32 length);
IMPORT(hal_yield) extern void hal_yield(void);

enum { FILE_SIZE = 0, READ_BLOCK = 1, FILE_NAME = 2, FILE_NAME_LENGTH = 3 };
static u8 uuid[16], descriptor[20], metadata[8], request[4], block[256], filename[1024];
static char message[512];
static u32 used;
static const char hex[] = "0123456789abcdef";

static u32 get32(const u8 *p) {
    return (u32)p[0] | ((u32)p[1] << 8) | ((u32)p[2] << 16) | ((u32)p[3] << 24);
}
static u64 get64(const u8 *p) { return (u64)get32(p) | ((u64)get32(p + 4) << 32); }
static void put32(u8 *p, u32 n) { for (u32 i = 0; i < 4; ++i) p[i] = (u8)(n >> (8 * i)); }
static void append(const char *s) { while (*s && used < sizeof(message)) message[used++] = *s++; }
static void number(u64 n) {
    char digits[20]; u32 count = 0;
    do { digits[count++] = (char)('0' + n % 10); n /= 10; } while (n);
    while (count && used < sizeof(message)) message[used++] = digits[--count];
}
static void print(void) {
    i32 status;
    do { status = hal_chat_print(message, (i32)used); if (status == -11) hal_yield(); } while (status == -11);
    used = 0;
}
static void say(const char *s) { used = 0; append(s); print(); }
static void error(const char *stage, i32 status) {
    used = 0; append("IMPORTER FAIL: "); append(stage); append(" (HAL ");
    if (status < 0) { append("-"); number(0u - (u32)status); } else number((u32)status);
    append(")"); print();
}
static i32 call(u32 operation, u32 arg_length, u8 *output, u32 capacity) {
    return hal_device_call(uuid, (i32)operation, request, (i32)arg_length, output, (i32)capacity);
}

// Streaming text renderer: retain UTF-8 and CRLF state across device blocks.
// Binary/control/invalid UTF-8 bytes are escaped, so nothing silently disappears.
static u8 utf8[4];
static u32 utf8_length, utf8_expected;
static i32 pending_cr;
static void begin_line(void) { used = 0; append("| "); }
static void emit(const u8 *bytes, u32 count) {
    if (used + count > sizeof(message)) { print(); begin_line(); }
    for (u32 i = 0; i < count; ++i) message[used++] = (char)bytes[i];
}
static void escape(u8 byte) {
    u8 text[] = {'\\', 'x', (u8)hex[byte >> 4], (u8)hex[byte & 15]};
    emit(text, 4);
}
static void unfinished_utf8(void) {
    for (u32 i = 0; i < utf8_length; ++i) escape(utf8[i]);
    utf8_length = utf8_expected = 0;
}
static void consume(u8 byte) {
    if (pending_cr) {
        pending_cr = 0;
        if (byte == '\n') { print(); begin_line(); return; }
        escape('\r');
    }
    if (utf8_length) {
        if ((byte & 0xc0) == 0x80) {
            utf8[utf8_length++] = byte;
            if (utf8_length == utf8_expected) {
                i32 valid = !(utf8[0] == 0xe0 && utf8[1] < 0xa0)
                        && !(utf8[0] == 0xed && utf8[1] >= 0xa0)
                        && !(utf8[0] == 0xf0 && utf8[1] < 0x90)
                        && !(utf8[0] == 0xf4 && utf8[1] >= 0x90);
                // Minecraft sanitizes the formatting marker and Unicode control bytes.
                if (utf8[0] == 0xc2 && utf8[1] <= 0x9f) valid = 0;
                if (utf8[0] == 0xc2 && utf8[1] == 0xa7) valid = 0;
                if (valid) { emit(utf8, utf8_length); utf8_length = utf8_expected = 0; }
                else unfinished_utf8();
            }
            return;
        }
        unfinished_utf8(); // Retry this non-continuation byte as a fresh character.
    }
    if (byte == '\n') { print(); begin_line(); }
    else if (byte == '\r') pending_cr = 1;
    else if (byte >= 32 && byte <= 126) emit(&byte, 1);
    else if (byte >= 0xc2 && byte <= 0xf4) {
        utf8[0] = byte; utf8_length = 1;
        utf8_expected = byte < 0xe0 ? 2 : byte < 0xf0 ? 3 : 4;
    } else escape(byte);
}
static void finish_text(void) {
    if (pending_cr) { escape('\r'); pending_cr = 0; }
    unfinished_utf8();
    if (used > 2) print();
    used = 0;
}
static i32 show_file(void) {
    used = 0; append("Importer ");
    for (u32 i = 0; i < 16; ++i) {
        if (i == 4 || i == 6 || i == 8 || i == 10) append("-");
        message[used++] = hex[uuid[i] >> 4]; message[used++] = hex[uuid[i] & 15];
    }
    print();
    i32 status = call(FILE_NAME_LENGTH, 0, metadata, 4);
    if (status != 0) { error("FILE_NAME_LENGTH", status); return 0; }
    u32 name_length = get32(metadata);
    if (name_length == 0 || name_length > sizeof(filename)) { say("IMPORTER FAIL: filename exceeds BIOS buffer or is empty."); return 0; }
    status = call(FILE_NAME, 0, filename, name_length);
    if (status != 0) { error("FILE_NAME", status); return 0; }
    used = 0; append("File: ");
    for (u32 i = 0; i < name_length; ++i) {
        u8 byte = filename[i];
        if (byte < 32 || byte > 126) escape(byte); else emit(&byte, 1);
    }
    print();
    status = call(FILE_SIZE, 0, metadata, 8);
    if (status != 0) { error("FILE_SIZE", status); return 0; }
    u64 size = get64(metadata);
    // READ_BLOCK uses a u32 block index. Check before converting to avoid wrapping.
    if (size > ((u64)0x100000000ULL * 256)) { say("IMPORTER FAIL: file exceeds block-index range."); return 0; }
    used = 0; append("Contents ("); number(size); append(" bytes):"); print();
    utf8_length = utf8_expected = 0; pending_cr = 0; begin_line();
    for (u64 offset = 0; offset < size; offset += 256) {
        u32 count = size - offset < 256 ? (u32)(size - offset) : 256;
        put32(request, (u32)(offset / 256));
        status = call(READ_BLOCK, 4, block, count);
        if (status != 0) { finish_text(); error("READ_BLOCK", status); return 0; }
        for (u32 i = 0; i < count; ++i) consume(block[i]);
    }
    finish_text();
    if (size == 0) say("(empty file)");
    say("IMPORTER PASS: entire file read and printed.");
    return 1;
}
__attribute__((export_name("_start"))) void _start(void) {
    say("OpenWAC file importer test BIOS v1.");
    i32 count = hal_device_count(); u32 found = 0, passed = 0, failed = 0;
    if (count < 0 || count > 64) { error("device count", count); failed = 1; }
    else for (i32 index = 0; index < count; ++index) {
        i32 status = hal_device_info(index, descriptor, sizeof(descriptor));
        if (status != 0) { error("device discovery", status); ++failed; continue; }
        if (get32(descriptor) != 0x0200) continue;
        for (u32 i = 0; i < 16; ++i) uuid[i] = descriptor[i + 4];
        ++found;
        if (show_file()) ++passed; else ++failed;
    }
    if (!found) say("NO IMPORTER: upload a file, power off, install the importer, then boot again.");
    used = 0; append("IMPORTER TEST COMPLETE: "); number(passed); append(" passed, "); number(failed); append(" failed."); print();
    for (;;) hal_yield();
}
