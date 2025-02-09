package com.bitzlay.ebztweaks.map.storage;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.world.level.ChunkPos;
import java.io.*;

public class MapRegion {
    private static final int REGION_SIZE = 32; // 32x32 chunks
    private static final int CHUNK_SIZE = 16;
    private final int regionX, regionZ;
    private final NativeImage regionImage;
    private boolean isDirty;
    private final File file;
    private final long regionId;

    public MapRegion(File file, int regionX, int regionZ) {
        this.file = file;
        this.regionX = regionX;
        this.regionZ = regionZ;
        this.regionId = ((long)regionX << 32) | (regionZ & 0xFFFFFFFFL);
        this.regionImage = new NativeImage(NativeImage.Format.RGBA,
                REGION_SIZE * CHUNK_SIZE,
                REGION_SIZE * CHUNK_SIZE,
                true);
        loadFromFile();
    }

    private void loadFromFile() {
        if (!file.exists()) return;

        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] colorBuffer = new byte[4];
            for (int z = 0; z < REGION_SIZE * CHUNK_SIZE; z++) {
                for (int x = 0; x < REGION_SIZE * CHUNK_SIZE; x++) {
                    if (fis.read(colorBuffer) != 4) break;
                    int color = ((colorBuffer[0] & 0xFF) << 24) |
                            ((colorBuffer[1] & 0xFF) << 16) |
                            ((colorBuffer[2] & 0xFF) << 8) |
                            (colorBuffer[3] & 0xFF);
                    regionImage.setPixelRGBA(x, z, color);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void updateChunk(ChunkPos pos, NativeImage chunkImage) {
        int relX = Math.floorMod(pos.x, REGION_SIZE) * CHUNK_SIZE;
        int relZ = Math.floorMod(pos.z, REGION_SIZE) * CHUNK_SIZE;

        for (int x = 0; x < CHUNK_SIZE; x++) {
            for (int z = 0; z < CHUNK_SIZE; z++) {
                regionImage.setPixelRGBA(relX + x, relZ + z,
                        chunkImage.getPixelRGBA(x, z));
            }
        }
        isDirty = true;
    }

    public void save() {
        if (!isDirty) return;

        file.getParentFile().mkdirs();
        try (FileOutputStream fos = new FileOutputStream(file)) {
            for (int z = 0; z < REGION_SIZE * CHUNK_SIZE; z++) {
                for (int x = 0; x < REGION_SIZE * CHUNK_SIZE; x++) {
                    int color = regionImage.getPixelRGBA(x, z);
                    fos.write((color >> 24) & 0xFF);
                    fos.write((color >> 16) & 0xFF);
                    fos.write((color >> 8) & 0xFF);
                    fos.write(color & 0xFF);
                }
            }
            isDirty = false;
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public boolean isDirty() {
        return isDirty;
    }

    public NativeImage getImage() {
        return regionImage;
    }

    public long getId() {
        return regionId;
    }

    public int getRegionX() {
        return regionX;
    }

    public int getRegionZ() {
        return regionZ;
    }

    public void close() {
        regionImage.close();
    }
}