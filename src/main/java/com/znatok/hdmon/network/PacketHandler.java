package com.znatok.hdmon.network;

import com.znatok.hdmon.HDMonMod;
import com.znatok.hdmon.block.HDMonBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

@EventBusSubscriber(modid = HDMonMod.MODID)
public class PacketHandler {

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToClient(
                        FullBufferSyncPacket.TYPE,
                        FullBufferSyncPacket.CODEC,
                        PacketHandler::handleFullBuffer
                )
                .playToClient(
                        TileDiffPacket.TYPE,
                        TileDiffPacket.CODEC,
                        PacketHandler::handleTileDiff
                );
    }

    private static void handleFullBuffer(FullBufferSyncPacket payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            // client side only — Minecraft#level is our client world
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) return;
            BlockEntity be = mc.level.getBlockEntity(payload.pos());
            if (be instanceof HDMonBlockEntity hd) {
                hd.applyBufferSync(payload.cols(), payload.rows(), payload.rgb());
            }
        });
    }

    private static void handleTileDiff(TileDiffPacket payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) return;
            BlockEntity be = mc.level.getBlockEntity(payload.pos());
            if (be instanceof HDMonBlockEntity hd) {
                hd.applyTileDiff(payload.cols(), payload.rows(), payload.tiles());
            }
        });
    }
}
