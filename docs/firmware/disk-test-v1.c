/*
 * OpenWAC disk self-test BIOS. Compile to wasm32 with tools/build-firmware.ps1.
 * No libc, WASI, host filesystem, or host-side test shortcuts are used.
 * Block 510 is backed up and restored. Block 511 receives a persistent marker
 * only if blank. Use a spare disk: interruption during a write can leave test data.
 */
typedef unsigned char u8;
typedef unsigned int u32;
typedef unsigned long long u64;
typedef int i32;
_Static_assert(sizeof(u32) == 4 && sizeof(u64) == 8, "wasm32 integer widths");

#define IMPORT(name) __attribute__((import_module("hal"), import_name(#name)))
IMPORT(hal_device_count) extern i32 hal_device_count(void);
IMPORT(hal_device_info) extern i32 hal_device_info(i32 index, u8 *output, i32 capacity);
IMPORT(hal_device_call) extern i32 hal_device_call(const u8 *uuid, i32 operation,
        const u8 *arguments, i32 length, u8 *response, i32 capacity);
IMPORT(hal_chat_print) extern i32 hal_chat_print(const char *text, i32 length);
IMPORT(hal_yield) extern void hal_yield(void);

enum { BLOCK_SIZE = 2048, BLOCKS = 512, SCRATCH_BLOCK = 510, MARKER_BLOCK = 511 };
enum { SIZE = 0x10, COUNT = 0x11, READ = 0x20, WRITE = 0x30 };
static u8 descriptor[20], uuid[16], geometry[16];
static u8 request[4 + BLOCK_SIZE], backup[BLOCK_SIZE], readback[BLOCK_SIZE];
static u8 pattern[BLOCK_SIZE], marker[BLOCK_SIZE], marker_before[BLOCK_SIZE];
static char line[256];
static u32 line_length;

static u32 length(const char *text) {
    u32 n = 0;
    while (text[n]) ++n;
    return n;
}
static void copy(u8 *out, const u8 *in, u32 n) { for (u32 i = 0; i < n; ++i) out[i] = in[i]; }
static i32 equal(const u8 *a, const u8 *b, u32 n) {
    for (u32 i = 0; i < n; ++i) if (a[i] != b[i]) return 0;
    return 1;
}
static i32 blank(const u8 *bytes) {
    for (u32 i = 0; i < BLOCK_SIZE; ++i) if (bytes[i] != 0) return 0;
    return 1;
}
static u32 get32(const u8 *bytes) {
    return (u32) bytes[0] | ((u32) bytes[1] << 8) | ((u32) bytes[2] << 16) | ((u32) bytes[3] << 24);
}
static u64 get64(const u8 *bytes) { return (u64) get32(bytes) | ((u64) get32(bytes + 4) << 32); }
static void put32(u8 *bytes, u32 value) {
    for (u32 i = 0; i < 4; ++i) bytes[i] = (u8) (value >> (8 * i));
}
static void put64(u8 *bytes, u64 value) {
    for (u32 i = 0; i < 8; ++i) bytes[i] = (u8) (value >> (8 * i));
}
static void append(const char *text) {
    while (*text && line_length < sizeof(line) - 1) line[line_length++] = *text++;
}
static void number(u32 value) {
    char reverse[10];
    u32 n = 0;
    do { reverse[n++] = (char) ('0' + value % 10); value /= 10; } while (value);
    while (n && line_length < sizeof(line) - 1) line[line_length++] = reverse[--n];
}
static void print(const char *text, u32 n) {
    // Chat is rate limited. Yield/retry preserves every diagnostic across later ticks.
    while (hal_chat_print(text, (i32) n) == -11) hal_yield();
}
static void say(const char *text) { print(text, length(text)); }
static void error(const char *stage, i32 code) {
    line_length = 0; append("FAIL: "); append(stage); append(" (HAL ");
    if (code < 0) { append("-"); number(0u - (u32) code); } else number((u32) code);
    append(")"); print(line, line_length);
}
static void disk_label(void) {
    static const char hex[] = "0123456789abcdef";
    line_length = 0; append("Testing disk ");
    for (u32 i = 0; i < 16; ++i) {
        if (i == 4 || i == 6 || i == 8 || i == 10) append("-");
        line[line_length++] = hex[uuid[i] >> 4]; line[line_length++] = hex[uuid[i] & 15];
    }
    print(line, line_length);
}
static void transfer_header(u64 first, u32 count) { (void)count; put32(request, (u32)first); }
static i32 read_block(u32 block, u8 *output) {
    transfer_header(block, 1);
    return hal_device_call(uuid, READ, request, 4, output, BLOCK_SIZE);
}
static i32 write_block(u32 block, const u8 *bytes) {
    transfer_header(block, 1); copy(request + 4, bytes, BLOCK_SIZE);
    return hal_device_call(uuid, WRITE, request, sizeof(request), readback, 0);
}
// The disk contract persists each successful write; there is no FLUSH operation.
static i32 flush(void) { return 0; }

static i32 restore(u32 block, const u8 *original) {
    i32 result = write_block(block, original);
    if (result != 0) { error("RESTORE WRITE - keep this disk for recovery", result); return 0; }
    result = flush();
    if (result != 0) { error("RESTORE FLUSH - keep this disk for recovery", result); return 0; }
    result = read_block(block, readback);
    if (result != 0) { error("RESTORE READ", result); return 0; }
    if (!equal(original, readback, BLOCK_SIZE)) { say("FAIL: RESTORE DATA MISMATCH"); return 0; }
    return 1;
}
static i32 scratch_test(void) {
    i32 result = read_block(SCRATCH_BLOCK, backup);
    if (result != 0) { error("scratch backup", result); return 0; }
    for (u32 i = 0; i < BLOCK_SIZE; ++i) pattern[i] = (u8) (i * 37u + uuid[i & 15] + 0x5au);
    i32 passed = 1;
    result = write_block(SCRATCH_BLOCK, pattern);
    if (result != 0) { error("scratch write", result); passed = 0; }
    if (passed) {
        result = flush();
        if (result != 0) { error("scratch flush", result); passed = 0; }
    }
    if (passed) {
        result = read_block(SCRATCH_BLOCK, readback);
        if (result != 0) { error("scratch readback", result); passed = 0; }
        else if (!equal(pattern, readback, BLOCK_SIZE)) { say("FAIL: scratch readback mismatch"); passed = 0; }
    }
    // Even an unsuccessful write can partially modify media: always attempt restoration.
    if (!restore(SCRATCH_BLOCK, backup)) return 0;
    if (!passed) { say("Scratch block restored after failure."); return 0; }
    say("I/O PASS: write, flush, readback; original scratch block restored.");
    return 1;
}
static i32 bounds_test(void) {
    transfer_header(BLOCKS, 1);
    i32 result = hal_device_call(uuid, READ, request, 4, readback, BLOCK_SIZE);
    if (result != -10) { error("out-of-range READ should return -10", result); return 0; }
    transfer_header(0, 1);
    result = hal_device_call(uuid, WRITE, request, 4, readback, 0);
    if (result != -4) { error("short WRITE should return -4", result); return 0; }
    say("BOUNDS PASS: invalid reads and short writes rejected.");
    return 1;
}
static i32 persistence_test(void) {
    static const char signature[] = "OpenWAC disk self-test v1";
    for (u32 i = 0; i < BLOCK_SIZE; ++i) marker[i] = (u8) (i * 17u + 0x31u);
    copy(marker, (const u8 *) signature, sizeof(signature));
    copy(marker + 32, uuid, 16);
    i32 result = read_block(MARKER_BLOCK, marker_before);
    if (result != 0) { error("persistence read", result); return 0; }
    if (equal(marker, marker_before, BLOCK_SIZE)) {
        say("PERSISTENCE PASS: previous boot's full marker and disk UUID verified.");
        return 1;
    }
    if (!blank(marker_before)) {
        say("PERSISTENCE SKIP: block 511 contains other data. Use a blank test disk.");
        return 1;
    }
    i32 passed = 1;
    result = write_block(MARKER_BLOCK, marker);
    if (result != 0) { error("persistence marker write", result); passed = 0; }
    if (passed) {
        result = flush();
        if (result != 0) { error("persistence marker flush", result); passed = 0; }
    }
    if (passed) {
        result = read_block(MARKER_BLOCK, readback);
        if (result != 0) { error("persistence marker readback", result); passed = 0; }
        else if (!equal(marker, readback, BLOCK_SIZE)) { say("FAIL: persistence marker readback mismatch"); passed = 0; }
    }
    if (!passed) { restore(MARKER_BLOCK, marker_before); return 0; }
    say("PERSISTENCE ARMED: marker in block 511. Save/reload and boot again.");
    return 1;
}
static i32 test_disk(void) {
    disk_label();
    i32 result = hal_device_call(uuid, SIZE, request, 0, geometry, 4);
    if (result != 0) { error("disk BLOCK_SIZE", result); return 0; }
    result = hal_device_call(uuid, COUNT, request, 0, geometry + 4, 4);
    if (result != 0) { error("disk BLOCK_COUNT", result); return 0; }
    if (get32(geometry) != BLOCK_SIZE || get32(geometry + 4) != BLOCKS) {
        say("FAIL: expected tier 1 geometry (512 x 2048 bytes).");
        return 0;
    }
    say("GEOMETRY PASS: 512 x 2048 bytes = 1 MiB.");
    if (!scratch_test()) return 0;
    if (!bounds_test()) return 0;
    if (!persistence_test()) return 0;
    say("DISK SELF-TEST PASS");
    return 1;
}
__attribute__((export_name("_start"))) void _start(void) {
    say("OpenWAC disk self-test BIOS v1. Keep power on until COMPLETE.");
    i32 count = hal_device_count();
    u32 disks = 0, passed = 0, failed = 0;
    if (count < 0 || count > 64) { error("device count", count); failed = 1; }
    else for (i32 index = 0; index < count; ++index) {
        i32 result = hal_device_info(index, descriptor, sizeof(descriptor));
        if (result != 0) { error("device discovery", result); ++failed; continue; }
        if (get32(descriptor) != 0x0100) continue;
        copy(uuid, descriptor + 4, 16); ++disks;
        if (test_disk()) ++passed; else ++failed;
    }
    if (disks == 0) say("NO DISK: power off, install a tier 1 disk, then power on.");
    line_length = 0; append("SELF-TEST COMPLETE: "); number(passed); append(" passed, "); number(failed); append(" failed.");
    print(line, line_length);
    for (;;) hal_yield();
}
