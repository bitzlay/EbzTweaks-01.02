package com.bitzlay.ebztweaks.map.core;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import java.util.HashMap;
import java.util.Map;

public class MapColorPalette {
    private static final Map<Block, Integer> COLOR_MAP = new HashMap<>();
    private static final int DEFAULT_COLOR = 0xFF808080; // Gris por defecto

    static {
        // Agua y líquidos
        register(Blocks.WATER, 0xFF3F76E4);
        register(Blocks.LAVA, 0xFFFF5500);

        // Vegetación
        register(Blocks.GRASS_BLOCK, 0xFF91BD59);
        register(Blocks.TALL_GRASS, 0xFF7BB344);
        register(Blocks.FERN, 0xFF7BB344);
        register(Blocks.LARGE_FERN, 0xFF7BB344);
        register(Blocks.AZALEA, 0xFF7BB344);

        // Flores
        register(Blocks.DANDELION, 0xFFFFFF00);
        register(Blocks.POPPY, 0xFFFF0000);
        register(Blocks.BLUE_ORCHID, 0xFF0000FF);
        register(Blocks.ALLIUM, 0xFFFF00FF);

        // Árboles
        register(Blocks.OAK_LOG, 0xFF9B7343);
        register(Blocks.SPRUCE_LOG, 0xFF745A36);
        register(Blocks.BIRCH_LOG, 0xFFD5C9B1);
        register(Blocks.JUNGLE_LOG, 0xFF996633);
        register(Blocks.ACACIA_LOG, 0xFFB1582C);
        register(Blocks.DARK_OAK_LOG, 0xFF4A2E1B);
        register(Blocks.MANGROVE_LOG, 0xFF8B4513);

        register(Blocks.OAK_LEAVES, 0xFF508A41);
        register(Blocks.SPRUCE_LEAVES, 0xFF2F4F2F);
        register(Blocks.BIRCH_LEAVES, 0xFF60A045);
        register(Blocks.JUNGLE_LEAVES, 0xFF30B030);
        register(Blocks.ACACIA_LEAVES, 0xFF508A41);
        register(Blocks.DARK_OAK_LEAVES, 0xFF2F4F2F);
        register(Blocks.MANGROVE_LEAVES, 0xFF4F8A4F);

        // Tierra y arena
        register(Blocks.DIRT, 0xFF8B5E34);
        register(Blocks.COARSE_DIRT, 0xFF7A4B2A);
        register(Blocks.ROOTED_DIRT, 0xFF794F2A);
        register(Blocks.SAND, 0xFFE7DFA7);
        register(Blocks.RED_SAND, 0xFFB76833);
        register(Blocks.GRAVEL, 0xFF8F8F8F);
        register(Blocks.CLAY, 0xFFA4A8B8);
        register(Blocks.MUD, 0xFF4F3B23);
        register(Blocks.MUDDY_MANGROVE_ROOTS, 0xFF4A3B23);

        // Piedra y minerales
        register(Blocks.STONE, 0xFF7F7F7F);
        register(Blocks.GRANITE, 0xFF8F6755);
        register(Blocks.DIORITE, 0xFFCFCFCF);
        register(Blocks.ANDESITE, 0xFF7B7B7B);
        register(Blocks.DEEPSLATE, 0xFF535353);
        register(Blocks.TUFF, 0xFF6B6B6B);
        register(Blocks.CALCITE, 0xFFDBDBDB);

        // Minerales
        register(Blocks.COAL_ORE, 0xFF383838);
        register(Blocks.IRON_ORE, 0xFFBC9980);
        register(Blocks.COPPER_ORE, 0xFFB87855);
        register(Blocks.GOLD_ORE, 0xFFFFDF00);
        register(Blocks.DIAMOND_ORE, 0xFF5DECF5);
        register(Blocks.LAPIS_ORE, 0xFF0000FF);
        register(Blocks.EMERALD_ORE, 0xFF00FF00);
        register(Blocks.REDSTONE_ORE, 0xFFFF0000);

        // Bloques de construcción
        register(Blocks.OAK_PLANKS, 0xFFBC9862);
        register(Blocks.SPRUCE_PLANKS, 0xFF745A36);
        register(Blocks.BIRCH_PLANKS, 0xFFD5C9B1);
        register(Blocks.JUNGLE_PLANKS, 0xFF996633);
        register(Blocks.ACACIA_PLANKS, 0xFFB1582C);
        register(Blocks.DARK_OAK_PLANKS, 0xFF4A2E1B);

        register(Blocks.STONE_BRICKS, 0xFF959595);
        register(Blocks.MOSSY_STONE_BRICKS, 0xFF7F9F7F);
        register(Blocks.CRACKED_STONE_BRICKS, 0xFF858585);
        register(Blocks.CHISELED_STONE_BRICKS, 0xFF9A9A9A);

        register(Blocks.BRICKS, 0xFFA54B36);
        register(Blocks.COBBLESTONE, 0xFF828282);
        register(Blocks.MOSSY_COBBLESTONE, 0xFF728272);

        // Otros
        register(Blocks.BEDROCK, 0xFF1A1A1A);
        register(Blocks.OBSIDIAN, 0xFF1F1F2F);
        register(Blocks.SNOW, 0xFFFFFFFF);
        register(Blocks.SNOW_BLOCK, 0xFFFFFFFF);
        register(Blocks.ICE, 0xFFA5C2FF);
        register(Blocks.PACKED_ICE, 0xFF95B2FF);
        register(Blocks.BLUE_ICE, 0xFF85A2FF);
        register(Blocks.GLASS, 0x66FFFFFF);
        register(Blocks.DIRT_PATH, 0xFF9B8B63);
        register(Blocks.FARMLAND, 0xFF6B4423);
    }

    private static void register(Block block, int color) {
        COLOR_MAP.put(block, color);
    }

    public static int getColor(Block block) {
        if (block == null) {
            return DEFAULT_COLOR;
        }
        return COLOR_MAP.getOrDefault(block, DEFAULT_COLOR);
    }
}