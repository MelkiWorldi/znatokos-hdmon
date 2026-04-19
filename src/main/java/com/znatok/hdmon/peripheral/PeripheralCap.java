package com.znatok.hdmon.peripheral;

import com.znatok.hdmon.HDMonMod;
import com.znatok.hdmon.Registries;
import dan200.computercraft.api.peripheral.PeripheralCapability;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

@EventBusSubscriber(modid = HDMonMod.MODID)
public class PeripheralCap {

    @SubscribeEvent
    public static void register(RegisterCapabilitiesEvent event) {
        // CC:Tweaked is optional — only register if loaded.
        if (!ModList.get().isLoaded("computercraft")) return;

        event.registerBlockEntity(
                PeripheralCapability.get(),
                Registries.HD_MONITOR_BE.get(),
                (be, side) -> be.getPeripheral(side)
        );
    }
}
