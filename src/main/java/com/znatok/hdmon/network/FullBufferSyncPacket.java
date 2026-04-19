package com.znatok.hdmon.network;

import com.znatok.hdmon.HDMonMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Full buffer sync from origin BE to clients.
 * {@code pos} = origin BlockPos. {@code rgb.length} = cols*128 * rows*64 * 3.
 */
public record FullBufferSyncPacket(BlockPos pos, int cols, int rows, byte[] rgb) implements CustomPacketPayload {
    public static final Type<FullBufferSyncPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(HDMonMod.MODID, "full_buffer"));

    public static final StreamCodec<FriendlyByteBuf, FullBufferSyncPacket> CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, FullBufferSyncPacket::pos,
            ByteBufCodecs.VAR_INT, FullBufferSyncPacket::cols,
            ByteBufCodecs.VAR_INT, FullBufferSyncPacket::rows,
            ByteBufCodecs.BYTE_ARRAY, FullBufferSyncPacket::rgb,
            FullBufferSyncPacket::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
