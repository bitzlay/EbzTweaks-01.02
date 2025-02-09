package com.bitzlay.ebztweaks.map.storage;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class RegionManager {
    private static final int REGION_SIZE = 32; // 32x32 chunks
    private final Path worldDir;
    private final Map<Long, MapRegion> loadedRegions = new HashMap<>();
    private final Map<Long, NativeImage> regionMipmaps = new HashMap<>();

    public RegionManager(Level world) {
        worldDir = Minecraft.getInstance().gameDirectory.toPath()
                .resolve("ebztweaks")
                .resolve("worldmap")
                .resolve(world.dimension().location().toString().replace(':', '_'));
        try {
            Files.createDirectories(worldDir);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private MapRegion getRegion(ChunkPos pos) {
        int regionX = Math.floorDiv(pos.x, REGION_SIZE);
        int regionZ = Math.floorDiv(pos.z, REGION_SIZE);
        long regionKey = ((long)regionX << 32) | (regionZ & 0xFFFFFFFFL);

        return loadedRegions.computeIfAbsent(regionKey, k -> {
            File regionFile = worldDir.resolve(String.format("r.%d.%d.map", regionX, regionZ)).toFile();
            return new MapRegion(regionFile, regionX, regionZ);
        });
    }

    public void saveChunk(ChunkPos pos, NativeImage chunkImage) {
        MapRegion region = getRegion(pos);
        region.updateChunk(pos, chunkImage);

        if (region.isDirty()) {
            generateMipmaps(region);
            region.save();
        }
    }

    private void generateMipmaps(MapRegion region) {
        NativeImage baseImage = region.getImage();
        int size = REGION_SIZE * 16;
        int level = 0;

        while (size > 64) {
            size /= 2;
            level++;
            NativeImage mipmap = new NativeImage(NativeImage.Format.RGBA, size, size, true);

            for (int x = 0; x < size; x++) {
                for (int y = 0; y < size; y++) {
                    int color = averageColors(baseImage, x*2, y*2);
                    mipmap.setPixelRGBA(x, y, color);
                }
            }

            regionMipmaps.put(region.getId() * 10 + level, mipmap);
        }
    }

    private int averageColors(NativeImage image, int x, int y) {
        int c1 = image.getPixelRGBA(x, y);
        int c2 = image.getPixelRGBA(x+1, y);
        int c3 = image.getPixelRGBA(x, y+1);
        int c4 = image.getPixelRGBA(x+1, y+1);

        int a = ((c1 >> 24 & 0xFF) + (c2 >> 24 & 0xFF) + (c3 >> 24 & 0xFF) + (c4 >> 24 & 0xFF)) / 4;
        int r = ((c1 >> 16 & 0xFF) + (c2 >> 16 & 0xFF) + (c3 >> 16 & 0xFF) + (c4 >> 16 & 0xFF)) / 4;
        int g = ((c1 >> 8 & 0xFF) + (c2 >> 8 & 0xFF) + (c3 >> 8 & 0xFF) + (c4 >> 8 & 0xFF)) / 4;
        int b = ((c1 & 0xFF) + (c2 & 0xFF) + (c3 & 0xFF) + (c4 & 0xFF)) / 4;

        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    public NativeImage getRegionImage(ChunkPos pos, int zoomLevel) {
        MapRegion region = getRegion(pos);
        if (zoomLevel == 0) return region.getImage();

        return regionMipmaps.getOrDefault(region.getId() * 10 + zoomLevel, region.getImage());
    }

    public boolean hasStoredChunk(ChunkPos pos) {
        int regionX = Math.floorDiv(pos.x, REGION_SIZE);
        int regionZ = Math.floorDiv(pos.z, REGION_SIZE);
        File regionFile = worldDir.resolve(String.format("r.%d.%d.map", regionX, regionZ)).toFile();
        return regionFile.exists();
    }


    public void cleanup() {
        for (MapRegion region : loadedRegions.values()) {
            region.save();
            region.close();
        }
        loadedRegions.clear();

        for (NativeImage mipmap : regionMipmaps.values()) {
            mipmap.close();
        }
        regionMipmaps.clear();
    }
}