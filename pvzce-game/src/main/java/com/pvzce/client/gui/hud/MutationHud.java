package com.pvzce.client.gui.hud;

import com.pvzce.api.util.Identifier;
import com.pvzce.client.PvzceClient;
import com.pvzce.common.level.mutation.MutationEffects;
import com.pvzce.common.network.packet.MutationStateS2C;

import java.util.List;

/**
 * What the player is told about the mutations running against them.
 *
 * <p>Three pieces, and each answers a different question:
 *
 * <ul>
 *   <li><b>the banner</b> - "something just happened". A mutation may change the rules of the game
 *       under the player's feet, and one that arrived silently would be indistinguishable from a
 *       bug. It announces itself in the middle of the screen for a few seconds, by name and by the
 *       number it rolled.</li>
 *   <li><b>the panel</b> - "what is happening right now". Down the right edge: every mutation on
 *       the field in arrival order, oldest first, with the ones that are waiting or held back
 *       marked as such. That is the list the eviction rule acts on, so showing it in the same
 *       order is what lets a player anticipate what is about to fall off the end.</li>
 *   <li><b>the countdown</b> - "when the next one lands". A clock the player can plan around is a
 *       much smaller thing to fear than a surprise.</li>
 * </ul>
 *
 * <p>Presentation only: nothing here changes the simulation, and the names come from
 * {@code MutationText} on the server's own words through the packet. The panel is deliberately
 * text rather than icons - the catalogue is code and grows, and a mutation with no art is better
 * than a mutation with the wrong art.
 */
public final class MutationHud {
    /** How long a banner stays on screen, in nanoseconds. */
    private static final long BANNER_NANOS = 3_000_000_000L;
    /** The banner's text size. */
    private static final float BANNER_SCALE = 2.2F;
    /** The panel's text size: legible at a glance without covering the board. */
    private static final float PANEL_SCALE = 1.0F;
    /** How far the panel's lines are spaced, in GUI pixels. */
    private static final float PANEL_LINE_HEIGHT = 20F;
    /** The panel's own margin from the right edge. */
    private static final float PANEL_MARGIN = 12F;
    /** The widest the panel may grow, as a fraction of the window. */
    private static final float PANEL_WIDTH_FRACTION = 0.22F;
    /** How dark the darkness effect draws the board, and its layer. */
    private static final float DARKNESS_ALPHA = 0.38F;
    private static final float DARKNESS_Z = 0.62F;
    private static final float FOG_R = 0.04F;
    private static final float FOG_G = 0.05F;
    private static final float FOG_B = 0.14F;

    /** When the banner currently on screen started, or 0. */
    private long bannerNanos;
    /** What that banner says. */
    private String bannerText = "";
    /** How many entries the last state had, so a new one can be spotted. */
    private int seenEntries = -1;

    public MutationHud() {
    }

    /**
     * Applies one state update and starts a banner if a mutation just appeared.
     *
     * <p>"A mutation appeared" is read off the entry count rather than off a flag in the packet:
     * the count only rises when one arrives, and the eviction that follows is a different event
     * that the player has already been warned about by the panel.
     */
    public void apply(MutationStateS2C state) {
        List<MutationStateS2C.Entry> entries = state == null ? List.of() : state.entries();
        if (seenEntries >= 0 && entries.size() > seenEntries) {
            MutationStateS2C.Entry newest = entries.get(entries.size() - 1);
            bannerText = label(newest);
            bannerNanos = System.nanoTime();
        }
        seenEntries = entries.size();
    }

    /** Drops everything; a new level starts with no mutations and no banner. */
    public void reset() {
        bannerNanos = 0L;
        bannerText = "";
        seenEntries = -1;
    }

    /**
     * The dark wash a mutation may ask for.
     *
     * <p>Drawn over the board and under the HUD, in the board's own space: the backdrop is already
     * the night one (the mutation rewrote the level's clock, which the client lights by), and this
     * is the standing haze a night lawn has. A flat translucent quad rather than real fog - the
     * engine has no volumetric pass, and a wash is what the original's own night levels mostly
     * amount to.
     */
    public void renderDarkness(PvzceClient client) {
        if (!MutationEffects.DARKNESS.isSet(client.level().mutationEffects())) {
            return;
        }
        client.drawSolid(0F, 0F, client.guiWidth(), client.guiHeight(), DARKNESS_Z,
                FOG_R, FOG_G, FOG_B, DARKNESS_ALPHA);
    }

    /** The middle-of-the-screen announcement, if one is still running. */
    public void renderBanner(PvzceClient client) {
        if (bannerNanos == 0L || bannerText.isEmpty()) {
            return;
        }
        long elapsed = System.nanoTime() - bannerNanos;
        if (elapsed >= BANNER_NANOS) {
            bannerNanos = 0L;
            return;
        }
        // A fade at each end rather than a hard cut: the banner is a message about something that
        // just changed, and one that snaps on and off reads as a glitch of its own.
        float progress = elapsed / (float) BANNER_NANOS;
        float alpha = Math.min(1F, Math.min(progress * 8F, (1F - progress) * 6F));
        int width = client.guiWidth();
        int height = client.guiHeight();
        float textWidth = client.fonts().button().width(bannerText, BANNER_SCALE);
        float x = (width - textWidth) / 2F;
        float y = height * 0.30F;
        // A dark plate behind the words: the board underneath can be anything from a bright lawn
        // to a black crater field, and white text on the second one is unreadable without it.
        client.drawSolid(x - 18F, y - 8F, textWidth + 36F,
                client.fonts().button().lineHeight(BANNER_SCALE) + 16F, 0.63F,
                0.05F, 0.02F, 0.08F, 0.62F * alpha);
        client.fonts().button().draw(bannerText, x, y, BANNER_SCALE, 1F, 0.85F, 0.35F, alpha);
    }

    /** The list of running mutations down the right edge, with the countdown underneath. */
    public void renderPanel(PvzceClient client) {
        MutationStateS2C state = client.level().mutations();
        if (state == null || (state.entries().isEmpty() && state.ticksUntilNext() <= 0)) {
            return;
        }
        float maxWidth = client.guiWidth() * PANEL_WIDTH_FRACTION;
        float y = 72F;
        for (MutationStateS2C.Entry entry : state.entries()) {
            String line = label(entry);
            float alpha = switch (entry.status()) {
                case "active" -> 1F;
                // Waiting and held-back mutations are on the list because they are on the field -
                // they will act the moment their condition changes - so they are dimmed rather
                // than hidden, which is the difference between "not yet" and "not there".
                default -> 0.45F;
            };
            drawRightAligned(client, line, y, maxWidth, PANEL_SCALE, alpha);
            y += PANEL_LINE_HEIGHT;
            if (y > client.guiHeight() - 120F) {
                // Out of room: the oldest entries are the ones the player has already read, so the
                // list stops rather than running over the card bar.
                break;
            }
        }
        if (state.ticksUntilNext() > 0) {
            int seconds = Math.max(1, Math.round(state.ticksUntilNext() / 60F));
            String next = "下次变异 " + seconds + "s";
            drawRightAligned(client, next, y + 6F, maxWidth, PANEL_SCALE, 0.7F);
        }
    }

    /** One entry's text: its name, the number it rolled, and its state if it is not acting. */
    private static String label(MutationStateS2C.Entry entry) {
        Identifier id = Identifier.tryParse(entry.mutation());
        String name = id == null ? entry.mutation() : mutationName(id);
        if (!entry.subject().isEmpty()) {
            Identifier subject = Identifier.tryParse(entry.subject());
            if (subject != null) {
                name = name + "：" + subjectName(subject);
            }
        }
        if (Math.abs(entry.multiplier() - 1F) >= 0.01F) {
            name = name + " ×" + String.format(java.util.Locale.ROOT, "%.2f", entry.multiplier());
        }
        return switch (entry.status()) {
            case "waiting" -> name + "（待机）";
            case "suppressed" -> name + "（被压制）";
            default -> name;
        };
    }

    /** Draws one line hard against the right edge, inside the panel's own width. */
    private static void drawRightAligned(PvzceClient client, String text, float y, float maxWidth,
                                         float scale, float alpha) {
        String shown = text;
        if (client.fonts().body().width(shown, scale) > maxWidth) {
            // Truncated rather than wrapped: a two-line entry would push everything under it down
            // and the panel's height is the thing that has to stay predictable.
            while (shown.length() > 1 && client.fonts().body().width(shown + "…", scale) > maxWidth) {
                shown = shown.substring(0, shown.length() - 1);
            }
            shown = shown + "…";
        }
        float x = client.guiWidth() - PANEL_MARGIN - client.fonts().body().width(shown, scale);
        // A soft shadow, the same trick the wave meter's name uses: the board under the panel can
        // be any colour, and unshadowed text disappears over the pool's highlights.
        client.fonts().body().draw(shown, x + 1F, y - 1F, scale, 0.02F, 0.02F, 0.05F, 0.85F * alpha);
        client.fonts().body().draw(shown, x, y, scale, 1F, 0.93F, 0.78F, alpha);
    }

    /**
     * The player's name for a mutation id, out of the language file.
     *
     * <p>It used to be a table here that mirrored the server's own - twenty names written twice, in
     * two languages, kept in step by hand. The id is all that crosses the wire, and the client
     * already loads the pack's language file, so the name is a lookup with the id's last segment as
     * the fallback: a mutation a pack adds without a translation is still listed, under its own id.
     */
    private static String mutationName(Identifier id) {
        // `ns.path`, not `ns:path`: that is the shape every content key in the language files has.
        return com.pvzce.client.gui.GuiLang.raw(
                "mutation." + id.namespace() + "." + id.path(), id.path());
    }

    /**
     * The player's name for the subject a mutation rolled.
     *
     * <p>A subject is a zombie (the crisis picks one out of a tag) or a buff, and both already have
     * names in the language file under their own prefixes - so this asks for the zombie's name and
     * falls back to the id, rather than keeping the seven-zombie table that used to live here.
     */
    private static String subjectName(Identifier id) {
        return com.pvzce.client.gui.GuiLang.raw(
                "zombie." + id.namespace() + "." + id.path(), id.path());
    }
}
