package com.znatok.hdmon.block;

import com.znatok.hdmon.Registries;
import com.znatok.hdmon.group.GroupManager;
import com.znatok.hdmon.network.FullBufferSyncPacket;
import com.znatok.hdmon.network.TileDiffPacket;
import com.znatok.hdmon.peripheral.HDMonPeripheral;
import com.znatok.hdmon.util.DirtyTracker;
import com.znatok.hdmon.util.PixelBuffer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * BE for a single HDMonitor block. May participate in a multi-block group;
 * origin block owns the master buffer for the group, non-origin blocks delegate.
 * Nothing is persisted across saves — groups rebuild on chunk load.
 */
public class HDMonBlockEntity extends BlockEntity {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("HDMon/BE");

    public static final int WIDTH = 60;
    public static final int HEIGHT = 60;
    public static final int TILE = 10;

    /** Standalone/origin buffer (WIDTH*cols x HEIGHT*rows). Non-origin BEs leave this at WIDTH x HEIGHT and ignore it. */
    private PixelBuffer buffer = new PixelBuffer(WIDTH, HEIGHT);
    private DirtyTracker dirty = new DirtyTracker(1, 1);

    private @Nullable HDMonPeripheral peripheral;

    private boolean autoFlush = true;
    private long lastSyncMs = 0L;
    private static final long MIN_SYNC_INTERVAL_MS = 100L;

    // Group metadata. Defaults = 1x1 standalone with self as origin.
    private BlockPos originPos;                 // null before first onLoad -> treated as self
    private int colIndex = 0;
    private int rowIndex = 0;
    private int cols = 1;
    private int rows = 1;

    public HDMonBlockEntity(BlockPos pos, BlockState state) {
        super(Registries.HD_MONITOR_BE.get(), pos, state);
        this.originPos = pos;
    }

    public boolean isOrigin() {
        return originPos == null || originPos.equals(getBlockPos());
    }

    public BlockPos getOriginPos() { return originPos == null ? getBlockPos() : originPos; }
    public int getColIndex() { return colIndex; }
    public int getRowIndex() { return rowIndex; }
    public int getCols() { return cols; }
    public int getRows() { return rows; }

    /** Called by GroupManager when this BE is assigned to a (new) group. */
    public void joinGroup(BlockPos origin, int col, int row, int cols, int rows) {
        boolean changed = !origin.equals(this.originPos) || col != this.colIndex
                || row != this.rowIndex || cols != this.cols || rows != this.rows;
        this.originPos = origin;
        this.colIndex = col;
        this.rowIndex = row;
        this.cols = cols;
        this.rows = rows;
        if (isOrigin()) {
            int w = cols * WIDTH, h = rows * HEIGHT;
            if (buffer.width() != w || buffer.height() != h) {
                buffer = new PixelBuffer(w, h);
            }
            dirty.resize(cols, rows);
        }
        if (changed) {
            if (isOrigin()) dirty.markAllDirty();
            // Force full resync so clients learn new shape.
            if (level != null && !level.isClientSide && isOrigin()) {
                sendFullBuffer();
                lastSyncMs = System.currentTimeMillis();
                dirty.markClean();
            }
            // Trigger block update so update tag is re-sent to tracking clients (covers non-origin too).
            if (level != null && !level.isClientSide) {
                setChanged();
                level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
            }
        }
    }

    /** Returns the buffer for drawing — origin's own buffer. */
    @Nullable
    public PixelBuffer getDrawBuffer() {
        if (isOrigin()) return buffer;
        if (level == null) return null;
        BlockEntity be = level.getBlockEntity(getOriginPos());
        if (be instanceof HDMonBlockEntity hd && hd.isOrigin()) return hd.buffer;
        return null;
    }

    /** Origin's own raw buffer (for client rendering upload). Only valid when isOrigin(). */
    public PixelBuffer getOwnBuffer() { return buffer; }

    public HDMonPeripheral getPeripheral(@Nullable Direction side) {
        if (peripheral == null) peripheral = new HDMonPeripheral(this);
        return peripheral;
    }

    public void setAutoFlush(boolean v) { this.autoFlush = v; }

    // ---- Drawing: route to origin's buffer, mark origin dirty ----

    @Nullable
    private HDMonBlockEntity resolveOrigin() {
        if (isOrigin()) return this;
        if (level == null) return null;
        BlockEntity be = level.getBlockEntity(getOriginPos());
        return be instanceof HDMonBlockEntity hd ? hd : null;
    }

    public void setPixel(int x, int y, int r, int g, int b) {
        HDMonBlockEntity o = resolveOrigin();
        if (o == null) return;
        if (o.buffer.setPixel(x, y, r, g, b)) {
            o.dirty.markRectDirty(x, y, 1, 1);
            if (o.autoFlush) o.maybeSync(false);
        }
    }

    public void clearBuffer(int r, int g, int b) {
        HDMonBlockEntity o = resolveOrigin();
        if (o == null) return;
        o.buffer.clear(r, g, b);
        o.dirty.markAllDirty();
        if (o.autoFlush) o.maybeSync(false);
    }

    public void drawRect(int x, int y, int w, int h, int r, int g, int b) {
        HDMonBlockEntity o = resolveOrigin();
        if (o == null) return;
        if (o.buffer.drawRect(x, y, w, h, r, g, b)) {
            o.dirty.markRectDirty(x, y, w, h);
            if (o.autoFlush) o.maybeSync(false);
        }
    }

    public void drawImage(int x, int y, int w, int h, byte[] rgb) {
        HDMonBlockEntity o = resolveOrigin();
        if (o == null) return;
        if (o.buffer.drawImage(x, y, w, h, rgb)) {
            o.dirty.markRectDirty(x, y, w, h);
            if (o.autoFlush) o.maybeSync(false);
        }
    }

    public void drawText(int x, int y, String text, int r, int g, int b, int scale) {
        HDMonBlockEntity o = resolveOrigin();
        if (o == null) return;
        if (com.znatok.hdmon.util.TextRenderer.drawText(o.buffer, x, y, text, r, g, b, scale)) {
            // Bounding box estimate: scale is clamped to [1,8] inside TextRenderer.
            int s = Math.max(1, Math.min(8, scale));
            int gw = 8 * s;
            int gh = 12 * s;
            int maxLineChars = 1;
            int lines = 1;
            int cur = 0;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '\n') { lines++; cur = 0; }
                else if (c != '\r') { cur++; if (cur > maxLineChars) maxLineChars = cur; }
            }
            o.dirty.markRectDirty(x, y, maxLineChars * gw, lines * gh);
            if (o.autoFlush) o.maybeSync(false);
        }
    }

    public void flushNow() {
        HDMonBlockEntity o = resolveOrigin();
        if (o == null) return;
        o.maybeSync(true);
    }

    private void maybeSync(boolean force) {
        if (level == null || level.isClientSide) return;
        if (!isOrigin()) return;
        if (!dirty.isDirty()) return;
        long now = System.currentTimeMillis();
        if (!force && (now - lastSyncMs) < MIN_SYNC_INTERVAL_MS) return;
        lastSyncMs = now;

        int[] dt = dirty.drainDirty();
        if (dt.length == 0) return;
        sendTileDiff(dt);
    }

    private void sendFullBuffer() {
        if (level == null || level.isClientSide) return;
        byte[] snapshot = buffer.copyBytes();
        FullBufferSyncPacket pkt = new FullBufferSyncPacket(getBlockPos(), cols, rows, snapshot);
        PacketDistributor.sendToPlayersTrackingChunk(
                (ServerLevel) level,
                new net.minecraft.world.level.ChunkPos(getBlockPos()),
                pkt
        );
    }

    private void sendTileDiff(int[] dirtyPairs) {
        int n = dirtyPairs.length / 2;
        if (n <= 0) return;
        List<TileDiffPacket.Tile> tiles = new ArrayList<>(n);
        Deflater def = new Deflater(Deflater.BEST_SPEED);
        try {
            byte[] compBuf = new byte[TILE * TILE * 3 + 64];
            for (int i = 0; i < n; i++) {
                int tx = dirtyPairs[2 * i];
                int ty = dirtyPairs[2 * i + 1];
                byte[] raw = buffer.extractTile(tx, ty, TILE);
                def.reset();
                def.setInput(raw);
                def.finish();
                int written = def.deflate(compBuf);
                byte[] comp;
                if (def.finished()) {
                    comp = new byte[written];
                    System.arraycopy(compBuf, 0, comp, 0, written);
                } else {
                    // Rare: compressed > compBuf (shouldn't happen for TILE*TILE*3-byte input with +64 slack),
                    // fall back to a dynamic buffer.
                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream(1024);
                    baos.write(compBuf, 0, written);
                    byte[] tmp = new byte[256];
                    while (!def.finished()) {
                        int w = def.deflate(tmp);
                        if (w == 0) break;
                        baos.write(tmp, 0, w);
                    }
                    comp = baos.toByteArray();
                }
                tiles.add(new TileDiffPacket.Tile((short) tx, (short) ty, comp));
            }
        } finally {
            def.end();
        }
        TileDiffPacket pkt = new TileDiffPacket(getBlockPos(), cols, rows, tiles);
        PacketDistributor.sendToPlayersTrackingChunk(
                (ServerLevel) level,
                new net.minecraft.world.level.ChunkPos(getBlockPos()),
                pkt
        );
    }

    private boolean registeredWithGroupManager = false;

    /** Server tick — flush any pending updates on a cadence. Only origin ticks meaningfully. */
    public static void serverTick(Level level, BlockPos pos, BlockState state, HDMonBlockEntity be) {
        // Registration fallback: if onLoad didn't register us, do it on first tick.
        if (!be.registeredWithGroupManager) {
            be.registeredWithGroupManager = true;
            if (GroupManager.getGroup(level, pos) == null) {
                Direction facing = state.getValue(HorizontalDirectionalBlock.FACING);
                LOG.info("HDMon BE serverTick fallback registration at {} facing={}", pos, facing);
                GroupManager.onBlockAdded(level, pos, facing);
            }
        }
        if (!be.isOrigin()) return;
        if (be.dirty.isDirty() && be.autoFlush) {
            be.maybeSync(false);
        }
    }

    /** Called on client when packet arrives at origin BE. */
    public void applyBufferSync(int cols, int rows, byte[] rgb) {
        // Ensure buffer size matches incoming group shape.
        int w = cols * WIDTH, h = rows * HEIGHT;
        if (buffer.width() != w || buffer.height() != h) {
            buffer = new PixelBuffer(w, h);
        }
        this.cols = cols;
        this.rows = rows;
        this.originPos = getBlockPos(); // receiver of FullBufferSyncPacket is the origin
        buffer.setAll(rgb);
        com.znatok.hdmon.client.TextureManager.markDirty(getBlockPos());
    }

    /** Client-side: apply a tile-diff packet, decompressing each tile into the mirror buffer. */
    public void applyTileDiff(int cols, int rows, List<TileDiffPacket.Tile> tiles) {
        int w = cols * WIDTH, h = rows * HEIGHT;
        if (buffer.width() != w || buffer.height() != h) {
            // Out of sync — wait for the next FullBufferSyncPacket / update tag.
            return;
        }
        this.cols = cols;
        this.rows = rows;
        this.originPos = getBlockPos();
        Inflater inf = new Inflater();
        byte[] raw = new byte[TILE * TILE * 3];
        try {
            for (TileDiffPacket.Tile t : tiles) {
                inf.reset();
                inf.setInput(t.compressed());
                try {
                    int got = inf.inflate(raw);
                    if (got != raw.length) continue;
                } catch (java.util.zip.DataFormatException e) {
                    continue;
                }
                buffer.applyTile(t.tx(), t.ty(), TILE, raw);
            }
        } finally {
            inf.end();
        }
        com.znatok.hdmon.client.TextureManager.markDirty(getBlockPos());
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide) {
            Direction facing = getBlockState().getValue(HorizontalDirectionalBlock.FACING);
            LOG.info("HDMon BE onLoad at {} facing={}", getBlockPos(), facing);
            GroupManager.onBlockAdded(level, getBlockPos(), facing);
        }
    }

    /** Ensure new client viewers get the full buffer on chunk load. */
    @Override
    public net.minecraft.network.protocol.Packet<net.minecraft.network.protocol.game.ClientGamePacketListener> getUpdatePacket() {
        return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public net.minecraft.nbt.CompoundTag getUpdateTag(net.minecraft.core.HolderLookup.Provider lookup) {
        net.minecraft.nbt.CompoundTag tag = super.getUpdateTag(lookup);
        BlockPos op = getOriginPos();
        tag.putLong("origin", op.asLong());
        tag.putInt("cols", cols);
        tag.putInt("rows", rows);
        tag.putInt("col", colIndex);
        tag.putInt("row", rowIndex);
        if (isOrigin()) {
            tag.putByteArray("rgb", buffer.copyBytes());
        }
        return tag;
    }

    @Override
    public void handleUpdateTag(net.minecraft.nbt.CompoundTag tag, net.minecraft.core.HolderLookup.Provider lookup) {
        super.handleUpdateTag(tag, lookup);
        if (tag.contains("origin")) {
            this.originPos = BlockPos.of(tag.getLong("origin"));
            this.cols = Math.max(1, tag.getInt("cols"));
            this.rows = Math.max(1, tag.getInt("rows"));
            this.colIndex = tag.getInt("col");
            this.rowIndex = tag.getInt("row");
        }
        if (isOrigin() && tag.contains("rgb")) {
            int w = cols * WIDTH, h = rows * HEIGHT;
            if (buffer.width() != w || buffer.height() != h) {
                buffer = new PixelBuffer(w, h);
            }
            byte[] arr = tag.getByteArray("rgb");
            if (arr.length == w * h * 3) {
                buffer.setAll(arr);
                if (level != null && level.isClientSide) {
                    com.znatok.hdmon.client.TextureManager.markDirty(getBlockPos());
                }
            }
        }
        if (level != null && level.isClientSide && !isOrigin()) {
            // Mark origin texture dirty if we now know the origin.
            com.znatok.hdmon.client.TextureManager.markDirty(getOriginPos());
        }
    }

    @Override
    public void onDataPacket(net.minecraft.network.Connection net,
                             net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket pkt,
                             net.minecraft.core.HolderLookup.Provider lookup) {
        if (pkt.getTag() != null) {
            handleUpdateTag(pkt.getTag(), lookup);
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (peripheral != null) peripheral.markDetached();
        if (level != null && !level.isClientSide) {
            GroupManager.onBlockRemoved(level, getBlockPos());
        }
        if (level != null && level.isClientSide) {
            // Non-origin: nothing owned. Origin: release its texture.
            if (isOrigin()) {
                com.znatok.hdmon.client.TextureManager.release(getBlockPos());
            }
        }
    }

    @SuppressWarnings("unchecked")
    public static <T extends BlockEntity> BlockEntityTicker<T> ticker(Level level, BlockEntityType<T> type) {
        if (level.isClientSide) return null;
        return (BlockEntityTicker<T>) (BlockEntityTicker<HDMonBlockEntity>) HDMonBlockEntity::serverTick;
    }
}
