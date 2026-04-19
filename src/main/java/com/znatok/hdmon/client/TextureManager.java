package com.znatok.hdmon.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.znatok.hdmon.HDMonMod;
import com.znatok.hdmon.block.HDMonBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * Client-side DynamicTexture cache keyed by the group's origin BlockPos.
 * One texture per group, sized cols*WIDTH by rows*HEIGHT. Non-origin blocks look up
 * the same texture via their BE's origin pointer and render a UV sub-rect.
 */
@EventBusSubscriber(modid = HDMonMod.MODID, value = Dist.CLIENT)
public final class TextureManager {
    private TextureManager() {}

    private static final Map<BlockPos, Entry> entries = new HashMap<>();

    private static class Entry {
        DynamicTexture tex;
        final ResourceLocation rl;
        int width;
        int height;
        boolean dirty = true;
        Entry(DynamicTexture tex, ResourceLocation rl, int w, int h) {
            this.tex = tex; this.rl = rl; this.width = w; this.height = h;
        }
    }

    /** Return the ResourceLocation of the group texture this block renders from. */
    public static ResourceLocation getOrCreate(HDMonBlockEntity be) {
        BlockPos origin = be.getOriginPos();
        int w = be.getCols() * HDMonBlockEntity.WIDTH;
        int h = be.getRows() * HDMonBlockEntity.HEIGHT;

        // Resolve origin BE (might be null if chunk not loaded — fall back to self metadata).
        HDMonBlockEntity originBE = be;
        if (!be.isOrigin() && be.getLevel() != null) {
            BlockEntity o = be.getLevel().getBlockEntity(origin);
            if (o instanceof HDMonBlockEntity hd) {
                originBE = hd;
                w = hd.getCols() * HDMonBlockEntity.WIDTH;
                h = hd.getRows() * HDMonBlockEntity.HEIGHT;
            }
        }

        Entry e = entries.get(origin);
        if (e == null || e.width != w || e.height != h) {
            if (e != null) {
                try { Minecraft.getInstance().getTextureManager().release(e.rl); } catch (Throwable ignored) {}
                try { e.tex.close(); } catch (Throwable ignored) {}
                entries.remove(origin);
            }
            DynamicTexture tex = new DynamicTexture(w, h, false);
            ResourceLocation rl = ResourceLocation.fromNamespaceAndPath(
                    HDMonMod.MODID,
                    "hdmon/" + Math.abs(origin.asLong()));
            Minecraft.getInstance().getTextureManager().register(rl, tex);
            e = new Entry(tex, rl, w, h);
            entries.put(origin, e);
        }
        if (e.dirty && originBE.isOrigin()) {
            upload(e, originBE);
            e.dirty = false;
        }
        return e.rl;
    }

    private static void upload(Entry e, HDMonBlockEntity originBE) {
        NativeImage img = e.tex.getPixels();
        if (img == null) return;
        byte[] rgb = originBE.getOwnBuffer().copyBytes();
        int w = e.width, h = e.height;
        if (rgb.length < w * h * 3) return;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int idx = (y * w + x) * 3;
                int r = rgb[idx] & 0xFF;
                int g = rgb[idx + 1] & 0xFF;
                int b = rgb[idx + 2] & 0xFF;
                int abgr = 0xFF000000 | (b << 16) | (g << 8) | r;
                img.setPixelRGBA(x, y, abgr);
            }
        }
        e.tex.upload();
    }

    public static void markDirty(BlockPos originPos) {
        Entry e = entries.get(originPos);
        if (e != null) e.dirty = true;
    }

    public static void release(BlockPos originPos) {
        Entry e = entries.remove(originPos);
        if (e != null) {
            try { Minecraft.getInstance().getTextureManager().release(e.rl); } catch (Throwable ignored) {}
            try { e.tex.close(); } catch (Throwable ignored) {}
        }
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload evt) {
        if (!(evt.getLevel() instanceof net.minecraft.client.multiplayer.ClientLevel)) return;
        for (Entry e : entries.values()) {
            try { Minecraft.getInstance().getTextureManager().release(e.rl); } catch (Throwable ignored) {}
            try { e.tex.close(); } catch (Throwable ignored) {}
        }
        entries.clear();
    }
}
