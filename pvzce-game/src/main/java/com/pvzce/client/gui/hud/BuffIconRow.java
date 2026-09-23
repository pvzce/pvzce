package com.pvzce.client.gui.hud;

import com.pvzce.api.content.LevelBuff;
import com.pvzce.api.util.Identifier;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.HoverTip;
import com.pvzce.common.buff.LevelBuffs;

import java.util.ArrayList;
import java.util.List;

/**
 * The row of level-buff icons in the board's bottom-left corner.
 *
 * <p>Where the run's rules are stated. A buff changes how the level is played - resources pick
 * themselves up, mushrooms reach further - and the player switched it on several screens ago, so
 * without this the only evidence a buff exists would be its effect. The icons are small because
 * they are a reminder rather than a control: nothing here is clickable, and hovering one names it.
 *
 * <p>Above the coin bank rather than beside it: that corner is the one piece of the HUD that is
 * sometimes occupied (the coin bank appears for 2.6s whenever the wallet changes), and a row of
 * icons sharing its corner would be covered by it exactly when the player is collecting things -
 * which, with the auto-pickup buff on, is constantly. The bank is 31px tall, so the row starts
 * clear of it and nothing ever overlaps.
 *
 * <p>Not a {@code CardBar}: a bar is a control that is clicked, dragged and hovered for a tooltip
 * about what it would do. This is a label.
 */
public final class BuffIconRow {
    /** One icon's edge, in GUI pixels. The same size as the coin bank's height, deliberately. */
    public static final int ICON_SIZE = 24;
    private static final int GAP = 4;
    /** How far the row sits above the bottom edge: past the coin bank, plus the usual margin. */
    private static final int BOTTOM = com.pvzce.client.gui.hud.cardbar.CardBarLayout.MARGIN
            + com.pvzce.client.gui.hud.cardbar.CardBarLayout.BANK_HEIGHT + 4;

    private BuffIconRow() {
    }

    /** Draws the icons and, under the cursor, the name of the one being pointed at. */
    public static void render(PvzceClient client) {
        render(client, client.guiWidth(), client.guiHeight(), true);
    }

    /**
     * @param withTooltip false draws only the icons - for a caller that puts its own text on top
     *                    and would otherwise have two tips under one cursor
     */
    public static void render(PvzceClient client, int guiWidth, int guiHeight, boolean withTooltip) {
        List<LevelBuff> buffs = activeBuffs(client);
        if (buffs.isEmpty()) {
            return;
        }
        for (int i = 0; i < buffs.size(); i++) {
            float x = com.pvzce.client.gui.hud.cardbar.CardBarLayout.MARGIN + i * (ICON_SIZE + GAP);
            float y = guiHeight - BOTTOM - ICON_SIZE;
            if (x + ICON_SIZE > guiWidth) {
                // A window too narrow for the whole row: the remaining icons are simply not
                // drawn. Wrapping them upward would put rule reminders over the board, which is
                // worse than a truncated reminder.
                return;
            }
            drawIcon(client, buffs.get(i), x, y);
        }
        if (withTooltip) {
            drawHover(client, buffs, guiHeight);
        }
    }

    /** The icons of the buffs this run is played with, in the server's order. */
    private static List<LevelBuff> activeBuffs(PvzceClient client) {
        List<LevelBuff> resolved = new ArrayList<>();
        if (client.level() == null) {
            return resolved;
        }
        for (String raw : client.level().activeBuffs()) {
            Identifier id = Identifier.tryParse(raw);
            LevelBuff buff = id == null ? null : LevelBuffs.get(id);
            if (buff != null) {
                resolved.add(buff);
            }
        }
        return resolved;
    }

    private static void drawIcon(PvzceClient client, LevelBuff buff, float x, float y) {
        // A dark plate under every icon: the buff sprites are borrowed art (a sun, a seed
        // packet) and dropped straight onto the lawn some of them would be unreadable. The plate
        // is the same treatment the coin bank gives its number.
        client.drawSolid(x - 1F, y - 1F, ICON_SIZE + 2F, ICON_SIZE + 2F, 0.12F, 0F, 0F, 0F, 0.42F);
        LevelBuff.BuffIcon icon = buff.icon();
        if (icon == null || icon.isEmpty()) {
            // No art yet: a neutral tile rather than nothing at all, so the row still says "a
            // buff is on" while the icon is still a placeholder.
            client.drawSolid(x, y, ICON_SIZE, ICON_SIZE, 0.13F, 0.35F, 0.55F, 0.35F, 0.9F);
            return;
        }
        if (icon.u0() == 0F && icon.v0() == 0F && icon.u1() == 1F && icon.v1() == 1F) {
            client.drawTexture(icon.texture(), x, y, ICON_SIZE, ICON_SIZE, 0.13F, 1F, 1F, 1F, 1F);
        } else {
            // A sub-rectangle, for an icon cut out of a sheet. Same call the GUI uses for
            // nine-patches, so a future atlas does not need a second path.
            client.drawTextureRegion(icon.texture(), icon.u0(), icon.v0(), icon.u1(), icon.v1(),
                    x, y, ICON_SIZE, ICON_SIZE, 0.13F, 1F, 1F, 1F, 1F);
        }
    }

    private static void drawHover(PvzceClient client, List<LevelBuff> buffs, int guiHeight) {
        double mouseX = client.guiMouseX(client.window().cursorX());
        double mouseY = client.guiMouseY(client.window().cursorY());
        float rowBottom = guiHeight - BOTTOM - ICON_SIZE;
        if (mouseY < rowBottom || mouseY > rowBottom + ICON_SIZE) {
            return;
        }
        for (int i = 0; i < buffs.size(); i++) {
            float x = com.pvzce.client.gui.hud.cardbar.CardBarLayout.MARGIN + i * (ICON_SIZE + GAP);
            if (mouseX < x || mouseX > x + ICON_SIZE) {
                continue;
            }
            Identifier id = LevelBuffs.idOf(buffs.get(i));
            if (id != null) {
                HoverTip.draw(client, HoverTip.buffName(id.toString()),
                        (float) mouseX, (float) mouseY, 1F);
            }
            return;
        }
    }
}
