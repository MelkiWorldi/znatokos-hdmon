package com.znatok.hdmon.group;

import com.znatok.hdmon.block.HDMonBlockEntity;
import com.znatok.hdmon.util.PixelBuffer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Server-side manager for {@link MonitorGroup}s. One index per Level (dimension).
 * Groups are NOT persisted — rebuilt on chunk load via BE#onLoad.
 *
 * <p>Coordinate convention: for a group facing {@code F}, the viewer stands on +F side
 * looking toward -F. Viewer's right is {@code F.getCounterClockWise()} (=> col axis),
 * viewer's up is {@code +Y} (=> row axis). Origin block has (col=0, row=0) = bottom-left.
 */
public final class GroupManager {
    private GroupManager() {}

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("HDMon/GroupManager");

    public static final int MAX_COLS = 16;
    public static final int MAX_ROWS = 9;

    /** Keyed by dimension then by any member BlockPos — all members map to their group. */
    private static final Map<ResourceKey<Level>, Map<BlockPos, MonitorGroup>> INDEX = new HashMap<>();

    private static Map<BlockPos, MonitorGroup> index(Level level) {
        return INDEX.computeIfAbsent(level.dimension(), k -> new HashMap<>());
    }

    @Nullable
    public static MonitorGroup getGroup(Level level, BlockPos pos) {
        Map<BlockPos, MonitorGroup> idx = INDEX.get(level.dimension());
        return idx == null ? null : idx.get(pos);
    }

    /** Viewer's-right axis for the given facing. */
    public static Direction rightAxis(Direction facing) {
        return facing.getCounterClockWise();
    }

    /** Place block into group world. Called from BE#onLoad on server. */
    public static void onBlockAdded(Level level, BlockPos pos, Direction facing) {
        if (level.isClientSide) return;
        Map<BlockPos, MonitorGroup> idx = index(level);

        LOG.info("[GroupManager] onBlockAdded pos={} facing={}", pos, facing);

        // Log neighbour groups at the 4 in-plane directions.
        for (Direction d : inPlaneAxes(facing)) {
            BlockPos np = pos.relative(d);
            MonitorGroup ng = idx.get(np);
            if (ng != null) {
                LOG.info("[GroupManager]   neighbour {} @ {} = group origin={} cols={} rows={} facing={}",
                        d, np, ng.originPos, ng.cols, ng.rows, ng.facing);
            } else {
                LOG.info("[GroupManager]   neighbour {} @ {} = none", d, np);
            }
        }

        // Already tracked? (E.g. duplicate onLoad) — skip, it will be refreshed by neighbour ops.
        if (idx.containsKey(pos)) {
            LOG.info("[GroupManager]   already tracked, skipping");
            return;
        }

        // Start with a 1x1 group containing just this block.
        MonitorGroup solo = new MonitorGroup(pos, facing, 1, 1, Collections.singleton(pos));
        idx.put(pos, solo);
        notifyBEJoined(level, pos, solo);

        // Try to merge with neighbours iteratively.
        tryGrow(level, pos);

        MonitorGroup finalG = idx.get(pos);
        if (finalG != null) {
            LOG.info("[GroupManager]   final group: origin={} cols={} rows={} members={}",
                    finalG.originPos, finalG.cols, finalG.rows, finalG.members.size());
        }
    }

    /** Remove block. Called from BE#setRemoved on server. */
    public static void onBlockRemoved(Level level, BlockPos pos) {
        if (level.isClientSide) return;
        Map<BlockPos, MonitorGroup> idx = index(level);
        MonitorGroup g = idx.remove(pos);
        if (g == null) return;
        if (g.members.size() == 1) return;

        // Conservative split: every remaining member becomes its own 1x1 group; then try to re-merge.
        Set<BlockPos> remaining = new HashSet<>(g.members);
        remaining.remove(pos);
        Direction facing = g.facing;
        for (BlockPos m : remaining) {
            MonitorGroup solo = new MonitorGroup(m, facing, 1, 1, Collections.singleton(m));
            idx.put(m, solo);
            notifyBEJoined(level, m, solo);
        }
        // Attempt regrowth starting from each.
        for (BlockPos m : new ArrayList<>(remaining)) {
            tryGrow(level, m);
        }
    }

    /**
     * Try to grow the group containing {@code pos} by merging rectangular neighbours.
     * Repeats until no more rectangular merges are possible.
     */
    private static void tryGrow(Level level, BlockPos pos) {
        Map<BlockPos, MonitorGroup> idx = index(level);
        boolean changed = true;
        while (changed) {
            changed = false;
            MonitorGroup g = idx.get(pos);
            if (g == null) return;
            Direction facing = g.facing;
            Direction right = rightAxis(facing);

            // For each member's 4 in-plane neighbours, see if the neighbour is a compatible group
            // whose union with us forms a strict rectangle.
            Set<MonitorGroup> candidates = new HashSet<>();
            for (BlockPos m : g.members) {
                for (Direction d : inPlaneAxes(facing)) {
                    BlockPos np = m.relative(d);
                    MonitorGroup ng = idx.get(np);
                    if (ng == null || ng == g) continue;
                    if (ng.facing != facing) continue;
                    candidates.add(ng);
                }
            }
            if (candidates.isEmpty()) return;

            // Try each candidate; pick largest that yields a rectangle within caps.
            MonitorGroup best = null;
            int bestSize = -1;
            for (MonitorGroup c : candidates) {
                MonitorGroup merged = tryMergeRect(g, c, right);
                if (merged == null) continue;
                int sz = merged.cols * merged.rows;
                if (sz > bestSize) { best = merged; bestSize = sz; }
            }
            if (best == null) return;

            applyGroup(level, best, g, findOther(candidates, best));
            changed = true;
        }
    }

    private static List<Direction> inPlaneAxes(Direction facing) {
        Direction right = rightAxis(facing);
        return List.of(right, right.getOpposite(), Direction.UP, Direction.DOWN);
    }

    /**
     * Check that union of {@code a} and {@code b} is a strict rectangle and within size caps.
     * If yes, return the merged group description (origin, cols, rows, members). Buffer is not copied here.
     */
    @Nullable
    private static MonitorGroup tryMergeRect(MonitorGroup a, MonitorGroup b, Direction right) {
        if (a.facing != b.facing) {
            LOG.info("[GroupManager] tryMergeRect reject: facing mismatch a={} b={}", a.facing, b.facing);
            return null;
        }
        Set<BlockPos> union = new HashSet<>(a.members);
        union.addAll(b.members);
        LOG.info("[GroupManager] tryMergeRect a.origin={} a.c={} a.r={} b.origin={} b.c={} b.r={} union={}",
                a.originPos, a.cols, a.rows, b.originPos, b.cols, b.rows, union.size());

        // Compute extent along right & up axes relative to any member (use origin of a).
        BlockPos ref = a.originPos;
        int minC = Integer.MAX_VALUE, maxC = Integer.MIN_VALUE;
        int minR = Integer.MAX_VALUE, maxR = Integer.MIN_VALUE;
        Map<Long, BlockPos> byCR = new HashMap<>();
        for (BlockPos p : union) {
            int c = project(p, ref, right);
            int r = p.getY() - ref.getY();
            if (c < minC) minC = c;
            if (c > maxC) maxC = c;
            if (r < minR) minR = r;
            if (r > maxR) maxR = r;
            byCR.put(((long) c << 32) ^ (r & 0xFFFFFFFFL), p);
        }
        int cols = maxC - minC + 1;
        int rows = maxR - minR + 1;
        if (cols * rows != union.size()) {
            LOG.info("[GroupManager] tryMergeRect reject: holes cols={} rows={} union={}", cols, rows, union.size());
            return null; // holes => not a rect
        }
        if (cols > MAX_COLS || rows > MAX_ROWS) {
            LOG.info("[GroupManager] tryMergeRect reject: exceeds caps cols={} rows={}", cols, rows);
            return null;
        }

        // Verify every (c,r) in rect is present.
        for (int c = minC; c <= maxC; c++) {
            for (int r = minR; r <= maxR; r++) {
                if (!byCR.containsKey(((long) c << 32) ^ (r & 0xFFFFFFFFL))) {
                    LOG.info("[GroupManager] tryMergeRect reject: missing cell c={} r={}", c, r);
                    return null;
                }
            }
        }

        // Compute origin = (minC, minR) member.
        BlockPos origin = byCR.get(((long) minC << 32) ^ (minR & 0xFFFFFFFFL));
        LOG.info("[GroupManager] tryMergeRect OK: origin={} cols={} rows={}", origin, cols, rows);
        return new MonitorGroup(origin, a.facing, cols, rows, union);
    }

    /** Signed offset of {@code p} from {@code ref} along direction axis {@code right}. */
    private static int project(BlockPos p, BlockPos ref, Direction right) {
        int dx = p.getX() - ref.getX();
        int dz = p.getZ() - ref.getZ();
        return dx * right.getStepX() + dz * right.getStepZ();
    }

    @Nullable
    private static MonitorGroup findOther(Set<MonitorGroup> candidates, MonitorGroup best) {
        // The "merged" group `best` contains union of `a` and one candidate. We need to identify which candidate
        // was merged by checking members disjoint from `a`. But we don't have `a` here. Caller handles this —
        // simpler: applyGroup uses best.members and dissolves all old groups touching these positions.
        return null;
    }

    /** Commit the merged group: swap index entries, copy pixels, notify BEs. */
    private static void applyGroup(Level level, MonitorGroup merged, MonitorGroup oldA, @Nullable MonitorGroup unused) {
        Map<BlockPos, MonitorGroup> idx = index(level);
        LOG.info("[GroupManager] applyGroup origin={} cols={} rows={} members={}",
                merged.originPos, merged.cols, merged.rows, merged.members);

        // Collect all distinct old groups that overlap merged.members.
        Set<MonitorGroup> oldGroups = new HashSet<>();
        for (BlockPos p : merged.members) {
            MonitorGroup og = idx.get(p);
            if (og != null) oldGroups.add(og);
        }

        // Create new buffer and copy over old pixels at correct offsets.
        PixelBuffer newBuf = merged.ensureBuffer();
        Direction right = rightAxis(merged.facing);
        for (MonitorGroup og : oldGroups) {
            if (og.buffer == null) continue;
            // Old origin's (col,row) in new group:
            int ogOriginCol = project(og.originPos, merged.originPos, right);
            int ogOriginRow = og.originPos.getY() - merged.originPos.getY();
            // Old origin is bottom-left of old rect. In new group coord system where row=0 is bottom,
            // pixel coords are y=0 top. Compute pixel offset top-left of old rect in new buffer:
            int oldPxW = og.cols * MonitorGroup.BLOCK_W;
            int oldPxH = og.rows * MonitorGroup.BLOCK_H;
            int newPxW = merged.pixelWidth();
            int newPxH = merged.pixelHeight();
            int dstX = ogOriginCol * MonitorGroup.BLOCK_W;
            // rows count from bottom; top of old rect is ogOriginRow + og.rows - 1
            int topRowOfOld = ogOriginRow + og.rows - 1;
            // In pixels from top of merged buffer: rowsFromTop = (merged.rows - 1 - topRowOfOld)
            int dstY = (merged.rows - 1 - topRowOfOld) * MonitorGroup.BLOCK_H;
            byte[] src = og.buffer.copyBytes();
            newBuf.drawImage(dstX, dstY, oldPxW, oldPxH, src);
        }

        // Point every member to merged, overwriting old references.
        for (BlockPos p : merged.members) {
            idx.put(p, merged);
        }
        // Any old group members that are NOT in merged.members stay as their own thing —
        // but by tryMergeRect() construction, old groups are entirely subsumed. Just in case:
        for (MonitorGroup og : oldGroups) {
            for (BlockPos op : og.members) {
                if (!merged.members.contains(op)) {
                    // Orphan — should not happen with strict rect merges, but handle defensively.
                    MonitorGroup solo = new MonitorGroup(op, og.facing, 1, 1, Collections.singleton(op));
                    idx.put(op, solo);
                    notifyBEJoined(level, op, solo);
                }
            }
        }

        merged.dirty = true;

        // Notify every member BE of its new group membership.
        for (BlockPos p : merged.members) {
            notifyBEJoined(level, p, merged);
        }
    }

    private static void notifyBEJoined(Level level, BlockPos pos, MonitorGroup g) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof HDMonBlockEntity hd) {
            Direction right = rightAxis(g.facing);
            int col = project(pos, g.originPos, right);
            int row = pos.getY() - g.originPos.getY();
            hd.joinGroup(g.originPos, col, row, g.cols, g.rows);
        }
    }

    /** Helper for BEs on the server that want to resolve facing from state. */
    public static Direction facingOf(BlockState state) {
        return state.getValue(HorizontalDirectionalBlock.FACING);
    }

    // --- Client-side hint API (no buffer, just shape metadata cache) ---
    // Clients learn shape from BE update tag; they do NOT go through GroupManager.
}
