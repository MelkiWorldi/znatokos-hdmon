package com.znatok.hdmon.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.znatok.hdmon.HDMonMod;
import com.znatok.hdmon.Registries;
import com.znatok.hdmon.block.HDMonBlockEntity;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

@EventBusSubscriber(modid = HDMonMod.MODID, value = Dist.CLIENT)
public class HDMonBER implements BlockEntityRenderer<HDMonBlockEntity> {

    public HDMonBER(BlockEntityRendererProvider.Context ctx) {}

    @SubscribeEvent
    public static void register(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(Registries.HD_MONITOR_BE.get(), HDMonBER::new);
    }

    @Override
    public void render(HDMonBlockEntity be, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        Direction facing = be.getBlockState().getValue(HorizontalDirectionalBlock.FACING);

        ResourceLocation tex = TextureManager.getOrCreate(be);

        pose.pushPose();
        pose.translate(0.5, 0.5, 0.5);
        switch (facing) {
            case NORTH -> pose.mulPose(new Quaternionf().rotateY((float) Math.PI));
            case SOUTH -> {}
            case WEST  -> pose.mulPose(new Quaternionf().rotateY((float) (Math.PI / 2)));
            case EAST  -> pose.mulPose(new Quaternionf().rotateY((float) (-Math.PI / 2)));
            default -> {}
        }
        pose.translate(0, 0, 0.501);

        // Screen surface is emissive — always render at full brightness.
        int light = LightTexture.FULL_BRIGHT;

        VertexConsumer vc = buffers.getBuffer(RenderType.entityCutout(tex));
        Matrix4f mat = pose.last().pose();

        // Edge-to-edge — bezel is painted into the DynamicTexture at the group level
        // (see TextureManager.upload), so adjacent blocks form one continuous frame.
        float hx = 0.5f, hy = 0.5f;
        int r = 255, g = 255, b = 255, a = 255;

        int col = be.getColIndex();
        int row = be.getRowIndex();
        int cols = Math.max(1, be.getCols());
        int rows = Math.max(1, be.getRows());
        float u0 = (float) col / cols;
        float u1 = (float) (col + 1) / cols;
        // row=0 is bottom; V=0 is top of texture. Top block (row=rows-1) -> v0=0.
        float v0 = (float) (rows - 1 - row) / rows;      // top edge of this block in tex
        float v1 = (float) (rows - row) / rows;          // bottom edge

        vc.addVertex(mat, -hx, -hy, 0).setColor(r, g, b, a).setUv(u0, v1).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(0, 0, 1);
        vc.addVertex(mat,  hx, -hy, 0).setColor(r, g, b, a).setUv(u1, v1).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(0, 0, 1);
        vc.addVertex(mat,  hx,  hy, 0).setColor(r, g, b, a).setUv(u1, v0).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(0, 0, 1);
        vc.addVertex(mat, -hx,  hy, 0).setColor(r, g, b, a).setUv(u0, v0).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(0, 0, 1);

        pose.popPose();
    }
}
