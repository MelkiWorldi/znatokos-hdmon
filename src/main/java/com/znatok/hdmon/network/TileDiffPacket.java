package com.znatok.hdmon.network;

import com.znatok.hdmon.HDMonMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Diff update: a list of Deflater-compressed TILExTILE RGB tiles.
 * Each tile payload decompresses to exactly {@code tileSize*tileSize*3} bytes.
 */
public record TileDiffPacket(BlockPos pos, int cols, int rows, List<Tile> tiles)
        implements CustomPacketPayload {

    public record Tile(short tx, short ty, byte[] compressed) {}

    public static final Type<TileDiffPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(HDMonMod.MODID, "tile_diff"));

    public static final StreamCodec<FriendlyByteBuf, TileDiffPacket> CODEC =
            new StreamCodec<>() {
                @Override
                public TileDiffPacket decode(FriendlyByteBuf buf) {
                    BlockPos pos = buf.readBlockPos();
                    int cols = buf.readVarInt();
                    int rows = buf.readVarInt();
                    int n = buf.readVarInt();
                    List<Tile> list = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) {
                        short tx = buf.readShort();
                        short ty = buf.readShort();
                        int len = buf.readVarInt();
                        byte[] bytes = new byte[len];
                        buf.readBytes(bytes);
                        list.add(new Tile(tx, ty, bytes));
                    }
                    return new TileDiffPacket(pos, cols, rows, list);
                }

                @Override
                public void encode(FriendlyByteBuf buf, TileDiffPacket pkt) {
                    buf.writeBlockPos(pkt.pos);
                    buf.writeVarInt(pkt.cols);
                    buf.writeVarInt(pkt.rows);
                    buf.writeVarInt(pkt.tiles.size());
                    for (Tile t : pkt.tiles) {
                        buf.writeShort(t.tx);
                        buf.writeShort(t.ty);
                        buf.writeVarInt(t.compressed.length);
                        buf.writeBytes(t.compressed);
                    }
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
