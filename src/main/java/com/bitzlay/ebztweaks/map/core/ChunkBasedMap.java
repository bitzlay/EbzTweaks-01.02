package com.bitzlay.ebztweaks.map.core;

import com.bitzlay.ebztweaks.EbzTweaks;
import com.bitzlay.ebztweaks.map.storage.ChunkStorageManager;
import com.bitzlay.ebztweaks.map.storage.RegionManager;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.renderer.texture.DynamicTexture;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class ChunkBasedMap {
    private static final int CHUNK_TEXTURE_SIZE = 16;
    public static final int BLOCKS_PER_CHUNK = 16;
    private static final int CACHE_SIZE = 256;
    private final Level world;
    private final Long2ObjectMap<ChunkTexture> chunkTextures = new Long2ObjectOpenHashMap<>();
    private final BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
    private final ChunkStorageManager storageManager;
    private final ExecutorService chunkLoader;
    private volatile boolean isShutdown = false;

    public static class ChunkTexture {
        private final NativeImage image;
        private volatile DynamicTexture texture;
        private final ResourceLocation location;
        private volatile boolean needsUpdate = true;
        private long lastAccess;
        private volatile boolean isInitialized = false;

        public ChunkTexture(String id) {
            image = new NativeImage(NativeImage.Format.RGBA, CHUNK_TEXTURE_SIZE, CHUNK_TEXTURE_SIZE, false);
            clearImage();
            location = new ResourceLocation("ebztweaks", "chunk_" + id);

            // Crear la textura en el hilo principal
            Minecraft.getInstance().execute(() -> {
                texture = new DynamicTexture(image);
                Minecraft.getInstance().getTextureManager().register(location, texture);
                isInitialized = true;
            });

            this.lastAccess = System.currentTimeMillis();
        }

        private void clearImage() {
            for(int x = 0; x < CHUNK_TEXTURE_SIZE; x++) {
                for(int y = 0; y < CHUNK_TEXTURE_SIZE; y++) {
                    image.setPixelRGBA(x, y, 0x00000000);
                }
            }
            needsUpdate = true;
        }

        public void update() {
            if (needsUpdate && isInitialized && texture != null) {
                Minecraft.getInstance().execute(() -> {
                    if (texture != null) {
                        texture.upload();
                        needsUpdate = false;
                    }
                });
            }
            lastAccess = System.currentTimeMillis();
        }

        public void close() {
            if (image != null) {
                image.close();
            }

            if (isInitialized && texture != null) {
                Minecraft.getInstance().execute(() -> {
                    if (texture != null) {
                        texture.close();
                        Minecraft.getInstance().getTextureManager().release(location);
                        texture = null;
                    }
                });
            }
        }

        public NativeImage getImage() {
            return image;
        }
    }

    public ChunkBasedMap(Level world) {
        this.world = world;
        this.storageManager = new ChunkStorageManager(world);
        this.chunkLoader = Executors.newFixedThreadPool(1, r -> {
            Thread thread = new Thread(r, "Chunk-Loader-Thread");
            thread.setDaemon(true);
            return thread;
        });
    }

    public ResourceLocation getChunkTexture(ChunkPos pos) {
        long key = pos.toLong();
        ChunkTexture texture = chunkTextures.get(key);

        if (texture == null) {
            if (chunkTextures.size() > CACHE_SIZE) {
                clearOldChunks();
            }

            texture = new ChunkTexture(pos.x + "_" + pos.z);
            ChunkTexture existing = chunkTextures.putIfAbsent(key, texture);
            if (existing != null) {
                texture.close();
                texture = existing;
            }
        }

        if (texture.needsUpdate && isChunkNearby(pos)) {
            updateChunkTexture(pos, texture);
        }
        texture.update();
        return texture.location;
    }

    private boolean isChunkNearby(ChunkPos pos) {
        ChunkPos playerChunk = new ChunkPos(Minecraft.getInstance().player.blockPosition());
        int dx = Math.abs(pos.x - playerChunk.x);
        int dz = Math.abs(pos.z - playerChunk.z);
        return dx <= 8 && dz <= 8; // Aumentado el rango de chunks cercanos
    }


    private void clearOldChunks() {
        final long currentTime = System.currentTimeMillis();
        final long oldestAllowed = currentTime - 30000; // 30 segundos

        // Crear una lista de las claves a eliminar
        List<Long> keysToRemove = new ArrayList<>();

        // Primero identificamos los chunks a eliminar
        for (Long2ObjectMap.Entry<ChunkTexture> entry : chunkTextures.long2ObjectEntrySet()) {
            ChunkTexture texture = entry.getValue();
            if (texture == null || texture.lastAccess < oldestAllowed) {
                keysToRemove.add(entry.getLongKey());
            }
        }

        // Luego los eliminamos de forma segura
        for (Long key : keysToRemove) {
            ChunkTexture texture = chunkTextures.remove(key);
            if (texture != null) {
                try {
                    texture.close();
                } catch (Exception e) {
                    EbzTweaks.LOGGER.error("Error cerrando textura para chunk " + key, e);
                }
            }
        }
    }

    private void updateChunkTexture(ChunkPos pos, ChunkTexture texture) {
        if (!texture.needsUpdate) return;

        try {
            if (world.hasChunk(pos.x, pos.z)) {
                int baseX = pos.getMinBlockX();
                int baseZ = pos.getMinBlockZ();
                boolean wasUpdated = false;
                boolean hasVisibleBlocks = false;

                // Primero limpiar la imagen
                for(int x = 0; x < CHUNK_TEXTURE_SIZE; x++) {
                    for(int z = 0; z < CHUNK_TEXTURE_SIZE; z++) {
                        texture.image.setPixelRGBA(x, z, 0x00000000);
                    }
                }

                // Luego actualizar con los nuevos datos
                for (int x = 0; x < CHUNK_TEXTURE_SIZE; x++) {
                    for (int z = 0; z < CHUNK_TEXTURE_SIZE; z++) {
                        mutablePos.set(baseX + x, 0, baseZ + z);
                        int color = getHighestBlockColor(mutablePos);
                        texture.image.setPixelRGBA(x, z, color);
                        if (color != 0) {
                            hasVisibleBlocks = true;
                        }
                        wasUpdated = true;
                    }
                }

                if (wasUpdated && hasVisibleBlocks) {
                    storageManager.saveChunk(pos, texture.image);
                }
                texture.needsUpdate = false;
            }
        } catch (Exception e) {
            EbzTweaks.LOGGER.error("Error actualizando chunk " + pos.x + "," + pos.z, e);
        }
    }

    public void loadChunkTexture(ChunkPos pos) {
        long key = pos.toLong();
        ChunkTexture texture = chunkTextures.get(key);

        if (texture == null) {
            if (chunkTextures.size() > CACHE_SIZE) {
                clearOldChunks();
            }

            texture = new ChunkTexture(pos.x + "_" + pos.z);
            ChunkTexture existing = chunkTextures.putIfAbsent(key, texture);
            if (existing != null) {
                texture.close();
                texture = existing;
            }
        }

        // Intentar cargar desde almacenamiento
        if (storageManager.hasStoredChunk(pos)) {
            if (storageManager.loadChunk(pos, texture.image)) {
                texture.needsUpdate = false;
                texture.update();
            }
        }

        // Si no se pudo cargar o necesita actualización
        if (texture.needsUpdate && isChunkNearby(pos)) {
            updateChunkTexture(pos, texture);
        }
    }

    public CompletableFuture<ChunkTexture> loadChunkAsync(ChunkPos pos) {
        if (isShutdown) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                ChunkTexture texture = chunkTextures.computeIfAbsent(pos.toLong(), k ->
                        new ChunkTexture(pos.x + "_" + pos.z));

                if (storageManager.hasStoredChunk(pos)) {
                    if (storageManager.loadChunk(pos, texture.image)) {
                        texture.needsUpdate = false;
                        return texture;
                    }
                }

                if (texture.needsUpdate && isChunkNearby(pos)) {
                    updateChunkTexture(pos, texture);
                }
                return texture;
            } catch (Exception e) {
                EbzTweaks.LOGGER.error("Error en loadChunkAsync: " + pos.x + "," + pos.z, e);
                return null;
            }
        }, chunkLoader);
    }


    private int getHighestBlockColor(BlockPos.MutableBlockPos pos) {
        int startY = Math.min(
                world.getMaxBuildHeight(),
                Minecraft.getInstance().player.blockPosition().getY() + 32
        );

        for (int y = startY; y >= world.getMinBuildHeight(); y--) {
            pos.setY(y);
            BlockState state = world.getBlockState(pos);
            if (!state.isAir()) {
                return MapColorPalette.getColor(state.getBlock());
            }
        }
        return 0x00000000;
    }

    public boolean hasChunkTexture(ChunkPos pos) {
        return chunkTextures.containsKey(pos.toLong()) || storageManager.hasStoredChunk(pos);
    }

    public void cleanup() {
        isShutdown = true;

        // Limpiar las texturas de manera segura
        chunkTextures.values().forEach(texture -> {
            if (texture != null) {
                texture.close();
            }
        });
        chunkTextures.clear();

        chunkLoader.shutdown();
        try {
            if (!chunkLoader.awaitTermination(2, TimeUnit.SECONDS)) {
                chunkLoader.shutdownNow();
            }
        } catch (InterruptedException e) {
            chunkLoader.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}