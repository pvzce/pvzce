package com.pvzce.client.gui.screens;

import com.pvzce.api.util.Identifier;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.GuiLang;
import com.pvzce.client.gui.Navigation;
import com.pvzce.client.gui.Screen;
import com.pvzce.client.gui.SeedCardRenderer;
import com.pvzce.client.gui.components.Button;
import com.pvzce.client.sound.PvzceMusicController;
import com.pvzce.common.PvzceSounds;
import com.pvzce.common.core.SlotResolver;
import com.pvzce.common.network.packet.LevelRewardS2C;
import com.pvzce.common.util.MathUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The original's end-of-level award page.
 *
 * <p>A won level shows one of two things in the frame: a seed packet dropping into place
 * when this run unlocked a card, or the coins this run paid out when it did not (a replay,
 * or a level whose first clear grants nothing).
 *
 * <p><b>This page is a summary, not a place to collect.</b> The money bag used to be drawn
 * here, and the player had to click it to see the coins - while the coins themselves had
 * already been banked by the server before the packet was even sent. That made the page
 * the second place the same money was handed over, and the one place the lawn's own payout
 * was invisible: the bag now bursts on the board, where the reward lay, and its coins fly
 * into the bank the player has been watching all run. What is left here is the receipt.
 *
 * <p>The page fades in rather than cutting in. It is opened at the end of the reward's
 * white light, so an instant cut would undo the one transition the light exists to make.
 */
public final class AwardScreen extends Screen {
    private static final Identifier BACKGROUND =
            Identifier.withDefaultNamespace("textures/gui/screen/award/award_background");
    private static final Identifier COIN_ICON = Identifier.withDefaultNamespace("textures/resource/coin");

    /** The background art's own pixel size; every panel rectangle below is in these units. */
    private static final int ART_WIDTH = 800;
    private static final int ART_HEIGHT = 600;
    /** The art's green display window and its parchment strip, in art pixels. */
    private static final float WINDOW_LEFT = 248F;
    private static final float WINDOW_TOP = 108F;
    private static final float WINDOW_RIGHT = 552F;
    private static final float WINDOW_BOTTOM = 292F;
    private static final float NOTE_TOP = 342F;
    private static final float NOTE_BOTTOM = 464F;

    private static final long DROP_NANOS = 620_000_000L;
    /**
     * How long the page takes to come up.
     *
     * <p>It is opened at the end of the reward's white light, so this is the tail of that
     * transition rather than a second one: the board is already gone, and what fades in is
     * the page itself.
     */
    private static final long FADE_IN_NANOS = 420_000_000L;
    private static final long COIN_LIFE_NANOS = 1_500_000_000L;
    private static final int COINS_PER_BURST = 18;

    /** One spraying coin: pure presentation, so it lives on the wall clock. */
    private record Sprinkled(float x, float y, float vx, float vy, long startNanos) {
    }

    private final LevelRewardS2C reward;
    private final Identifier unlockedIcon;
    private final String unlockedName;
    /** The new card's own sun cost, so the packet reads like every other card. */
    private final int unlockedCost;
    private final long startNanos = System.nanoTime();
    private final List<Sprinkled> coins = new ArrayList<>();
    private final Random random = new Random();

    /**
     * The opening coin shower, played once.
     *
     * <p>One implementation, two ways in: the animation stage fires it from
     * {@code spawnSprinkledCoins} when the page is drawn from a real payout, and a smoke run
     * can ask for it by name so the effect can be photographed without playing a level.
     */
    private boolean coinBurstPlayed;

    public AwardScreen(PvzceClient client, LevelRewardS2C reward) {
        super(client);
        this.reward = reward;
        SlotResolver.ResolvedCard card = reward.hasUnlock()
                ? SlotResolver.resolve(Identifier.tryParse(reward.unlockedCard())).orElse(null)
                : null;
        this.unlockedIcon = card == null ? null : card.icon().orElse(null);
        this.unlockedCost = card == null ? 0 : card.costSun();
        this.unlockedName = reward.hasUnlock() ? GuiLang.name(reward.unlockedCard()) : "";
    }

    /** True when the frame shows a new card rather than the run's coin payout. */
    private boolean showsCard() {
        return unlockedIcon != null;
    }

    @Override
    protected void init() {
        // The original scores this page with the Zen Garden theme rather than the
        // level's battle track or the win stinger: the level is over, and the page is
        // about what the player walked away with. The stinger is one-shot and has done
        // its job by now, so it is stopped explicitly rather than left to play under
        // the new theme.
        client.music().stopCue(PvzceMusicController.TRACK_STINGER, 0.4F);
        client.music().ensureMenu("pvzce:music/zen_garden");
        int buttonWidth = Math.min(200, client.guiWidth() - 16);
        int buttonHeight = Math.max(30, Math.min(56, client.guiHeight() / 13));
        int y = Math.max(6, (int) (client.guiHeight() * 0.06F));
        // The wooden chooser button, not the default chrome one: this page is a wooden
        // board, and the blue button belongs to the dialogs.
        addWidget(new Button(centerX(buttonWidth), y, buttonWidth, buttonHeight,
                GuiLang.raw("pvzce.award.continue", "继续"), client::finishLevelAndShowList)
                .style(Button.Style.SEED_CHOOSER));
    }

    @Override
    public void tick() {
        long now = System.nanoTime();
        coins.removeIf(coin -> now - coin.startNanos() >= COIN_LIFE_NANOS);
    }

    @Override
    public void render() {
        client.beginGuiView();
        int guiW = client.guiWidth();
        int guiH = client.guiHeight();
        // The whole page, including its widgets, is drawn at this opacity: the fade is a
        // transition into the page, so the button arriving before the board behind it would
        // look like a stray control on the lawn.
        float fade = openingFade();
        if (client.hasTexture(BACKGROUND)) {
            client.drawTexture(BACKGROUND, 0, 0, guiW, guiH, -1F, 1F, 1F, 1F, fade);
        } else {
            client.drawSolid(0F, 0F, guiW, guiH, -1F, 0.42F, 0.24F, 0.10F, fade);
        }
        CoverFit fit = coverFit(ART_WIDTH, ART_HEIGHT);

        // Pinned to the top of the *window*, not to a spot in the artwork: the art is
        // 4:3 and cover-fitted, so on a wide screen its top band - the plaque the title
        // was written for - is cropped away entirely.
        String title = GuiLang.raw("pvzce.award.title_win", "关卡完成！");
        float titleScale = Math.max(1.4F, Math.min(2.6F, guiH / 90F));
        float titleWidth = client.font().width(title, titleScale);
        client.drawSolid((guiW - titleWidth) / 2F - 28F, guiH - client.font().lineHeight(titleScale) - 22F,
                titleWidth + 56F, client.font().lineHeight(titleScale) + 18F,
                0.35F, 0.12F, 0.07F, 0.03F, 0.66F);
        client.font().draw(title, (guiW - titleWidth) / 2F, guiH - client.font().lineHeight(titleScale) - 14F,
                titleScale, 1F, 0.94F, 0.55F, 1F);

        if (showsCard()) {
            renderDroppingCard(fit, guiW, guiH, fade);
        } else {
            // No card: the page's own beat is a shower of the coins this run paid out. It
            // used to need a click on the bag first; the bag is on the lawn now, so here it
            // simply happens, on the same wall clock as the rest of the page.
            spawnSprinkledCoins(fit);
        }
        renderSprinkledCoins(fit);
        renderNote(fit, fade);

        for (var widget : widgets) {
            widget.render(client);
        }
        if (fade < 1F) {
            // A black wash over everything while the page comes up, so the widgets fade in
            // with it rather than sitting on top of a half-transparent board.
            client.drawSolid(0F, 0F, guiW, guiH, 0.95F, 0F, 0F, 0F, 1F - fade);
        }
    }

    /**
     * 0..1 through the page's opening fade.
     *
     * <p>Derived from {@link #startNanos} rather than accumulated: the page is rebuilt on a
     * window resize, and a resize during the fade must not restart or skip it.
     */
    private float openingFade() {
        float elapsed = (System.nanoTime() - startNanos) / (float) FADE_IN_NANOS;
        return MathUtil.clamp01(MathUtil.easeOutCubic(elapsed));
    }

    /**
     * The new card falls into the frame and settles.
     *
     * <p>Eased rather than linear so the packet reads as being dealt; the value is
     * already granted, so a dropped frame only changes how it looks.
     */
    private void renderDroppingCard(CoverFit fit, int guiW, int guiH, float fade) {
        float elapsed = (System.nanoTime() - startNanos) / (float) DROP_NANOS;
        float progress = MathUtil.easeOutCubic(Math.max(0F, Math.min(1F, elapsed)));
        // Sized to the window: a taller packet would reach up over the title plaque
        // once it settled, which is exactly what "dropping into the frame" must not do.
        float cardHeight = (WINDOW_BOTTOM - WINDOW_TOP) * 0.85F;
        float size = fit.scale() * (cardHeight * 100F / 140F);
        float centerX = fit.mapX((WINDOW_LEFT + WINDOW_RIGHT) / 2F);
        float settledY = fit.mapY(WINDOW_BOTTOM - 12F, ART_HEIGHT);
        float fromY = fit.mapY(-160F, ART_HEIGHT);
        float y = MathUtil.lerp(fromY, settledY, progress);
        SeedCardRenderer.draw(client, SeedCardRenderer.CardModel.of(
                        unlockedIcon, SeedCardRenderer.CardKind.PLANT, unlockedCost)
                        .withAlpha(fade),
                centerX - size / 2F, y, size, cardHeight * fit.scale());
    }

    /**
     * The opening coin shower, started once.
     *
     * <p>Where the bag used to be, on the same beat and from the same place: the page pays
     * out what the board already paid out, and the player does not have to ask for it.
     */
    private void spawnSprinkledCoins(CoverFit fit) {
        if (coinBurstPlayed || !coins.isEmpty()) {
            return;
        }
        coinBurstPlayed = true;
        float scale = fit.scale();
        float size = scale * Math.min(150F, (WINDOW_BOTTOM - WINDOW_TOP) * 0.82F);
        float centerX = fit.mapX((WINDOW_LEFT + WINDOW_RIGHT) / 2F);
        float centerY = fit.mapY(WINDOW_BOTTOM - 12F, ART_HEIGHT) + size * 0.5F;
        long now = System.nanoTime();
        for (int i = 0; i < COINS_PER_BURST; i++) {
            coins.add(new Sprinkled(centerX + (random.nextFloat() - 0.5F) * size * 0.5F,
                    centerY + random.nextFloat() * size * 0.3F,
                    (random.nextFloat() - 0.5F) * 260F,
                    180F + random.nextFloat() * 260F, now));
        }
        if (client.sound() != null) {
            // The original's coin-shower cue, which is what this page's own beat has always
            // been scored with.
            client.sound().play(PvzceSounds.UI_MONEY_FALLS.toString(), 1F, 1F);
        }
    }

    /** True once the opening coin shower has been played. For the smoke test. */
    public boolean coinBurstPlayed() {
        return coinBurstPlayed;
    }

    /**
     * The coins of the opening shower, spraying up and falling back through the frame.
     *
     * <p>Position from elapsed time rather than per-frame integration, so the arc is the
     * same whatever the frame rate: the page's only animation must not run at a different
     * speed on a different machine.
     */
    private void renderSprinkledCoins(CoverFit fit) {
        if (coins.isEmpty()) {
            return;
        }
        long now = System.nanoTime();
        float scale = fit.scale();
        float size = scale * 34F;
        for (Sprinkled coin : coins) {
            float life = (now - coin.startNanos()) / (float) COIN_LIFE_NANOS;
            float seconds = (now - coin.startNanos()) / 1_000_000_000F;
            float x = coin.x() + coin.vx() * seconds;
            float y = coin.y() + coin.vy() * seconds - 320F * seconds * seconds;
            float alpha = life < 0.7F ? 1F : Math.max(0F, (1F - life) / 0.3F);
            client.drawTexture(COIN_ICON, x - size / 2F, y, size, size, 0.5F, 1F, 1F, 1F, alpha);
        }
    }

    /**
     * The parchment strip: what was earned, and the wallet it went into.
     *
     * <p>Three lines, and the middle one is the answer to "what did that just do for me":
     * the run's coins, then the wallet's new total. There is no fourth line prompting a
     * click - nothing on this page is clickable except "continue".
     */
    private void renderNote(CoverFit fit, float fade) {
        float centerX = fit.mapX((WINDOW_LEFT + WINDOW_RIGHT) / 2F);
        float scale = Math.max(0.9F, Math.min(1.5F, client.guiHeight() / 160F));
        float y = fit.mapY(NOTE_TOP + 8F, ART_HEIGHT);
        if (reward.hasUnlock()) {
            y = drawCentered(GuiLang.raw("pvzce.award.new_card", "获得新植物！"),
                    centerX, y, scale * 1.15F, 0.35F, 0.22F, 0.05F);
            y = drawCentered(unlockedName, centerX, y - 6F, scale, 0.45F, 0.3F, 0.08F);
        } else {
            // Not "click the bag to collect": there is no bag here any more, and the coins
            // were collected on the board. This line states what the run was worth.
            y = drawCentered(GuiLang.raw("pvzce.award.coins_collected", "本局收集的金币"),
                    centerX, y, scale, 0.35F, 0.22F, 0.05F);
        }
        y = drawCentered(GuiLang.raw("pvzce.award.coins", "金币 +{0}")
                        .replace("{0}", String.valueOf(reward.awardedCoins())),
                centerX, y - 10F, scale, 0.5F, 0.34F, 0.06F);
        drawCentered(GuiLang.raw("pvzce.award.total", "金币总数：{0}")
                        .replace("{0}", String.valueOf(reward.totalCoins())),
                centerX, y - 6F, scale * 0.9F, 0.5F, 0.34F, 0.06F);
    }

    /**
     * Draws one centred line and returns the y for the next one.
     *
     * <p>Always fully opaque: the page's fade is applied as a wash over the finished frame
     * (see {@link #render}), so the note does not carry it - a half-faded line of dark text
     * on a half-faded parchment would be the least readable thing on screen at exactly the
     * wrong moment.
     */
    private float drawCentered(String text, float centerX, float y, float scale, float r, float g, float b) {
        client.font().draw(text, centerX - client.font().width(text, scale) / 2F, y, scale, r, g, b, 1F);
        return y - client.font().lineHeight(scale) - 2F;
    }

    /** How many coins the award page says were earned, for the debug overlay. */
    public int awardedCoins() {
        return reward.awardedCoins();
    }

    /**
     * The award page is reached by {@code openScreen} over a finished level, but backing out
     * of it must not reveal that level - it is over, and the player's next move is the next
     * level. So the destination is the level list as a new root, not a pop.
     *
     * <p>It is also why the award page is the one screen that pushes over a screen whose own
     * back target differs from its own: after a level, the in-game screen is no longer
     * anything the player can return to.
     */
    @Override
    public Navigation backTarget() {
        return Navigation.replaceRoot(LevelSelectScreen::new);
    }

    @Override
    public void requestClose() {
        client.finishLevelAndShowList();
    }
}
