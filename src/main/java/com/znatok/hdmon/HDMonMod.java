package com.znatok.hdmon;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

@Mod(HDMonMod.MODID)
public class HDMonMod {
    public static final String MODID = "hdmon";

    public HDMonMod(IEventBus modBus) {
        Registries.BLOCKS.register(modBus);
        Registries.ITEMS.register(modBus);
        Registries.BLOCK_ENTITIES.register(modBus);
        Registries.CREATIVE_TABS.register(modBus);
        com.znatok.hdmon.util.FontAtlas.init();
    }
}
