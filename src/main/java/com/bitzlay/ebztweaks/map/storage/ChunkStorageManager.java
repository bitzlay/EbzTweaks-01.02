package com.bitzlay.ebztweaks.map.storage;


import com.bitzlay.ebztweaks.EbzTweaks;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class ChunkStorageManager {
    private final Path worldMapDir;
    private final Path chunksDir;

    public ChunkStorageManager(Level world) {
        // Estructura: .minecraft/EbzWorldMap/dimensionId/chunks/
        worldMapDir = Minecraft.getInstance().gameDirectory.toPath()
                .resolve("EbzWorldMap")
                .resolve(world.dimension().location().toString().replace(':', '_'));
        chunksDir = worldMapDir.resolve("chunks");

        try {
            Files.createDirectories(chunksDir);
        } catch (IOException e) {
            EbzTweaks.LOGGER.error("Error creando directorios de almacenamiento", e);
        }
    }

    public void saveChunk(ChunkPos pos, NativeImage chunkImage) {
        Path chunkFile = getChunkFile(pos);
        try (OutputStream os = new GZIPOutputStream(Files.newOutputStream(chunkFile))) {
            // Guardar los datos del chunk
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    int color = chunkImage.getPixelRGBA(x, z);
                    writeInt(os, color);
                }
            }
        } catch (IOException e) {
            EbzTweaks.LOGGER.error("Error guardando chunk " + pos, e);
        }
    }

    public boolean loadChunk(ChunkPos pos, NativeImage targetImage) {
        Path chunkFile = getChunkFile(pos);
        if (!Files.exists(chunkFile)) {
            return false;
        }

        try (InputStream is = new GZIPInputStream(Files.newInputStream(chunkFile))) {
            // Cargar los datos del chunk
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    int color = readInt(is);
                    targetImage.setPixelRGBA(x, z, color);
                }
            }
            return true;
        } catch (IOException e) {
            EbzTweaks.LOGGER.error("Error cargando chunk " + pos, e);
            return false;
        }
    }

    public boolean hasStoredChunk(ChunkPos pos) {
        return Files.exists(getChunkFile(pos));
    }

    private Path getChunkFile(ChunkPos pos) {
        return chunksDir.resolve(String.format("chunk_%d_%d.dat", pos.x, pos.z));
    }

    private void writeInt(OutputStream os, int value) throws IOException {
        os.write((value >> 24) & 0xFF);
        os.write((value >> 16) & 0xFF);
        os.write((value >> 8) & 0xFF);
        os.write(value & 0xFF);
    }

    private int readInt(InputStream is) throws IOException {
        return (is.read() << 24) | (is.read() << 16) | (is.read() << 8) | is.read();
    }
}