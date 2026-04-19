package com.znatok.hdmon.util;

/** Thread-safe RGB framebuffer. byte[w*h*3] row-major. */
public class PixelBuffer {
    private final int width;
    private final int height;
    private final byte[] data;

    public PixelBuffer(int width, int height) {
        this.width = width;
        this.height = height;
        this.data = new byte[width * height * 3];
    }

    public int width() { return width; }
    public int height() { return height; }

    public synchronized byte[] getBytes() { return data; }

    public synchronized byte[] copyBytes() {
        byte[] out = new byte[data.length];
        System.arraycopy(data, 0, out, 0, data.length);
        return out;
    }

    public synchronized boolean setPixel(int x, int y, int r, int g, int b) {
        if (x < 0 || y < 0 || x >= width || y >= height) return false;
        int idx = (y * width + x) * 3;
        data[idx    ] = (byte)(r & 0xFF);
        data[idx + 1] = (byte)(g & 0xFF);
        data[idx + 2] = (byte)(b & 0xFF);
        return true;
    }

    public synchronized int[] getPixel(int x, int y) {
        if (x < 0 || y < 0 || x >= width || y >= height) return new int[]{0,0,0};
        int idx = (y * width + x) * 3;
        return new int[]{ data[idx] & 0xFF, data[idx+1] & 0xFF, data[idx+2] & 0xFF };
    }

    public synchronized void clear(int r, int g, int b) {
        byte br = (byte)(r & 0xFF), bg = (byte)(g & 0xFF), bb = (byte)(b & 0xFF);
        for (int i = 0; i < data.length; i += 3) {
            data[i    ] = br;
            data[i + 1] = bg;
            data[i + 2] = bb;
        }
    }

    public synchronized void setAll(byte[] src) {
        if (src == null || src.length != data.length) return;
        System.arraycopy(src, 0, data, 0, data.length);
    }

    /** Filled rect. Clipped to buffer. Returns true if at least one pixel changed. */
    public synchronized boolean drawRect(int x, int y, int w, int h, int r, int g, int b) {
        if (w <= 0 || h <= 0) return false;
        int x0 = Math.max(0, x);
        int y0 = Math.max(0, y);
        int x1 = Math.min(width, x + w);
        int y1 = Math.min(height, y + h);
        if (x0 >= x1 || y0 >= y1) return false;
        byte br = (byte)(r & 0xFF), bg = (byte)(g & 0xFF), bb = (byte)(b & 0xFF);
        for (int yy = y0; yy < y1; yy++) {
            int base = (yy * width + x0) * 3;
            for (int xx = x0; xx < x1; xx++) {
                data[base    ] = br;
                data[base + 1] = bg;
                data[base + 2] = bb;
                base += 3;
            }
        }
        return true;
    }

    /**
     * Blit a w*h RGB image from {@code rgb} (row-major, length >= w*h*3) into buffer at (x,y),
     * clipping at edges. Returns true if any pixel was written.
     */
    public synchronized boolean drawImage(int x, int y, int w, int h, byte[] rgb) {
        if (rgb == null) throw new IllegalArgumentException("rgb is null");
        if (w <= 0 || h <= 0) return false;
        int need = w * h * 3;
        if (rgb.length < need) {
            throw new IllegalArgumentException("rgb too short: need " + need + " got " + rgb.length);
        }
        int sx = 0, sy = 0;
        int dx = x, dy = y;
        if (dx < 0) { sx = -dx; dx = 0; }
        if (dy < 0) { sy = -dy; dy = 0; }
        int cw = Math.min(w - sx, width - dx);
        int ch = Math.min(h - sy, height - dy);
        if (cw <= 0 || ch <= 0) return false;
        for (int row = 0; row < ch; row++) {
            int srcOff = ((sy + row) * w + sx) * 3;
            int dstOff = ((dy + row) * width + dx) * 3;
            System.arraycopy(rgb, srcOff, data, dstOff, cw * 3);
        }
        return true;
    }

    /**
     * Blit one glyph. {@code glyphMask} is glyphW*glyphH bytes, 0 = transparent, non-zero = on.
     * Each source pixel expands to a scale*scale solid square in the buffer, in (r,g,b).
     */
    /** Copy tileSize x tileSize RGB from tile (tx,ty) into a fresh buffer. */
    public synchronized byte[] extractTile(int tx, int ty, int tileSize) {
        byte[] out = new byte[tileSize * tileSize * 3];
        int px = tx * tileSize;
        int py = ty * tileSize;
        for (int row = 0; row < tileSize; row++) {
            int sy = py + row;
            if (sy < 0 || sy >= height) continue;
            int srcOff = (sy * width + px) * 3;
            int dstOff = row * tileSize * 3;
            int copy = Math.min(tileSize, width - px) * 3;
            if (copy <= 0) continue;
            System.arraycopy(data, srcOff, out, dstOff, copy);
        }
        return out;
    }

    /** Paste tileSize x tileSize RGB (length tileSize*tileSize*3) at tile (tx,ty). */
    public synchronized void applyTile(int tx, int ty, int tileSize, byte[] rgb) {
        if (rgb == null || rgb.length < tileSize * tileSize * 3) return;
        int px = tx * tileSize;
        int py = ty * tileSize;
        for (int row = 0; row < tileSize; row++) {
            int dy = py + row;
            if (dy < 0 || dy >= height) continue;
            int dstOff = (dy * width + px) * 3;
            int srcOff = row * tileSize * 3;
            int copy = Math.min(tileSize, width - px) * 3;
            if (copy <= 0) continue;
            System.arraycopy(rgb, srcOff, data, dstOff, copy);
        }
    }

    public synchronized boolean blitGlyph(int x, int y, int glyphW, int glyphH,
                                          byte[] glyphMask, int scale, int r, int g, int b) {
        if (glyphMask == null || scale < 1) return false;
        if (glyphMask.length < glyphW * glyphH) return false;
        byte br = (byte)(r & 0xFF), bg = (byte)(g & 0xFF), bb = (byte)(b & 0xFF);
        boolean any = false;
        for (int gy = 0; gy < glyphH; gy++) {
            for (int gx = 0; gx < glyphW; gx++) {
                if (glyphMask[gy * glyphW + gx] == 0) continue;
                int px0 = x + gx * scale;
                int py0 = y + gy * scale;
                int px1 = px0 + scale;
                int py1 = py0 + scale;
                int cx0 = Math.max(0, px0), cy0 = Math.max(0, py0);
                int cx1 = Math.min(width, px1), cy1 = Math.min(height, py1);
                if (cx0 >= cx1 || cy0 >= cy1) continue;
                for (int yy = cy0; yy < cy1; yy++) {
                    int base = (yy * width + cx0) * 3;
                    for (int xx = cx0; xx < cx1; xx++) {
                        data[base    ] = br;
                        data[base + 1] = bg;
                        data[base + 2] = bb;
                        base += 3;
                    }
                }
                any = true;
            }
        }
        return any;
    }
}
