package com.znatok.hdmon;

import com.znatok.hdmon.block.HDMonBlock;
import com.znatok.hdmon.block.HDMonBlockEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public class Registries {
    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(HDMonMod.MODID);
    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(HDMonMod.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(net.minecraft.core.registries.Registries.BLOCK_ENTITY_TYPE, HDMonMod.MODID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
            DeferredRegister.create(net.minecraft.core.registries.Registries.CREATIVE_MODE_TAB, HDMonMod.MODID);

    public static final Supplier<HDMonBlock> HD_MONITOR_BLOCK = BLOCKS.register(
            "hd_monitor",
            () -> new HDMonBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .strength(1.5f)
                    .noOcclusion())
    );

    public static final Supplier<Item> HD_MONITOR_ITEM = ITEMS.register(
            "hd_monitor",
            () -> new BlockItem(HD_MONITOR_BLOCK.get(), new Item.Properties())
    );

    public static final Supplier<BlockEntityType<HDMonBlockEntity>> HD_MONITOR_BE =
            BLOCK_ENTITIES.register("hd_monitor",
                    () -> BlockEntityType.Builder.of(HDMonBlockEntity::new, HD_MONITOR_BLOCK.get()).build(null));

    public static final Supplier<CreativeModeTab> TAB = CREATIVE_TABS.register("hdmon", () -> CreativeModeTab.builder()
            .title(Component.literal("HDMonitor"))
            .icon(() -> HD_MONITOR_ITEM.get().getDefaultInstance())
            .displayItems((params, output) -> output.accept(HD_MONITOR_ITEM.get()))
            .build());
}
