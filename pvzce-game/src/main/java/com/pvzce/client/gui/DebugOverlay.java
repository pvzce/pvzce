package com.pvzce.client.gui;

import com.pvzce.client.ClientEntity;
import com.pvzce.client.ClientLevel;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.renderer.PvzceCamera;
import com.pvzce.common.network.packet.TimeOfDayS2C;
import com.pvzce.launcher.PvzceVersions;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * MC DebugScreen-shaped F3 overlay. White 1x text on a 50% black background,
 * anchored to the top-left corner and refreshed every frame.
 */
public final class DebugOverlay {
    private static final float FONT_SCALE = 1F;
    private static final int PADDING = 2;

    private DebugOverlay() {
    }

    public static void render(PvzceClient client) {
        ClientLevel level = client.level();
        List<String> lines = new ArrayList<>();
        lines.add(PvzceVersions.GAME_NAME + " " + PvzceVersions.GAME_VERSION);
        lines.add("FPS: " + client.fps());

        String status = level.serverFrozen() ? "，已冻结"
                : level.serverSprinting() ? "，冲刺中" : "";
        float tps = level.serverFrozen() ? 0F : level.measuredTps();
        lines.add(String.format(Locale.ROOT, "TPS: %.1f (目标 %.1f%s)",
                tps, level.targetTps(), status));
        lines.add(mouseLine(client));
        for (String entity : entitiesAtMouse(client)) {
            lines.add(entity);
        }
        lines.add("游戏时间: " + gameTime(level));
        lines.add("波次: " + waveLine(level));

        float lineHeight = client.font().lineHeight(FONT_SCALE) + 2F;
        float maxWidth = 0F;
        for (String line : lines) {
            maxWidth = Math.max(maxWidth, client.font().width(line, FONT_SCALE));
        }
        float boxWidth = maxWidth + PADDING * 2F;
        float boxHeight = lines.size() * lineHeight + PADDING * 2F;
        float boxX = 2F;
        float boxY = client.guiHeight() - 2F - boxHeight;

        client.drawSolid(boxX, boxY, boxWidth, boxHeight, 0.5F, 0F, 0F, 0F, 0.5F);
        float textY = boxY + boxHeight - PADDING - lineHeight;
        for (String line : lines) {
            client.font().draw(line, boxX + PADDING, textY, FONT_SCALE, 1F, 1F, 1F, 1F);
            textY -= lineHeight;
        }
    }

    private static String mouseLine(PvzceClient client) {
        double mouseX = client.window().cursorX();
        double mouseY = client.window().cursorY();
        PvzceCamera camera = client.camera();
        if (!camera.inBoard(mouseX, mouseY)) {
            return "鼠标: 棋盘外 (窗口 " + Math.round(mouseX) + ", " + Math.round(mouseY) + ")";
        }
        float worldX = camera.worldX(mouseX, mouseY);
        float worldY = camera.worldY(mouseX, mouseY);
        int cellX = (int) Math.floor(worldX);
        int cellY = (int) Math.floor(worldY);
        return String.format(Locale.ROOT, "鼠标: 格 (%d, %d) · 世界 (%.2f, %.2f)",
                cellX, cellY, worldX, worldY);
    }

    private static List<String> entitiesAtMouse(PvzceClient client) {
        double mouseX = client.window().cursorX();
        double mouseY = client.window().cursorY();
        PvzceCamera camera = client.camera();
        List<String> lines = new ArrayList<>();
        if (!camera.inBoard(mouseX, mouseY)) {
            lines.add("实体: 棋盘外");
            return lines;
        }
        float worldX = camera.worldX(mouseX, mouseY);
        float worldY = camera.worldY(mouseX, mouseY);
        int cellX = (int) Math.floor(worldX);
        int cellY = (int) Math.floor(worldY);
        if (cellX < 0 || cellX >= client.level().width() || cellY < 0 || cellY >= client.level().height()) {
            lines.add("实体: 关卡外");
            return lines;
        }
        List<ClientEntity> hit = client.level().entities().values().stream()
                .filter(entity -> (int) Math.floor(entity.cellX()) == cellX)
                .filter(entity -> (int) Math.floor(entity.cellY()) == cellY)
                .toList();
        if (hit.isEmpty()) {
            lines.add("实体: 无");
            return lines;
        }
        lines.add("实体 (" + hit.size() + "):");
        for (ClientEntity entity : hit) {
            lines.add(String.format(Locale.ROOT, "  [%s] %s @ (%.2f, %.2f) hp=%d anim=%s",
                    entity.kind(), entity.defId(), entity.cellX(), entity.cellY(),
                    entity.health(), entity.animation()));
        }
        return lines;
    }

    private static String gameTime(ClientLevel level) {
        long totalTicks = Math.max(0L, (long) Math.floor(level.smoothDayTicks()));
        TimeOfDayS2C time = level.timeOfDay();
        int dayLength = Math.max(0, time.dayLength());
        int nightLength = Math.max(0, time.nightLength());
        long cycle = dayLength > 0 ? dayLength + nightLength : 0L;
        long day = cycle > 0 ? totalTicks / cycle : 0L;
        long timeOfDay = cycle > 0 ? totalTicks % cycle : totalTicks;
        long totalSeconds = timeOfDay / 60L;
        long hours = totalSeconds / 3600L;
        long minutes = totalSeconds % 3600L / 60L;
        long seconds = totalSeconds % 60L;
        return String.format(Locale.ROOT, "第%d天 %02d:%02d:%02d (%d tick)",
                day, hours, minutes, seconds, totalTicks);
    }

    private static String waveLine(ClientLevel level) {
        int total = level.totalWaves();
        if (total <= 0) {
            return "无波次配置";
        }
        String line = level.currentWave() + "/" + total;
        return level.finalWave() ? line + " (最终波)" : line;
    }
}
