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

        // The strategist's standing order, in the opposite corner from the diagnostics: it is about
        // the match rather than about the process, and reading it next to the FPS counter buries it.
        List<String> plan = commanderLines(client);
        if (!plan.isEmpty()) {
            drawPlan(client, plan);
        }
        float lineHeight = client.fonts().body().lineHeight(FONT_SCALE) + 2F;
        float maxWidth = 0F;
        for (String line : lines) {
            maxWidth = Math.max(maxWidth, client.fonts().body().width(line, FONT_SCALE));
        }
        float boxWidth = maxWidth + PADDING * 2F;
        float boxHeight = lines.size() * lineHeight + PADDING * 2F;
        float boxX = 2F;
        float boxY = client.guiHeight() - 2F - boxHeight;

        client.drawSolid(boxX, boxY, boxWidth, boxHeight, 0.5F, 0F, 0F, 0F, 0.5F);
        float textY = boxY + boxHeight - PADDING - lineHeight;
        for (String line : lines) {
            client.fonts().body().draw(line, boxX + PADDING, textY, FONT_SCALE, 1F, 1F, 1F, 1F);
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
        long totalSeconds = timeOfDay / com.pvzce.common.PvzceConstants.TICKS_PER_SECOND;
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

    /** The commander's plan as display lines, or empty when there is no commander on this level. */
    private static List<String> commanderLines(PvzceClient client) {
        if (client.level() == null) {
            return List.of();
        }
        var state = client.level().mechanicStateOrNull(com.pvzce.common.PvzceIds.MECHANIC_VERSUS,
                com.pvzce.common.level.mechanic.VersusMechanic.State.class);
        if (state == null || state.commanderPlan().isBlank()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        lines.add(state.commanderBusy() ? "指挥官（正在想…）" : "指挥官");
        for (String raw : state.commanderPlan().split("\n")) {
            // Wrapped by eye rather than by measurement: the F3 overlay is a diagnostic, and a
            // model's paragraph wrapped to the pixel would be more code than the panel is worth.
            String text = raw.trim();
            for (int at = 0; at < text.length(); at += PLAN_WRAP_CHARS) {
                lines.add(text.substring(at, Math.min(text.length(), at + PLAN_WRAP_CHARS)));
            }
        }
        return lines;
    }

    /** Characters per display line for the plan; Chinese text, so a short line. */
    private static final int PLAN_WRAP_CHARS = 32;

    /**
     * Right-aligned in the bottom corner, <b>above</b> the versus HUD's own two panels.
     *
     * <p>Above rather than in the corner itself because the corner is the race's: the opponent's
     * wallet and the player's progress are drawn there, and a diagnostic that covers the numbers a
     * developer is trying to correlate with the plan is worse than one that sits higher. The offset is
     * the height those two panels claim, written here as one constant.
     */
    private static final float PLAN_BOTTOM_MARGIN = 118F;

    private static void drawPlan(PvzceClient client, List<String> lines) {
        float scale = FONT_SCALE;
        float lineHeight = client.fonts().body().lineHeight(scale) + 2F;
        float maxWidth = 0F;
        for (String line : lines) {
            maxWidth = Math.max(maxWidth, client.fonts().body().width(line, scale));
        }
        float boxWidth = maxWidth + PADDING * 2F;
        float boxHeight = lineHeight * lines.size() + PADDING * 2F;
        float x = client.guiWidth() - boxWidth - 16F;
        float y = PLAN_BOTTOM_MARGIN;
        client.drawSolid(x, y, boxWidth, boxHeight, 0F, 0F, 0F, 0.5F, 1F);
        for (int i = 0; i < lines.size(); i++) {
            float lineY = y + boxHeight - PADDING - lineHeight * (i + 1) + 1F;
            client.fonts().body().draw(lines.get(i), x + PADDING, lineY, scale, 1F, 1F, 1F, 1F);
        }
    }
}
