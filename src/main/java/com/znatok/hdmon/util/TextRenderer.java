package com.znatok.hdmon.util;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Renders text into a {@link PixelBuffer} using the bundled bitmap font. */
public final class TextRenderer {
    private TextRenderer() {}

    /**
     * CC:Tweaked delivers Lua strings to {@code String} parameters as ISO-8859-1
     * (each raw byte becomes one Latin-1 char). If the source Lua file was saved as
     * UTF-8 (ZnatokOS editor default), a character like 'П' arrives as two Latin-1
     * chars 0xD0 0x9F instead of the single codepoint U+041F.
     *
     * <p>Why: re-encode the string as ISO-8859-1 bytes to recover the original byte
     * stream, then try strict UTF-8 decoding. If it decodes cleanly, use that.
     * If it fails (the string was really a raw ASCII/Latin-1 payload with high bytes),
     * fall back to the original string so ASCII input is never broken.
     */
    public static String normalizeLuaUtf8(String text) {
        if (text == null || text.isEmpty()) return text;
        boolean hasHigh = false;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) > 0x7F) { hasHigh = true; break; }
        }
        if (!hasHigh) return text; // pure ASCII, nothing to do
        byte[] raw = text.getBytes(StandardCharsets.ISO_8859_1);
        CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CharBuffer out = dec.decode(ByteBuffer.wrap(raw));
            return out.toString();
        } catch (Exception e) {
            return text;
        }
    }

    /**
     * Draw {@code text} into {@code buf} starting at (x,y) in color (r,g,b) with integer {@code scale}.
     * Newline advances to the next line; scale clamped to [1,8].
     * Returns true if any pixel was written.
     */
    public static boolean drawText(PixelBuffer buf, int x, int y, String text,
                                   int r, int g, int b, int scale) {
        if (buf == null || text == null) return false;
        text = normalizeLuaUtf8(text);
        int s = Math.max(1, Math.min(8, scale));
        int gw = FontAtlas.glyphWidth();
        int gh = FontAtlas.glyphHeight();
        int cx = x;
        int cy = y;
        boolean any = false;
        int i = 0;
        int len = text.length();
        while (i < len) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '\n') {
                cx = x;
                cy += gh * s;
                continue;
            }
            if (cp == '\r') continue;
            byte[] glyph = FontAtlas.getGlyphOrFallback(cp);
            if (glyph != null) {
                boolean drew = buf.blitGlyph(cx, cy, gw, gh, glyph, s, r, g, b);
                any |= drew;
            }
            cx += gw * s;
        }
        return any;
    }
}
