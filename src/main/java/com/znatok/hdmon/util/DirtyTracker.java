package com.znatok.hdmon.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Tile-based dirty tracker. 16x16 pixel tiles. Group dims are always multiples
 * of 16 (cols*128 x rows*64), so tilesX = cols*8, tilesY = rows*4.
 *
 * <p>Thread-safe: all mutators/readers synchronize on {@code this}. The tracker
 * lives on the origin BE and may be touched from the Lua peripheral thread and
 * the server tick thread concurrently.
 */
public class DirtyTracker {
    private static final int TILE = 16;

    private int tilesX;
    private int tilesY;
    private boolean[][] tiles; // [tx][ty]
    private boolean any;

    public DirtyTracker() {
        this(1, 1);
    }

    public DirtyTracker(int cols, int rows) {
        resize(cols, rows);
    }

    public static int tileSize() { return TILE; }

    public synchronized void resize(int cols, int rows) {
        this.tilesX = Math.max(1, cols) * 8;  // cols*128 / 16
        this.tilesY = Math.max(1, rows) * 4;  // rows*64 / 16
        this.tiles = new boolean[tilesX][tilesY];
        this.any = false;
    }

    public synchronized int tilesX() { return tilesX; }
    public synchronized int tilesY() { return tilesY; }

    public synchronized void markAllDirty() {
        for (int x = 0; x < tilesX; x++) {
            for (int y = 0; y < tilesY; y++) tiles[x][y] = true;
        }
        any = true;
    }

    public synchronized void markRectDirty(int pxX, int pxY, int pxW, int pxH) {
        if (pxW <= 0 || pxH <= 0) return;
        int x0 = Math.max(0, pxX) / TILE;
        int y0 = Math.max(0, pxY) / TILE;
        int x1 = Math.min(tilesX * TILE, pxX + pxW);
        int y1 = Math.min(tilesY * TILE, pxY + pxH);
        if (x1 <= 0 || y1 <= 0) return;
        int tx1 = (x1 - 1) / TILE;
        int ty1 = (y1 - 1) / TILE;
        if (x0 >= tilesX || y0 >= tilesY) return;
        for (int tx = x0; tx <= tx1 && tx < tilesX; tx++) {
            for (int ty = y0; ty <= ty1 && ty < tilesY; ty++) {
                tiles[tx][ty] = true;
                any = true;
            }
        }
    }

    public synchronized boolean isDirty() { return any; }

    public synchronized int dirtyCount() {
        if (!any) return 0;
        int n = 0;
        for (int x = 0; x < tilesX; x++) {
            for (int y = 0; y < tilesY; y++) if (tiles[x][y]) n++;
        }
        return n;
    }

    public synchronized int totalTiles() { return tilesX * tilesY; }

    /** Returns dirty tiles as flat [tx,ty,tx,ty,...] and resets to clean. */
    public synchronized int[] drainDirty() {
        if (!any) return new int[0];
        List<Integer> out = new ArrayList<>();
        for (int x = 0; x < tilesX; x++) {
            for (int y = 0; y < tilesY; y++) {
                if (tiles[x][y]) {
                    out.add(x);
                    out.add(y);
                    tiles[x][y] = false;
                }
            }
        }
        any = false;
        int[] arr = new int[out.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = out.get(i);
        return arr;
    }

    public synchronized void markClean() {
        for (int x = 0; x < tilesX; x++) {
            for (int y = 0; y < tilesY; y++) tiles[x][y] = false;
        }
        any = false;
    }
}
