package com.pvzce.client.gui.screens;

import com.pvzce.api.util.Identifier;
import com.pvzce.client.PvzceClient;
import com.pvzce.common.network.packet.LevelListS2C;

/**
 * One row of a level list, drawn the same way wherever levels are listed.
 *
 * <p>Extracted from {@code LevelSelectScreen} when the collection screen arrived: the two screens
 * show the same rows - an icon, a name, a status line, a padlock, a trophy, and now the "contains N
 * levels" a box carries - and a second copy of that layout would be a second answer to what a
 * locked level looks like. What stays with the screens is everything around the row: where it is,
 * which one is selected, and what a click on it does.
 *
 * <p>Stateless. Every question a row asks - whether the player may enter it, whether it has earned
 * a trophy, how many of a box's levels are beaten - is answered from the row's own fields, so a
 * renderer built once can draw every row of every frame.
 */
final class LevelRowRenderer {
    /** The medal a beaten mini-game earns, pinned to the right of its row. */
    private static final Identifier TROPHY =
            Identifier.withDefaultNamespace("textures/gui/screen/level/minigame_trophy");
    private static final float TROPHY_ART_WIDTH = 83F;
    private static final float TROPHY_ART_HEIGHT = 63F;
    /** Padlock badge drawn on a row the player cannot enter yet. */
    private static final Identifier LOCK_ICON =
            Identifier.withDefaultNamespace("textures/gui/icon/lock");

    private final PvzceClient client;
    /**
     * How many of the open list's levels are cleared, by id.
     *
     * <p>Carried in rather than asked of the whole list per row: a collection's row shows the
     * progress of its members, and the members are elsewhere in the same list the screen already
     * holds. The screen builds this once per frame and every row reads it.
     */
    private final java.util.Map<String, Boolean> cleared;

    LevelRowRenderer(PvzceClient client, java.util.Map<String, Boolean> cleared) {
        this.client = client;
        this.cleared = cleared == null ? java.util.Map.of() : cleared;
    }

    /** The cleared flag of every level in a list, which is what a collection's progress counts. */
    static java.util.Map<String, Boolean> clearedIndex(java.util.List<LevelListS2C.LevelInfo> levels) {
        java.util.Map<String, Boolean> index = new java.util.HashMap<>();
        for (LevelListS2C.LevelInfo level : levels) {
            index.put(level.id(), level.cleared());
        }
        return index;
    }

    /**
     * Draws one row.
     *
     * @param level     the row; a level or a collection
     * @param cardX     left edge
     * @param cardY     <em>top</em> edge - every offset below is downward from here
     * @param rowWidth  the row's width
     * @param rowHeight the row's height
     * @param selected  whether to draw the selection frame
     */
    void render(LevelListS2C.LevelInfo level, float cardX, float cardY, float rowWidth,
                float rowHeight, boolean selected) {
        client.drawSolid(cardX - 2, cardY - 2, rowWidth + 4, rowHeight + 4, 0.1F,
                0F, 0F, 0F, 0.35F);
        client.drawSolid(cardX, cardY, rowWidth, rowHeight, 0.1F,
                0.22F, 0.14F, 0.06F, 0.88F);
        if (selected) {
            client.drawSolid(cardX - 3, cardY - 3, rowWidth + 6, rowHeight + 6, 0.2F,
                    1F, 0.9F, 0.2F, 0.75F);
        }

        boolean locked = level.isLocked();
        float iconSize = Math.max(20F, Math.min(rowHeight - 10F, 40F));
        float iconX = cardX + 10F;
        float iconY = cardY + (rowHeight - iconSize) / 2F;
        // A locked level is drawn, but dimmed: hiding it would leave the player with no
        // idea that there is more to do, and the original shows its next level too.
        client.drawTexture(iconTexture(level.icon()), iconX, iconY, iconSize, iconSize,
                0.2F, 1F, 1F, 1F, locked ? 0.45F : 1F);

        float textX = iconX + iconSize + 10F;
        if (locked) {
            float lockSize = Math.max(12F, iconSize * 0.5F);
            client.drawTexture(LOCK_ICON, textX, cardY + (rowHeight - lockSize) / 2F,
                    lockSize, lockSize, 0.3F, 1F, 1F, 1F, 0.9F);
            textX += lockSize + 6F;
        }
        // The trophy a beaten trophy-category level has earned, pinned to the right. It reads
        // `cleared` rather than the status label: a row that says 进行中 because a replay was
        // abandoned is still a row the player has won, and the medal is not taken back by starting
        // over. Everything else on the row yields to it, so a long level name shrinks instead of
        // running underneath the cup.
        float trophyHeight = showsTrophy(level) ? Math.max(16F, Math.min(rowHeight - 12F, iconSize)) : 0F;
        float trophyWidth = trophyHeight * TROPHY_ART_WIDTH / TROPHY_ART_HEIGHT;
        // And the box's own line, in the bottom-right corner: how many levels are in it. Its own
        // reserved strip rather than a share of the trophy's, because a collection is never
        // cleared as a whole and so never earns one - the two never appear on the same row.
        String contained = level.isCollection() ? containedLabel(level) : "";
        float containedWidth = contained.isEmpty() ? 0F
                : client.fonts().body().width(contained, 0.7F) + 8F;
        float reservedRight = 10F + (trophyHeight > 0F ? trophyWidth + 8F : 0F) + containedWidth;
        float availableWidth = Math.max(30F, cardX + rowWidth - reservedRight - textX);
        String name = level.isCollection()
                ? LevelPage.collectionLabel(level.id())
                : (level.name().isEmpty() ? level.id() : level.name());
        float nameScale = 1.1F;
        while (nameScale > 0.55F && client.fonts().body().width(name, nameScale) > availableWidth) {
            nameScale -= 0.05F;
        }
        // A locked row says what is missing instead of "未通关": the condition is the
        // only thing the player can act on.
        String status = statusOf(level, locked);
        float nameY = cardY + rowHeight / 2F - client.fonts().body().lineHeight(nameScale) / 2F;
        if (!status.isEmpty()) {
            nameY += 6F;
        }
        client.fonts().body().draw(name, textX, nameY, nameScale,
                locked ? 0.65F : 1F, locked ? 0.65F : 1F, locked ? 0.6F : 1F, 1F);
        if (!status.isEmpty()) {
            client.fonts().body().draw(status, textX, cardY + rowHeight / 2F - 14F,
                    0.7F, 1F, locked ? 0.6F : 0.9F, locked ? 0.4F : 0.5F, 1F);
        }
        if (selected) {
            String id = level.id();
            float idScale = 0.6F;
            client.fonts().body().draw(id, cardX + rowWidth - reservedRight
                            - client.fonts().body().width(id, idScale),
                    cardY + 5F, idScale, 0.7F, 0.72F, 0.65F, 1F);
        }
        if (trophyHeight > 0F) {
            client.drawTexture(TROPHY, cardX + rowWidth - 10F - trophyWidth,
                    cardY + (rowHeight - trophyHeight) / 2F,
                    trophyWidth, trophyHeight, 0.4F, 1F, 1F, 1F, locked ? 0.75F : 1F);
        }
        if (!contained.isEmpty()) {
            // The corner of the row a box says what is in it: the bottom-right, which is the one
            // strip the row's other text does not use - the name and the status line sit on the
            // left, the trophy in the middle of the right edge, and the selected row's id just
            // inside the reserved strip this label is part of.
            client.fonts().body().draw(contained,
                    cardX + rowWidth - 10F - client.fonts().body().width(contained, 0.7F),
                    cardY + 6F, 0.7F, 1F, 0.92F, 0.62F, 1F);
        }
    }

    /** What a row says under its name: a box's progress, a level's lock, or its status. */
    private String statusOf(LevelListS2C.LevelInfo level, boolean locked) {
        if (level.isCollection()) {
            int total = level.collection().members().size();
            int beaten = 0;
            for (String member : level.collection().members()) {
                if (Boolean.TRUE.equals(cleared.get(member))) {
                    beaten++;
                }
            }
            return com.pvzce.client.gui.GuiLang.raw("pvzce.level_collection.progress", "已通关 {0}/{1}")
                    .replace("{0}", Integer.toString(beaten))
                    .replace("{1}", Integer.toString(total));
        }
        if (locked) {
            String reason = level.unlock().reason();
            if (level.unlock().cost() > 0) {
                reason = reason + "，或 " + level.unlock().cost() + " 金币";
            }
            return reason;
        }
        return LevelSelectScreen.statusLabel(level.status());
    }

    /** "contains N levels", which is what a box shows in the corner of its row. */
    private String containedLabel(LevelListS2C.LevelInfo level) {
        return com.pvzce.client.gui.GuiLang.raw("pvzce.level_collection.contains", "内含 {0} 个关卡")
                .replace("{0}", Integer.toString(level.collection().members().size()));
    }

    /**
     * True when this row has earned its category's trophy: beaten, in a trophy category.
     *
     * <p>Both halves are needed. {@code cleared} is the durable fact the server sends beside the
     * status label, and {@code trophy} is the category's own statement that beating one of its
     * levels is a medal rather than just progress - asked of the registry the client loaded from
     * the same packs, so a pack that marks another category earns trophies there without a code
     * change. An unknown category answers false rather than throwing: a client that is missing the
     * pack still draws the list. A collection never earns one: it is not a level and cannot be
     * beaten, only its members can.
     */
    static boolean showsTrophy(LevelListS2C.LevelInfo level) {
        if (level == null || level.isCollection() || !level.cleared()) {
            return false;
        }
        Identifier category = Identifier.tryParse(level.category() == null ? "" : level.category());
        com.pvzce.api.content.LevelCategoryDef def =
                category == null ? null : com.pvzce.common.core.BuiltInRegistries.LEVEL_CATEGORIES.get(category);
        return def != null && def.trophy();
    }

    /** The almanac ground icon a level's own {@code icon} field names. */
    static Identifier iconTexture(String icon) {
        String path = switch (icon == null ? "day" : icon) {
            case "night" -> "almanac_groundnight";
            case "pool" -> "almanac_groundpool";
            case "night_pool" -> "almanac_groundnightpool";
            case "roof" -> "almanac_groundroof";
            default -> "almanac_groundday";
        };
        return Identifier.withDefaultNamespace("textures/gui/screen/level/" + path);
    }
}
