package com.bitzlay.ebztweaks;


import com.bitzlay.ebztweaks.map.core.EfficientMapScreen;
import com.bitzlay.ebztweaks.map.core.KeyBindings;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;



@Mod(EbzTweaks.MOD_ID)
public class EbzTweaks {
    public static final String MOD_ID = "ebztweaks";
    public static final Logger LOGGER = LogUtils.getLogger();

    public EbzTweaks() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        LOGGER.info("Iniciando registro de EbzTweaks");

        // Registrar MenuTypes
        MinecraftForge.EVENT_BUS.register(this);

        // Registrar eventos del mod
        modEventBus.addListener(this::clientSetup);
        modEventBus.addListener(this::registerOverlays);
        modEventBus.addListener(this::registerKeys);

        // Registrar eventos de forge


        LOGGER.info("Registro de EbzTweaks completado");
    }

    private void registerOverlays(final RegisterGuiOverlaysEvent event) {
        //event.registerAboveAll("custom_hotbar", new CustomHotbarOverlay());
    }

    private void registerKeys(RegisterKeyMappingsEvent event) {
        LOGGER.info("Registrando keybindings");
        event.register(KeyBindings.OPEN_MAP);
    }

    private void clientSetup(final FMLClientSetupEvent event) {
        LOGGER.info("Client setup starting");
        event.enqueueWork(() -> {
            //MenuScreens.register(ModMenuTypes.CUSTOM_INVENTORY.get(), CustomInventoryScreen::new);
        });
    }

    @SubscribeEvent
    public void onKeyInput(InputEvent.Key event) {
        if (KeyBindings.OPEN_MAP.consumeClick()) {
            Minecraft.getInstance().setScreen(new EfficientMapScreen());
        }
    }
}

