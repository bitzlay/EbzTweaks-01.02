package com.bitzlay.ebztweaks.map.storage;

import com.bitzlay.ebztweaks.EbzTweaks;
import com.bitzlay.ebztweaks.map.core.MapColorPalette;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public class MapChunkManager {
    private static final int REGION_SIZE = 32;
    private static final int CHUNK_SIZE = 16;
    private static final int MAX_CACHED_REGIONS = 9;
    private static final int MAX_LOADED_CHUNKS = 256;

    private final Level world;
    private final Path saveDir;
    private final Map<Long, RegionData> loadedRegions = new ConcurrentHashMap<>();
    private final Map<Long, ChunkData> loadedChunks = new ConcurrentHashMap<>();
    private final ExecutorService executor;
    private final Set<ChunkPos> generatedChunks = ConcurrentHashMap.newKeySet();
    private final Queue<ChunkPos> chunkLoadQueue = new ConcurrentLinkedQueue<>();
    private final BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();

    public static class ChunkInfo {
        private final ResourceLocation textureLocation;
        private final boolean generated;

        public ChunkInfo(ResourceLocation textureLocation, boolean generated) {
            this.textureLocation = textureLocation;
            this.generated = generated;
        }

        public ResourceLocation getTexture() {
            return textureLocation;
        }

        public boolean isGenerated() {
            return generated;
        }
    }

    private static class ChunkData {
        private final NativeImage image;
        private final ResourceLocation textureLocation;
        private volatile boolean isInitialized = false;
        private volatile boolean needsUpdate = true;
        private volatile boolean isGenerated = false;
        private long lastAccess;

        ChunkData(ChunkPos pos) {
            this.image = new NativeImage(NativeImage.Format.RGBA, CHUNK_SIZE, CHUNK_SIZE, false);
            this.textureLocation = new ResourceLocation("ebztweaks", "chunk_" + pos.x + "_" + pos.z);
            this.lastAccess = System.currentTimeMillis();

            // Inicializar la textura en el hilo principal
            Minecraft.getInstance().execute(() -> {
                var texture = new net.minecraft.client.renderer.texture.DynamicTexture(image);
                Minecraft.getInstance().getTextureManager().register(textureLocation, texture);
                isInitialized = true;
            });
        }

        void close() {
            if (image != null) {
                image.close();
            }
            if (isInitialized) {
                Minecraft.getInstance().execute(() -> {
                    Minecraft.getInstance().getTextureManager().release(textureLocation);
                });
            }
        }

        ChunkInfo toInfo() {
            return new ChunkInfo(textureLocation, isGenerated);
        }

        void update() {
            if (needsUpdate && isInitialized) {
                Minecraft.getInstance().execute(() -> {
                    needsUpdate = false;
                });
            }
            lastAccess = System.currentTimeMillis();
        }
    }

    private static class RegionData {
        final int regionX, regionZ;
        final NativeImage image;
        long lastAccess;
        final Set<ChunkPos> containedChunks = new HashSet<>();

        RegionData(int x, int z) {
            this.regionX = x;
            this.regionZ = z;
            this.image = new NativeImage(NativeImage.Format.RGBA, REGION_SIZE * CHUNK_SIZE, REGION_SIZE * CHUNK_SIZE, false);
            this.lastAccess = System.currentTimeMillis();
        }

        void close() {
            if (image != null) {
                image.close();
            }
        }
    }

    public MapChunkManager(Level world) {
        this.world = world;
        this.saveDir = Minecraft.getInstance().gameDirectory.toPath()
                .resolve("ebztweaks")
                .resolve("worldmap")
                .resolve(world.dimension().location().toString().replace(':', '_'));

        try {
            Files.createDirectories(saveDir);
        } catch (IOException e) {
            EbzTweaks.LOGGER.error("Error creating save directory", e);
        }

        this.executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "MapChunkManager-Worker");
            t.setDaemon(true);
            return t;
        });

        loadGeneratedChunksIndex();
    }

    public CompletableFuture<ChunkInfo> getChunk(ChunkPos pos, double zoom) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                long chunkKey = pos.toLong();
                ChunkData chunk = loadedChunks.computeIfAbsent(pos.toLong(), k -> {
                    EbzTweaks.LOGGER.info("Creating new chunk data for " + pos.x + "," + pos.z);
                    return new ChunkData(pos);
                });
                chunk.lastAccess = System.currentTimeMillis();

                if (chunk.needsUpdate && isChunkNearby(pos)) {
                    EbzTweaks.LOGGER.info("Updating chunk " + pos.x + "," + pos.z);
                    updateChunk(pos, chunk);
                }
                else if (!chunk.isGenerated && generatedChunks.contains(pos)) {
                    EbzTweaks.LOGGER.info("Loading chunk from disk " + pos.x + "," + pos.z);
                    loadChunkFromDisk(pos, chunk);
                }

                chunk.update();
                return chunk.toInfo();
            } catch (Exception e) {
                EbzTweaks.LOGGER.error("Error getting chunk " + pos, e);
                return null;
            }
        }, executor);
    }

    private void updateChunk(ChunkPos pos, ChunkData chunk) {
        try {
            if (!world.hasChunk(pos.x, pos.z)) {
                EbzTweaks.LOGGER.info("Chunk no disponible: " + pos.x + "," + pos.z);
                return;
            }

            EbzTweaks.LOGGER.info("Actualizando chunk: " + pos.x + "," + pos.z);
            boolean wasUpdated = false;
            int baseX = pos.getMinBlockX();
            int baseZ = pos.getMinBlockZ();

            // Limpiar el chunk primero
            for (int x = 0; x < CHUNK_SIZE; x++) {
                for (int z = 0; z < CHUNK_SIZE; z++) {
                    chunk.image.setPixelRGBA(x, z, 0);
                }
            }

            // Actualizar con nuevos datos
            for (int x = 0; x < CHUNK_SIZE; x++) {
                for (int z = 0; z < CHUNK_SIZE; z++) {
                    int worldX = baseX + x;
                    int worldZ = baseZ + z;
                    int color = getHighestBlockColor(worldX, worldZ);

                    if (color != 0) {
                        chunk.image.setPixelRGBA(x, z, color);
                        wasUpdated = true;
                        if (x % 8 == 0 && z % 8 == 0) {
                            EbzTweaks.LOGGER.info("Color en (" + x + "," + z + "): " +
                                    String.format("0x%08X", color));
                        }
                    }
                }
            }

            if (wasUpdated) {
                EbzTweaks.LOGGER.info("Chunk actualizado: " + pos.x + "," + pos.z);
                saveChunkToDisk(pos, chunk);
                chunk.isGenerated = true;
                generatedChunks.add(pos);
                updateRegionData(pos, chunk);
            } else {
                EbzTweaks.LOGGER.info("No se encontraron bloques para actualizar en el chunk: " +
                        pos.x + "," + pos.z);
            }

            chunk.needsUpdate = false;
            chunk.update();

        } catch (Exception e) {
            EbzTweaks.LOGGER.error("Error actualizando chunk " + pos.x + "," + pos.z, e);
            e.printStackTrace();
        }
    }

    private int getHighestBlockColor(int x, int z) {
        int playerY = Minecraft.getInstance().player.blockPosition().getY();
        int maxY = Math.min(world.getMaxBuildHeight(), playerY + 64);
        int minY = Math.max(world.getMinBuildHeight(), playerY - 64);

        mutablePos.set(x, minY, z);
        BlockState lastState = null;
        int lastY = minY;

        // Buscar desde arriba hacia abajo
        for (int y = maxY; y >= minY; y--) {
            mutablePos.setY(y);
            BlockState state = world.getBlockState(mutablePos);

            // Si encontramos un bloque no aire
            if (!state.isAir()) {
                Block block = state.getBlock();
                int color = MapColorPalette.getColor(block);

                // Log para depuración
                EbzTweaks.LOGGER.info("Block at " + x + "," + y + "," + z + ": " +
                        block.getName().getString() + " with color: " +
                        String.format("0x%08X", color));

                if (color != 0) {
                    return color;
                }
            }
        }

        // Si no encontramos ningún bloque válido, retornar gris transparente
        return 0x44808080;
    }


    private void loadChunkFromDisk(ChunkPos pos, ChunkData chunk) {
        Path chunkFile = getChunkFile(pos);
        if (!Files.exists(chunkFile)) return;

        try (InputStream is = Files.newInputStream(chunkFile)) {
            for (int x = 0; x < CHUNK_SIZE; x++) {
                for (int z = 0; z < CHUNK_SIZE; z++) {
                    chunk.image.setPixelRGBA(x, z, readInt(is));
                }
            }
            chunk.isGenerated = true;
            chunk.needsUpdate = false;
        } catch (IOException e) {
            EbzTweaks.LOGGER.error("Error loading chunk " + pos, e);
        }
    }

    private Path getChunkFile(ChunkPos pos) {
        return saveDir.resolve(String.format("chunk_%d_%d.dat", pos.x, pos.z));
    }

    private void saveChunkToDisk(ChunkPos pos, ChunkData chunk) {
        Path chunkFile = getChunkFile(pos);
        try (OutputStream os = Files.newOutputStream(chunkFile)) {
            for (int x = 0; x < CHUNK_SIZE; x++) {
                for (int z = 0; z < CHUNK_SIZE; z++) {
                    writeInt(os, chunk.image.getPixelRGBA(x, z));
                }
            }
        } catch (IOException e) {
            EbzTweaks.LOGGER.error("Error saving chunk " + pos, e);
        }
    }

    private void updateRegionData(ChunkPos pos, ChunkData chunk) {
        int regionX = Math.floorDiv(pos.x, REGION_SIZE);
        int regionZ = Math.floorDiv(pos.z, REGION_SIZE);
        long regionKey = (((long)regionX) << 32) | (regionZ & 0xFFFFFFFFL);

        RegionData region = loadedRegions.computeIfAbsent(regionKey, k -> new RegionData(regionX, regionZ));
        region.lastAccess = System.currentTimeMillis();
        region.containedChunks.add(pos);

        int relX = Math.floorMod(pos.x, REGION_SIZE) * CHUNK_SIZE;
        int relZ = Math.floorMod(pos.z, REGION_SIZE) * CHUNK_SIZE;

        for (int x = 0; x < CHUNK_SIZE; x++) {
            for (int z = 0; z < CHUNK_SIZE; z++) {
                region.image.setPixelRGBA(relX + x, relZ + z, chunk.image.getPixelRGBA(x, z));
            }
        }
    }

    private void loadGeneratedChunksIndex() {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(saveDir, "chunk_*.dat")) {
            for (Path file : stream) {
                String fileName = file.getFileName().toString();
                String[] parts = fileName.substring(6, fileName.length() - 4).split("_");
                if (parts.length == 2) {
                    try {
                        int x = Integer.parseInt(parts[0]);
                        int z = Integer.parseInt(parts[1]);
                        generatedChunks.add(new ChunkPos(x, z));
                    } catch (NumberFormatException e) {
                        EbzTweaks.LOGGER.error("Invalid chunk filename: " + fileName);
                    }
                }
            }
        } catch (IOException e) {
            EbzTweaks.LOGGER.error("Error loading generated chunks index", e);
        }
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


    private boolean isChunkNearby(ChunkPos pos) {
        ChunkPos playerChunk = new ChunkPos(Minecraft.getInstance().player.blockPosition());
        int dx = Math.abs(pos.x - playerChunk.x);
        int dz = Math.abs(pos.z - playerChunk.z);
        boolean isNearby = dx <= 8 && dz <= 8;
        EbzTweaks.LOGGER.info("Chunk " + pos.x + "," + pos.z + " isNearby: " + isNearby +
                " (dx=" + dx + ", dz=" + dz + ")");
        return isNearby;
    }

    public Set<ChunkPos> getGeneratedChunks() {
        return Collections.unmodifiableSet(generatedChunks);
    }

    public void cleanup() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        for (ChunkData chunk : loadedChunks.values()) {
            chunk.close();
        }
        loadedChunks.clear();

        for (RegionData region : loadedRegions.values()) {
            region.close();
        }
        loadedRegions.clear();
    }
}