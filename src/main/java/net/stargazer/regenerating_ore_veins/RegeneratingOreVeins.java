package net.stargazer.regenerating_ore_veins;

import com.mojang.logging.LogUtils;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

@Mod(RegeneratingOreVeins.MOD_ID)
public final class RegeneratingOreVeins {
    public static final String MOD_ID = "regenerating_ore_veins";
    public static final Logger LOGGER = LogUtils.getLogger();

    public RegeneratingOreVeins(IEventBus modEventBus) {
        ModContent.register(modEventBus);
        modEventBus.addListener(RegeneratorNetworking::registerPayloads);
        modEventBus.addListener(VeinRuntime::onAddPackFinders);
        NeoForge.EVENT_BUS.register(VeinRuntime.class);
    }

    @EventBusSubscriber(modid = MOD_ID, bus = EventBusSubscriber.Bus.MOD)
    public static final class ClientEvents {
        private ClientEvents() {
        }

        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
            event.enqueueWork(() -> ItemBlockRenderTypes.setRenderLayer(ModContent.REGENERATOR_BLOCK.get(), RenderType.translucent()));
        }
    }
}
