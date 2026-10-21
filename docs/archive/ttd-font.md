# TTD glyph atlas

The Tiny Text Display uses the supplied CP437 atlas, copied unchanged from
`Codepage-437-fixed.png` to
`../../src/main/resources/assets/openwac/textures/display/cp437.png`.
Its resource identifier is `openwac:textures/display/cp437.png`.

The image is 288 x 128 pixels, containing 256 glyphs in row-major byte order:
32 columns and 8 rows, with each cell measuring 9 x 16 pixels. Glyphs are white
on black. Render the whole cell, including its black background, with nearest
filtering and no mipmaps. Preserve the glyph aspect ratio when scaling.

For a stored Java byte, calculate the atlas rectangle as follows:

```java
int code = Byte.toUnsignedInt(character);
int sourceX = (code & 31) * 9;
int sourceY = (code >>> 5) * 16;
// Source rectangle: sourceX, sourceY, width 9, height 16.
// Normalized UVs: sourceX / 288f, sourceY / 128f,
//                (sourceX + 9) / 288f, (sourceY + 16) / 128f.
```

TTD cells hold CP437 bytes, not Unicode or UTF-8. The renderer selects the glyph
directly from the atlas; it does not use Minecraft's font renderer or require
a Java charset decoder. This also preserves the supplied graphical glyphs in
the low control-code positions. Newline, tab, and other terminal behavior belong
in the WASM display driver, rather than the cell renderer.

The implemented screen uses 60 columns by 30 rows, producing a 540 x
480 logical display. `TtdRenderer` batches glyph quads on the block face using
the atlas; `TtdScreen` uses the same atlas for the enlarged view. Both preserve
the glyph aspect ratio. Texture metadata disables blur and clamps the atlas.
No per-display dynamic GPU texture is allocated; changes update only the
client's cell buffer and the renderer selects glyphs from the shared resource.

The [TTD device contract](true_device_interfaces.md#0x0400-tiny-text-display-ttd)
defines the byte layouts. See [using the screen](ttd.md) for attachment,
persistence, and guest examples.
