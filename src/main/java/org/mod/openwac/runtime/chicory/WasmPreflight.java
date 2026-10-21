package org.mod.openwac.runtime.chicory;

import java.nio.*;

/** Bounds compressed declarations before Chicory allocates their decoded representation. */
final class WasmPreflight {
    private WasmPreflight() {}
    static void validate(byte[] wasm) {
        Reader module = new Reader(ByteBuffer.wrap(wasm));
        if (module.bytes.getInt() != 0x0061736d || module.bytes.getInt() != 0x01000000)
            throw new IllegalArgumentException("Invalid WASM header");
        int sections = 0, totalLocals = 0, totalCode = 0;
        while (module.bytes.hasRemaining()) {
            if (++sections > 64) throw new IllegalArgumentException("Too many sections");
            int id = module.byteValue();
            Reader section = module.slice(module.u32());
            switch (id) {
                case 0 -> { /* Custom data remains bounded by the module byte limit. */ }
                case 1 -> {
                    int count = section.count(4096);
                    for (int i = 0; i < count; i++) {
                        if (section.byteValue() != 0x60) throw new IllegalArgumentException("Only function types are supported");
                        for (int j = section.count(128); j > 0; j--) section.numericType();
                        for (int j = section.count(16); j > 0; j--) section.numericType();
                    }
                }
                case 2 -> {
                    for (int i = section.count(64); i > 0; i--) {
                        section.name(); section.name();
                        if (section.byteValue() != 0) throw new IllegalArgumentException("Only function imports are supported");
                        section.u32();
                    }
                }
                case 3 -> { for (int i = section.count(4096); i > 0; i--) section.u32(); }
                case 4 -> {
                    for (int i = section.count(1); i > 0; i--) {
                        if (section.byteValue() != 0x70 || section.u32() != 1)
                            throw new IllegalArgumentException("Tables require funcref and an explicit maximum");
                        section.count(4096); section.count(4096);
                    }
                }
                case 5 -> {
                    for (int i = section.count(1); i > 0; i--) {
                        int flags = section.count(1);
                        section.count(65536);
                        if (flags == 1) section.count(65536);
                    }
                }
                case 6 -> {
                    for (int i = section.count(4096); i > 0; i--) {
                        section.numericType(); section.countByte(1); section.constantExpression();
                    }
                }
                case 7 -> {
                    for (int i = section.count(4096); i > 0; i--) { section.name(); section.byteValue(); section.u32(); }
                }
                case 8 -> section.u32();
                case 9 -> {
                    for (int i = section.count(64); i > 0; i--) {
                        if (section.u32() != 0) throw new IllegalArgumentException("Only active MVP element segments are supported");
                        section.constantExpression();
                        for (int j = section.count(4096); j > 0; j--) section.u32();
                    }
                }
                case 10 -> {
                    for (int i = section.count(4096); i > 0; i--) {
                        int size = section.u32();
                        totalCode = Math.addExact(totalCode, size);
                        if (totalCode > 64 * 1024) throw new IllegalArgumentException("Decoded code limit exceeded");
                        Reader body = section.slice(size);
                        int locals = 0;
                        for (int j = body.count(1024); j > 0; j--) {
                            locals = Math.addExact(locals, body.count(1024));
                            body.numericType();
                        }
                        totalLocals = Math.addExact(totalLocals, locals);
                        if (locals > 1024 || totalLocals > 65536) throw new IllegalArgumentException("Local variable limit exceeded");
                    }
                }
                case 11 -> {
                    for (int i = section.count(4096); i > 0; i--) {
                        int flags = section.count(2);
                        if (flags == 2) section.u32();
                        if (flags != 1) section.constantExpression();
                        section.skip(section.u32());
                    }
                }
                case 12 -> section.count(4096);
                default -> throw new IllegalArgumentException("Unsupported WASM section");
            }
            if (id != 0 && section.bytes.hasRemaining()) throw new IllegalArgumentException("Invalid section size");
        }
    }
    private static final class Reader {
        private final ByteBuffer bytes;
        Reader(ByteBuffer bytes) { this.bytes = bytes; }
        int byteValue() { return Byte.toUnsignedInt(bytes.get()); }
        int u32() {
            long result = 0;
            for (int shift = 0; shift < 35; shift += 7) {
                int b = byteValue(); result |= (long) (b & 127) << shift;
                if ((b & 128) == 0) {
                    if (result > Integer.MAX_VALUE) throw new IllegalArgumentException("Declaration exceeds supported limits");
                    return (int) result;
                }
            }
            throw new IllegalArgumentException("Invalid unsigned LEB128");
        }
        int count(int maximum) { int value = u32(); if (value > maximum) throw new IllegalArgumentException("Declaration limit exceeded"); return value; }
        void countByte(int maximum) { if (byteValue() > maximum) throw new IllegalArgumentException("Invalid flag"); }
        void numericType() { int type = byteValue(); if (type < 0x7c || type > 0x7f) throw new IllegalArgumentException("Only scalar numeric types are supported"); }
        void name() { skip(count(128)); }
        void skip(int count) {
            if (count < 0 || count > bytes.remaining()) throw new IllegalArgumentException("Truncated WASM section");
            bytes.position(bytes.position() + count);
        }
        Reader slice(int size) {
            if (size < 0 || size > bytes.remaining()) throw new IllegalArgumentException("Truncated WASM section");
            Reader result = new Reader(bytes.slice(bytes.position(), size)); skip(size); return result;
        }
        void constantExpression() {
            for (int i = 0; i < 128; i++) {
                switch (byteValue()) {
                    case 0x0b -> { return; }
                    case 0x41, 0x42 -> {
                        int count = 0;
                        while ((byteValue() & 128) != 0) if (++count >= 10) throw new IllegalArgumentException("Invalid constant");
                    }
                    case 0x43 -> skip(4);
                    case 0x44 -> skip(8);
                    case 0x23 -> u32();
                    case 0x6a, 0x6b, 0x6c, 0x7c, 0x7d, 0x7e -> { }
                    default -> throw new IllegalArgumentException("Unsupported constant expression");
                }
            }
            throw new IllegalArgumentException("Constant expression too large");
        }
    }
}
