package com.bitzlay.ebztweaks.map.core;

import com.bitzlay.ebztweaks.EbzTweaks;
import com.bitzlay.ebztweaks.map.storage.MapChunkManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import org.joml.Vector2d;

import java.util.*;
import java.util.concurrent.*;

public class EfficientMapScreen extends Screen {
    // Constantes del mapa
    private static final double INITIAL_ZOOM = 1.0;
    private static final double MIN_ZOOM = 0.25;
    private static final double MAX_ZOOM = 4.0;
    private static final int CHUNK_SIZE = 16;
    private static final int LOAD_DELAY_MS = 50;
    private static final int MAX_CONCURRENT_LOADS = 8;
    private static final int MAX_CACHED_CHUNKS = 512;

    // Estado del mapa
    private double zoom = INITIAL_ZOOM;
    private final Vector2d offset = new Vector2d();
    private BlockPos playerPos;
    private float playerRotation;
    private boolean showChunkGrid = false;
    private boolean isDragging = false;
    private double lastMouseX, lastMouseY;
    private boolean isFirstFrame = true;
    private long lastRenderTime = 0;

    // Sistema de chunks
    private final MapChunkManager chunkManager;
    private final Map<ChunkPos, CompletableFuture<MapChunkManager.ChunkInfo>> loadingChunks = new ConcurrentHashMap<>();
    private final Set<ChunkPos> visibleChunks = new HashSet<>();
    private final Set<ChunkPos> generatedChunks = new HashSet<>();

    public EfficientMapScreen() {
        super(Component.empty());
        this.chunkManager = new MapChunkManager(Minecraft.getInstance().level);
        this.generatedChunks.addAll(chunkManager.getGeneratedChunks());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        long currentTime = System.currentTimeMillis();

        // Inicialización en el primer frame
        if (isFirstFrame) {
            playerPos = Minecraft.getInstance().player.blockPosition();
            offset.x = playerPos.getX();
            offset.y = playerPos.getZ();
            isFirstFrame = false;
            lastRenderTime = currentTime;
            return;
        }

        renderBackground(graphics);
        updatePlayerPosition();

        // Calcular área visible
        int centerX = width / 2;
        int centerY = height / 2;
        double chunkSize = CHUNK_SIZE * zoom;
        int visibleChunksX = (int)Math.ceil(width / chunkSize) + 2;
        int visibleChunksZ = (int)Math.ceil(height / chunkSize) + 2;

        // Calcular chunk inicial
        double worldX = offset.x - (centerX / zoom);
        double worldZ = offset.y - (centerY / zoom);
        ChunkPos startChunk = new ChunkPos(
                (int)Math.floor(worldX / CHUNK_SIZE),
                (int)Math.floor(worldZ / CHUNK_SIZE)
        );

        // Actualizar chunks visibles
        if (currentTime - lastRenderTime > LOAD_DELAY_MS) {
            processChunkLoading(startChunk, visibleChunksX, visibleChunksZ);
            lastRenderTime = currentTime;
        }

        // Habilitar scissor test para el área del mapa
        graphics.enableScissor(2, 2, width - 2, height - 2);

        // Renderizar chunks
        renderVisibleChunks(graphics, startChunk, visibleChunksX, visibleChunksZ, centerX, centerY);

        // Renderizar elementos adicionales
        renderPlayerMarker(graphics, centerX, centerY);
        if (showChunkGrid) {
            renderChunkGrid(graphics, startChunk, visibleChunksX, visibleChunksZ);
        }

        // Deshabilitar scissor test
        graphics.disableScissor();

        // Renderizar información
        renderCoordinates(graphics, mouseX, mouseY, centerX, centerY);
    }

    private void processChunkLoading(ChunkPos startChunk, int visibleChunksX, int visibleChunksZ) {
        loadingChunks.entrySet().removeIf(entry -> {
            if (entry.getValue().isDone()) {
                try {
                    MapChunkManager.ChunkInfo info = entry.getValue().get();
                    if (info != null) {
                        visibleChunks.add(entry.getKey());
                        if (info.isGenerated() && !generatedChunks.contains(entry.getKey())) {
                            generatedChunks.add(entry.getKey());
                        }
                    }
                } catch (Exception e) {
                    EbzTweaks.LOGGER.error("Error loading chunk: " + entry.getKey(), e);
                }
                return true;
            }
            return false;
        });

        // Determinar radio de carga basado en zoom
        int loadRadius = zoom < 1.0 ? 4 : (zoom < 2.0 ? 6 : 8);
        ChunkPos playerChunk = new ChunkPos(playerPos);

        // Primero cargar chunks cercanos al jugador
        for (int dx = -loadRadius; dx <= loadRadius && loadingChunks.size() < MAX_CONCURRENT_LOADS; dx++) {
            for (int dz = -loadRadius; dz <= loadRadius && loadingChunks.size() < MAX_CONCURRENT_LOADS; dz++) {
                ChunkPos pos = new ChunkPos(playerChunk.x + dx, playerChunk.z + dz);
                queueChunkLoad(pos);
            }
        }

        // Luego cargar el resto de chunks visibles
        if (loadingChunks.size() < MAX_CONCURRENT_LOADS) {
            for (int x = 0; x < visibleChunksX && loadingChunks.size() < MAX_CONCURRENT_LOADS; x++) {
                for (int z = 0; z < visibleChunksZ && loadingChunks.size() < MAX_CONCURRENT_LOADS; z++) {
                    ChunkPos pos = new ChunkPos(startChunk.x + x, startChunk.z + z);
                    queueChunkLoad(pos);
                }
            }
        }

        // Limpiar chunks que ya no son visibles
        visibleChunks.removeIf(chunk -> {
            int dx = Math.abs(chunk.x - playerChunk.x);
            int dz = Math.abs(chunk.z - playerChunk.z);
            return dx > loadRadius * 2 || dz > loadRadius * 2;
        });
    }

    private void queueChunkLoad(ChunkPos pos) {
        if (!visibleChunks.contains(pos) && !loadingChunks.containsKey(pos)) {
            loadingChunks.put(pos, chunkManager.getChunk(pos, zoom));
        }
    }

    private void renderVisibleChunks(GuiGraphics graphics, ChunkPos startChunk,
                                     int visibleChunksX, int visibleChunksZ,
                                     int centerX, int centerY) {
        double chunkSize = CHUNK_SIZE * zoom;
        double offsetX = (offset.x % CHUNK_SIZE) * zoom;
        double offsetZ = (offset.y % CHUNK_SIZE) * zoom;

        for (ChunkPos chunk : visibleChunks) {
            // Calcular posición en pantalla
            double screenX = centerX + (chunk.x * CHUNK_SIZE - offset.x) * zoom;
            double screenY = centerY + (chunk.z * CHUNK_SIZE - offset.y) * zoom;

            if (isChunkOnScreen(screenX, screenY, chunkSize)) {
                renderChunk(graphics, chunk, screenX, screenY, chunkSize);
            }
        }

        // Si zoom está muy lejos, mostrar chunks generados como puntos de color
        if (zoom < 0.5) {
            renderGeneratedChunksOverview(graphics, centerX, centerY);
        }
    }

    private boolean isChunkOnScreen(double screenX, double screenY, double size) {
        return screenX + size >= 0 && screenX <= width &&
                screenY + size >= 0 && screenY <= height;
    }

    private void renderChunk(GuiGraphics graphics, ChunkPos chunk, double screenX, double screenY, double size) {
        try {
            CompletableFuture<MapChunkManager.ChunkInfo> future = loadingChunks.get(chunk);
            if (future != null && future.isDone()) {
                MapChunkManager.ChunkInfo info = future.get();
                if (info != null) {
                    graphics.blit(
                            info.getTexture(),
                            (int)screenX, (int)screenY,
                            (int)size, (int)size,
                            0, 0,
                            CHUNK_SIZE, CHUNK_SIZE,
                            CHUNK_SIZE, CHUNK_SIZE
                    );
                }
            }
        } catch (Exception e) {
            EbzTweaks.LOGGER.error("Error rendering chunk " + chunk, e);
        }
    }

    private void renderGeneratedChunksOverview(GuiGraphics graphics, int centerX, int centerY) {
        for (ChunkPos chunk : generatedChunks) {
            double screenX = centerX + (chunk.x * CHUNK_SIZE - offset.x) * zoom;
            double screenY = centerY + (chunk.z * CHUNK_SIZE - offset.y) * zoom;

            if (isChunkOnScreen(screenX, screenY, zoom * CHUNK_SIZE)) {
                graphics.fill(
                        (int)screenX, (int)screenY,
                        (int)(screenX + zoom * CHUNK_SIZE),
                        (int)(screenY + zoom * CHUNK_SIZE),
                        0x80FFFFFF
                );
            }
        }
    }

    private void renderPlayerMarker(GuiGraphics graphics, int centerX, int centerY) {
        float markerSize = 5.0f;
        float rotation = (float)Math.toRadians(playerRotation + 180);

        int x = (int)(centerX + (playerPos.getX() - offset.x) * zoom);
        int y = (int)(centerY + (playerPos.getZ() - offset.y) * zoom);

        int[] xPoints = new int[3];
        int[] yPoints = new int[3];

        xPoints[0] = x + (int)(Math.sin(rotation) * markerSize);
        yPoints[0] = y - (int)(Math.cos(rotation) * markerSize);
        xPoints[1] = x + (int)(Math.sin(rotation + 2.618f) * markerSize);
        yPoints[1] = y - (int)(Math.cos(rotation + 2.618f) * markerSize);
        xPoints[2] = x + (int)(Math.sin(rotation - 2.618f) * markerSize);
        yPoints[2] = y - (int)(Math.cos(rotation - 2.618f) * markerSize);

        graphics.fill(xPoints[0], yPoints[0], xPoints[1], yPoints[1], 0xFFFF0000);
        graphics.fill(xPoints[1], yPoints[1], xPoints[2], yPoints[2], 0xFFFF0000);
        graphics.fill(xPoints[2], yPoints[2], xPoints[0], yPoints[0], 0xFFFF0000);
    }

    private void renderChunkGrid(GuiGraphics graphics, ChunkPos startChunk,
                                 int visibleChunksX, int visibleChunksZ) {
        double chunkSize = CHUNK_SIZE * zoom;
        double offsetX = (offset.x % CHUNK_SIZE) * zoom;
        double offsetZ = (offset.y % CHUNK_SIZE) * zoom;

        for (int x = 0; x < visibleChunksX; x++) {
            for (int z = 0; z < visibleChunksZ; z++) {
                ChunkPos pos = new ChunkPos(startChunk.x + x, startChunk.z + z);
                double screenX = -offsetX + x * chunkSize;
                double screenY = -offsetZ + z * chunkSize;

                // Dibujar bordes del chunk
                int x1 = (int)screenX;
                int y1 = (int)screenY;
                int x2 = (int)(screenX + chunkSize);
                int y2 = (int)(screenY + chunkSize);

                graphics.fill(x1, y1, x2, y1 + 1, 0x30FFFFFF);
                graphics.fill(x1, y1, x1 + 1, y2, 0x30FFFFFF);
                graphics.fill(x2 - 1, y1, x2, y2, 0x30FFFFFF);
                graphics.fill(x1, y2 - 1, x2, y2, 0x30FFFFFF);

                // Mostrar coordenadas si el zoom es suficiente
                if (zoom > 1.0) {
                    String coords = pos.x + "," + pos.z;
                    graphics.drawString(font, coords, x1 + 2, y1 + 2, 0x80FFFFFF);
                }
            }
        }
    }

    private void renderCoordinates(GuiGraphics graphics, int mouseX, int mouseY, int centerX, int centerY) {
        ChunkPos playerChunk = new ChunkPos(playerPos);
        String coords = String.format("X: %d, Z: %d (Chunk: %d, %d) [Visible: %d, Loading: %d, Generated: %d]",
                playerPos.getX(), playerPos.getZ(),
                playerChunk.x, playerChunk.z,
                visibleChunks.size(), loadingChunks.size(), generatedChunks.size());
        graphics.drawString(font, coords, 5, 5, 0xFFFFFFFF);

        if (isInMapView(mouseX, mouseY)) {
            double worldX = offset.x + (mouseX - centerX) / zoom;
            double worldZ = offset.y + (mouseY - centerY) / zoom;
            String cursorCoords = String.format("Cursor: X:%d Z:%d", (int)worldX, (int)worldZ);
            graphics.drawString(font, cursorCoords, 5, 20, 0xFFFFFFFF);
        }
    }

    private void updatePlayerPosition() {
        playerPos = Minecraft.getInstance().player.blockPosition();
        playerRotation = Minecraft.getInstance().player.getYRot();
    }

    private boolean isInMapView(double mouseX, double mouseY) {
        return mouseX >= 2 && mouseX <= width - 2 && mouseY >= 2 && mouseY <= height - 2;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (isInMapView(mouseX, mouseY) && button == 0) {
            isDragging = true;
            lastMouseX = mouseX;
            lastMouseY = mouseY;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        isDragging = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (isDragging) {
            offset.x -= (mouseX - lastMouseX) / zoom;
            offset.y -= (mouseY - lastMouseY) / zoom;
            lastMouseX = mouseX;
            lastMouseY = mouseY;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (isInMapView(mouseX, mouseY)) {
            double oldZoom = zoom;
            double zoomFactor = delta > 0 ? 1.2 : 0.8;
            zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom * zoomFactor));

            // Ajustar el offset para mantener el punto bajo el cursor
            double worldX = offset.x + (mouseX - width/2.0) / oldZoom;
            double worldZ = offset.y + (mouseY - height/2.0) / oldZoom;
            offset.x = worldX - (mouseX - width/2.0) / zoom;
            offset.y = worldZ - (mouseY - height/2.0) / zoom;

            // Limpiar caché si el cambio de zoom es significativo
            if (Math.abs(oldZoom - zoom) > 0.5) {
                visibleChunks.clear();
                loadingChunks.clear();
            }

            return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 71) { // Tecla G
            showChunkGrid = !showChunkGrid;
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        super.onClose();
        loadingChunks.values().forEach(future -> future.cancel(true));
        loadingChunks.clear();
        visibleChunks.clear();
        chunkManager.cleanup();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void centerOnPlayer() {
        offset.x = playerPos.getX();
        offset.y = playerPos.getZ();
    }

    @Override
    public void tick() {
        super.tick();
        updatePlayerPosition();

        // Auto-centrar si el jugador está muy lejos del centro
        double screenX = width/2 + (playerPos.getX() - offset.x) * zoom;
        double screenY = height/2 + (playerPos.getZ() - offset.y) * zoom;

        if (screenX < width * 0.2 || screenX > width * 0.8 ||
                screenY < height * 0.2 || screenY > height * 0.8) {
            centerOnPlayer();
        }
    }
}