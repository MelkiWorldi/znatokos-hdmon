package com.znatok.hdmon.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Loads 8x12 bitmap glyph masks from two .bin files in the jar:
 *   /assets/hdmon/font/ascii.bin       (ASCII 0x20..0x7E)
 *   /assets/hdmon/font/cyrillic.bin    (U+0400..U+04FF)
 *
 * Each file layout: u32-LE firstCodepoint, u32-LE count, then count * 96 bytes
 * (8*12 = 96 bytes per glyph, one byte per pixel: 0 transparent, 1 opaque).
 *
 * Safe to load on server + client — it's just a resource bundled in the jar.
 */
public final class FontAtlas {
    private FontAtlas() {}

    public static final int GLYPH_W = 8;
    public static final int GLYPH_H = 12;
    private static final int BYTES_PER_GLYPH = GLYPH_W * GLYPH_H;

    private static final class Bank {
        final int first;
        final int count;
        final byte[] data; // count * BYTES_PER_GLYPH
        Bank(int first, int count, byte[] data) {
            this.first = first; this.count = count; this.data = data;
        }
        byte[] get(int cp) {
            if (cp < first || cp >= first + count) return null;
            int off = (cp - first) * BYTES_PER_GLYPH;
            byte[] out = new byte[BYTES_PER_GLYPH];
            System.arraycopy(data, off, out, 0, BYTES_PER_GLYPH);
            return out;
        }
    }

    private static Bank ascii;
    private static Bank cyrillic;
    private static byte[] fallback; // glyph for '?'

    static {
        ascii = loadBank("/assets/hdmon/font/ascii.bin");
        cyrillic = loadBank("/assets/hdmon/font/cyrillic.bin");
        byte[] q = ascii != null ? ascii.get('?') : null;
        if (q == null) {
            // emergency — an X-shape
            q = new byte[BYTES_PER_GLYPH];
            for (int i = 0; i < GLYPH_H; i++) {
                int a = (i * GLYPH_W) / GLYPH_H;
                int b = GLYPH_W - 1 - a;
                if (a >= 0 && a < GLYPH_W) q[i * GLYPH_W + a] = 1;
                if (b >= 0 && b < GLYPH_W) q[i * GLYPH_W + b] = 1;
            }
        }
        fallback = q;
    }

    private static Bank loadBank(String path) {
        try (InputStream in = FontAtlas.class.getResourceAsStream(path)) {
            if (in == null) return null;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            byte[] raw = bos.toByteArray();
            if (raw.length < 8) return null;
            ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
            int first = bb.getInt();
            int count = bb.getInt();
            int need = 8 + count * BYTES_PER_GLYPH;
            if (raw.length < need) return null;
            byte[] body = new byte[count * BYTES_PER_GLYPH];
            System.arraycopy(raw, 8, body, 0, body.length);
            return new Bank(first, count, body);
        } catch (IOException ignored) {
            return null;
        }
    }

    /** Returns a 96-byte mask for the codepoint or null (caller uses fallback). */
    public static byte[] getGlyph(int codepoint) {
        if (ascii != null) {
            byte[] g = ascii.get(codepoint);
            if (g != null) return g;
        }
        if (cyrillic != null) {
            byte[] g = cyrillic.get(codepoint);
            if (g != null) return g;
        }
        return null;
    }

    /** Returns glyph or fallback ('?') — never null. */
    public static byte[] getGlyphOrFallback(int codepoint) {
        byte[] g = getGlyph(codepoint);
        return g != null ? g : fallback;
    }

    public static int glyphWidth() { return GLYPH_W; }
    public static int glyphHeight() { return GLYPH_H; }

    /** Force class load (for eager init during mod startup). */
    public static void init() {}
}
