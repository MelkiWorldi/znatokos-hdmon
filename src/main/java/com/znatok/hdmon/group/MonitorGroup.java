package com.znatok.hdmon.group;

import com.znatok.hdmon.util.PixelBuffer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;

/**
 * Server-side data for one logical monitor group. Origin holds the master PixelBuffer
 * for the whole group. Non-origin members route their drawing through origin.
 */
public class MonitorGroup {
    public static final int BLOCK_W = 160;
    public static final int BLOCK_H = 90;

    /** Origin = bottom-left block (col=0, row=0) from viewer's perspective. */
    public final BlockPos originPos;
    public final Direction facing;
    public final int cols;
    public final int rows;
    public final Set<BlockPos> members;
    /** Lazily-built: non-null on server after createBuffer(). */
    @Nullable public PixelBuffer buffer;
    public boolean dirty = false;
    public long lastSyncMs = 0L;

    public MonitorGroup(BlockPos originPos, Direction facing, int cols, int rows, Set<BlockPos> members) {
        this.originPos = originPos;
        this.facing = facing;
        this.cols = cols;
        this.rows = rows;
        this.members = new HashSet<>(members);
    }

    public int pixelWidth() { return cols * BLOCK_W; }
    public int pixelHeight() { return rows * BLOCK_H; }

    public PixelBuffer ensureBuffer() {
        if (buffer == null) buffer = new PixelBuffer(pixelWidth(), pixelHeight());
        return buffer;
    }
}
