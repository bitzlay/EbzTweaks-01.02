package com.bitzlay.ebztweaks.map.core;


import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.client.settings.KeyConflictContext;

public class KeyBindings {
    public static final KeyMapping OPEN_MAP = new KeyMapping(
            "key.worldmap.open",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            InputConstants.KEY_M,
            "key.categories.worldmap"
    );
}