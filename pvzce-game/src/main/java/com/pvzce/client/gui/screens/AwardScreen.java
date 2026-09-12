package com.pvzce.client.gui.screens;

import com.pvzce.api.util.Identifier;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.GuiLang;
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
 * <p>A won level shows one of two things in the frame: a seed packet dropping into
 * place when this run unlocked a card, or a money bag when it did not (a replay, or
 * a level whose first clear grants nothing). The bag is clickable and showers
 * coins - presentation only. The wallet was written by the server before this
 * packet was even sent, so a player who clicks straight through still keeps every
 * coin.
 */
public final class AwardScreen extends Screen {
    private static final Identifier BACKGROUND =
            Identifier.withDefaultNamespace("textures/gui/screen/award/award_background");
    private static final Identifier MONEY_BAG = Identifier.withDefaultNamespace("textures/gui/award/money_bag");
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

    private boolean bagOpened;

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

    /** True when the frame shows a new card rather than the money bag. */
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
        if (client.hasTexture(BACKGROUND)) {
            client.drawTexture(BACKGROUND, 0, 0, guiW, guiH, -1F, 1F, 1F, 1F, 1F);
        } else {
            renderBackground(0.42F, 0.24F, 0.10F);
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
            renderDroppingCard(fit, guiW, guiH);
        } else {
            renderMoneyBag(fit, guiW, guiH);
        }
        renderSprinkledCoins(fit, guiW, guiH);
        renderNote(fit, guiW, guiH);

        for (var widget : widgets) {
            widget.render(client);
        }
    }

    /**
     * The new card falls into the frame and settles.
     *
     * <p>Eased rather than linear so the packet reads as being dealt; the value is
     * already granted, so a dropped frame only changes how it looks.
     */
    private void renderDroppingCard(CoverFit fit, int guiW, int guiH) {
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
                        unlockedIcon, SeedCardRenderer.CardKind.PLANT, unlockedCost),
                centerX - size / 2F, y, size, cardHeight * fit.scale());
    }

    /** The money bag; clicking it is the only interaction on this page. */
    private void renderMoneyBag(CoverFit fit, int guiW, int guiH) {
        float elapsed = (System.nanoTime() - startNanos) / (float) DROP_NANOS;
        float progress = MathUtil.easeOutCubic(Math.max(0F, Math.min(1F, elapsed)));
        float scale = fit.scale();
        // Sized to the window and settled at its bottom edge: mapY already flips the
        // axis, so subtracting the sprite's height here would push it off the top of
        // the screen instead of leaving room below it.
        float size = scale * Math.min(150F, (WINDOW_BOTTOM - WINDOW_TOP) * 0.82F);
        float centerX = fit.mapX((WINDOW_LEFT + WINDOW_RIGHT) / 2F);
        float settledY = fit.mapY(WINDOW_BOTTOM - 12F, ART_HEIGHT);
        float fromY = fit.mapY(-160F, ART_HEIGHT);
        float y = MathUtil.lerp(fromY, settledY, progress);
        // A click that lands while the bag is still falling should still count.
        client.drawTexture(MONEY_BAG, centerX - size / 2F, y, size, size, 0.3F, 1F, 1F, 1F, 1F);
        bagRect = new float[]{centerX - size / 2F, y, size, size};
    }

    /** Screen rectangle of the money bag, or null while a card is shown. */
    private float[] bagRect;

    private void renderSprinkledCoins(CoverFit fit, int guiW, int guiH) {
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

    /** The parchment strip: what was earned, and the wallet it went into. */
    private void renderNote(CoverFit fit, int guiW, int guiH) {
        float centerX = fit.mapX((WINDOW_LEFT + WINDOW_RIGHT) / 2F);
        float scale = Math.max(0.9F, Math.min(1.5F, guiH / 160F));
        float y = fit.mapY(NOTE_TOP + 8F, ART_HEIGHT);
        String headline;
        if (reward.hasUnlock()) {
            headline = GuiLang.raw("pvzce.award.new_card", "获得新植物！");
            y = drawCentered(headline, centerX, y, scale * 1.15F, 0.35F, 0.22F, 0.05F);
            y = drawCentered(unlockedName, centerX, y - 6F, scale, 0.45F, 0.3F, 0.08F);
        } else {
            headline = GuiLang.raw("pvzce.award.money_bag", "点击钱袋收集金币");
            y = drawCentered(headline, centerX, y, scale, 0.35F, 0.22F, 0.05F);
        }
        String earned = GuiLang.raw("pvzce.award.coins", "金币 +{0}")
                .replace("{0}", String.valueOf(reward.awardedCoins()));
        y = drawCentered(earned, centerX, y - 10F, scale, 0.5F, 0.34F, 0.06F);
        String total = GuiLang.raw("pvzce.award.total", "金币总数：{0}")
                .replace("{0}", String.valueOf(reward.totalCoins()));
        drawCentered(total, centerX, y - 6F, scale * 0.9F, 0.5F, 0.34F, 0.06F);
        if (bagOpened) {
            client.drawSolid(centerX - 2F, y - 2F, 4F, 4F, 0.4F, 0F, 0F, 0F, 0F);
        }
    }

    /** Draws one centred line and returns the y for the next one. */
    private float drawCentered(String text, float centerX, float y, float scale, float r, float g, float b) {
        client.font().draw(text, centerX - client.font().width(text, scale) / 2F, y, scale, r, g, b, 1F);
        return y - client.font().lineHeight(scale) - 2F;
    }

    @Override
    protected void onMouseClicked(double guiX, double guiY, int button) {
        if (button != 0 || showsCard() || bagOpened || bagRect == null) {
            return;
        }
        if (guiX < bagRect[0] || guiX > bagRect[0] + bagRect[2]
                || guiY < bagRect[1] || guiY > bagRect[1] + bagRect[3]) {
            return;
        }
        openBag();
    }

    /**
     * Sprays coins out of the bag.
     *
     * <p>Cosmetic: the coins were banked by the server before this page existed.
     * The sound is the original's money-falls cue.
     */
    private void openBag() {
        bagOpened = true;
        if (bagRect == null) {
            return;
        }
        float centerX = bagRect[0] + bagRect[2] / 2F;
        float centerY = bagRect[1] + bagRect[3] / 2F;
        long now = System.nanoTime();
        for (int i = 0; i < COINS_PER_BURST; i++) {
            coins.add(new Sprinkled(centerX + (random.nextFloat() - 0.5F) * bagRect[2] * 0.5F,
                    centerY + random.nextFloat() * bagRect[3] * 0.3F,
                    (random.nextFloat() - 0.5F) * 260F,
                    180F + random.nextFloat() * 260F, now));
        }
        if (client.sound() != null) {
            client.sound().play(PvzceSounds.UI_MONEY_FALLS.toString(), 1F, 1F);
        }
    }

    /** True once the money bag was clicked; the smoke test asserts the burst happened. */
    public boolean bagOpened() {
        return bagOpened;
    }

    /** How many coins the award page says were earned, for the debug overlay. */
    public int awardedCoins() {
        return reward.awardedCoins();
    }

    @Override
    public void requestClose() {
        client.finishLevelAndShowList();
    }
}
