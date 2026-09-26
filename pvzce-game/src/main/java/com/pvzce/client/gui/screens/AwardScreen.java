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
 * <p>A won level shows one of three things in the frame: a seed packet dropping into place
 * when this run unlocked a card, the resource it was handed when the level pays in objects
 * (a mini-game's trophy diamond), or the coins this run paid out when it did neither (a
 * replay, or a level whose first clear grants nothing).
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
    private static final Identifier COIN_ICON = Identifier.withDefaultNamespace("textures/resource/coin_gold");

    /** The background art's own pixel size; every panel rectangle below is in these units. */
    private static final int ART_WIDTH = 800;
    private static final int ART_HEIGHT = 600;
    /** The art's green display window and its parchment strip, in art pixels. */
    private static final float WINDOW_LEFT = 248F;
    private static final float WINDOW_TOP = 108F;
    private static final float WINDOW_RIGHT = 552F;
    private static final float WINDOW_BOTTOM = 292F;
    private static final float NOTE_TOP = 342F;
    /**
     * The foot of the note.
     *
     * <p>The parchment strip the original prints its three lines on ends here. A receipt with more
     * than three lines uses the board below it as well - the art has a wide empty plank there, and
     * the alternative is a list that either runs into the "continue" button or stops after one
     * entry.
     */
    private static final float NOTE_BOTTOM = 470F;

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
    /**
     * How many grants the parchment describes in full.
     *
     * <p>A clear pays one or two in practice, and the strip has room for a handful of name +
     * sentence pairs. Past that the page says how many more there were rather than running the
     * last description off the bottom of the art: a receipt that is cut off is worse than one
     * that counts.
     */
    private static final int MAX_LISTED_GRANTS = 4;

    /** One spraying coin: pure presentation, so it lives on the wall clock. */
    private record Sprinkled(float x, float y, float vx, float vy, long startNanos) {
    }

    private final LevelRewardS2C reward;
    private final Identifier unlockedIcon;
    private final String unlockedName;
    /** The new card's own sun cost, so the packet reads like every other card. */
    private final int unlockedCost;
    /**
     * The object this run was handed, when the level pays a {@code resource} reward.
     *
     * <p>Null for every other payout. A card and an item never both take the frame: the card
     * is the bigger news, and the fields below are filled only when there is no card to show.
     */
    private final Identifier rewardItemIcon;
    private final String rewardItemName;
    private final int rewardItemAmount;
    /** The buff this run unlocked, when it unlocked one; null for every other payout. */
    private final Identifier buffIcon;
    private final String buffName;
    private final long startNanos = System.nanoTime();
    private final List<Sprinkled> coins = new ArrayList<>();
    private final Random random = new Random();
    /**
     * The top edge of the "continue" button, in GUI units.
     *
     * <p>Set by {@link #init()} because the button's height depends on the window, and the note
     * above it has to stop there whatever the window is. Zero before the first {@code init}, which
     * is why nothing that reads it runs earlier than the first frame.
     */
    private float continueTop;

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
        // The category comes from the resolved card, not from the id: a level may award a plant
        // or a tool, and the two are named through different registries.
        this.unlockedName = reward.hasUnlock()
                ? GuiLang.name(card == null ? null
                        : com.pvzce.common.core.SlotResolver.languageCategory(card.kind()),
                        Identifier.tryParse(reward.unlockedCard()))
                : "";
        // A buff takes the frame after a card and before an object, in the same order the drop on
        // the lawn asks it, so the two cannot disagree about what was paid. It is drawn in the
        // seed packet's chrome with its own icon - what the level handed over is a new rule, and
        // the page says so the same way it says "a new card".
        boolean buff = !reward.hasUnlock() && reward.hasUnlockedBuff();
        this.buffIcon = buff ? buffIcon(reward.unlockedBuff()) : null;
        this.buffName = buff ? GuiLang.name("level_buff", Identifier.tryParse(reward.unlockedBuff())) : "";
        boolean item = !reward.hasUnlock() && !buff && reward.hasRewardItem();
        Identifier itemId = item ? Identifier.tryParse(reward.rewardItem()) : null;
        com.pvzce.api.content.ResourceDef itemDef = itemId == null
                ? null : com.pvzce.common.core.BuiltInRegistries.RESOURCES.get(itemId);
        // An unknown resource still gets a name and the shared missing-texture tile: the
        // wallet was credited, and a receipt that shows nothing is worse than one that says
        // "something you do not have the art for".
        this.rewardItemIcon = !item ? null
                : (itemDef != null ? itemDef.icon() : com.pvzce.common.core.EntityArt.sprite(itemId));
        this.rewardItemName = item ? GuiLang.name("resource", itemId) : "";
        this.rewardItemAmount = item ? reward.rewardItemAmount() : 0;
    }

    /** True when the frame shows a new card rather than the run's coin payout. */
    private boolean showsCard() {
        return unlockedIcon != null;
    }

    /** True when the frame shows an object the level handed over instead of coins. */
    private boolean showsItem() {
        return rewardItemIcon != null;
    }

    /** True when the frame shows a level buff this run unlocked. */
    private boolean showsBuff() {
        return buffIcon != null || !buffName.isEmpty();
    }

    /** A buff's own sprite, or {@code null} when the buff is unknown or has no art yet. */
    private static Identifier buffIcon(String buffId) {
        Identifier id = Identifier.tryParse(buffId);
        com.pvzce.api.content.LevelBuff buff =
                id == null ? null : com.pvzce.common.buff.LevelBuffs.get(id);
        return buff == null || buff.icon().isEmpty() ? null : buff.icon().texture();
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
        continueTop = y + buttonHeight;
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
        float titleWidth = client.fonts().button().width(title, titleScale);
        client.drawSolid((guiW - titleWidth) / 2F - 28F, guiH - client.fonts().button().lineHeight(titleScale) - 22F,
                titleWidth + 56F, client.fonts().button().lineHeight(titleScale) + 18F,
                0.35F, 0.12F, 0.07F, 0.03F, 0.66F);
        client.fonts().button().draw(title, (guiW - titleWidth) / 2F, guiH - client.fonts().button().lineHeight(titleScale) - 14F,
                titleScale, 1F, 0.94F, 0.55F, 1F);

        if (showsCard()) {
            renderDroppingCard(fit, guiW, guiH, fade);
        } else if (showsBuff()) {
            // A new rule falls into the frame on the same beat as a card, drawn in the same
            // packet chrome: what the level handed over is "something new for your collection",
            // and the money bag would say the opposite.
            renderDroppingBuff(fit, fade);
        } else if (showsItem()) {
            // The level paid in objects: the object falls into the frame, on the same beat
            // and from the same place as the card. No coin shower - what the wallet gained is
            // written below, and a shower of coins over a diamond would say the wrong thing.
            renderDroppingItem(fit, fade);
        } else {
            // No card and no object: the page's own beat is a shower of the coins this run
            // paid out. It used to need a click on the bag first; the bag is on the lawn now,
            // so here it simply happens, on the same wall clock as the rest of the page.
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
     * The coin shower's own beat, for a reward that was an object instead.
     *
     * <p>Same drop-and-settle as the card, and the coin shower's own size and resting place,
     * because the frame is where the coins would have been sprayed: an item landing there
     * reads as the same award in a different currency.
     */
    private void renderDroppingItem(CoverFit fit, float fade) {
        float elapsed = (System.nanoTime() - startNanos) / (float) DROP_NANOS;
        float progress = MathUtil.easeOutCubic(Math.max(0F, Math.min(1F, elapsed)));
        float size = fit.scale() * Math.min(150F, (WINDOW_BOTTOM - WINDOW_TOP) * 0.82F);
        float centerX = fit.mapX((WINDOW_LEFT + WINDOW_RIGHT) / 2F);
        float settledY = fit.mapY(WINDOW_BOTTOM - 12F, ART_HEIGHT);
        float fromY = fit.mapY(-160F, ART_HEIGHT);
        float y = MathUtil.lerp(fromY, settledY, progress);
        client.drawTexture(rewardItemIcon, centerX - size / 2F, y, size, size, 0.5F,
                1F, 1F, 1F, fade);
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
        float scale = Math.max(0.85F, Math.min(1.35F, client.guiHeight() / 180F));
        float width = fit.mapX(ART_WIDTH - 96F) - fit.mapX(96F);
        // The coin line is anchored just above the continue button rather than to the art: the
        // button is the one thing on this page the player must be able to press, and a note whose
        // length depends on what the level paid would otherwise grow straight into it.
        float line = client.fonts().body().lineHeight(scale);
        // Two anchors, and the lower of the two wins: the art's own note band, and the strip just
        // above the continue button. The button is the one thing on this page the player must be
        // able to press, and a note whose length depends on what the level paid would otherwise
        // grow straight into it; the 4:3 art is cropped top and bottom by a 16:9 window, so the
        // band the original drew its three lines in is not always below the button.
        float coinY = Math.max(fit.mapY(NOTE_BOTTOM, ART_HEIGHT), continueTop + 10F);
        float y = fit.mapY(NOTE_TOP + 8F, ART_HEIGHT);
        if (!reward.hasGrants()) {
            // Nothing was handed over: the level paid coins, and the page says so. Not "click the
            // bag to collect": there is no bag here any more, and the coins were collected on the
            // board. These three lines state what the run was worth.
            y = Math.max(y, coinY + line * 2F + 6F);
            y = drawCentered(GuiLang.raw("pvzce.award.coins_collected", "本局收集的金币"),
                    centerX, y, scale, 0.35F, 0.22F, 0.05F);
            y = drawCentered(GuiLang.raw("pvzce.award.coins", "金币 +{0}")
                            .replace("{0}", String.valueOf(reward.awardedCoins())),
                    centerX, y - 4F, scale, 0.5F, 0.34F, 0.06F);
            drawCentered(GuiLang.raw("pvzce.award.total", "金币总数：{0}")
                            .replace("{0}", String.valueOf(reward.totalCoins())),
                    centerX, y - 4F, scale * 0.9F, 0.5F, 0.34F, 0.06F);
            return;
        }
        // Something new: the page's subject is *what*, not how much. Each thing gets its name and
        // its own one-line description - the same sentences the almanac shows - because "获得新植物！"
        // over an unfamiliar name tells a player nothing about what they just earned. The coins
        // are what the wallet gained rather than the news, so they drop to one small line at the
        // foot.
        //
        // The list is bounded twice: by how many entries are worth spelling out, and by the room
        // between the heading and that coin line. It fills greedily one line at a time - a name
        // always beats a sentence, so the player learns *what* they were given even when there is
        // only room for the names, and whatever still does not fit is counted rather than clipped.
        float listFloor = coinY + line + 6F;
        y = drawCentered(headingFor(reward.grants().get(0)), centerX, y, scale * 1.15F,
                0.35F, 0.22F, 0.05F);
        int listed = 0;
        for (LevelRewardS2C.Grant grant : reward.grants()) {
            if (listed >= MAX_LISTED_GRANTS || y - line < listFloor) {
                break;
            }
            y = drawCentered(nameOf(grant), centerX, y - 6F, scale, 0.45F, 0.3F, 0.08F);
            listed++;
            String description = descriptionOf(grant);
            if (!description.isEmpty() && y - line >= listFloor) {
                y = drawCenteredWrapped(description, centerX, y, width, scale * 0.78F,
                        0.42F, 0.3F, 0.12F);
            }
        }
        if (reward.grants().size() > listed) {
            drawCentered(GuiLang.raw("pvzce.award.more_items", "还有 {0} 件")
                            .replace("{0}", String.valueOf(reward.grants().size() - listed)),
                    centerX, listFloor, scale * 0.85F, 0.45F, 0.32F, 0.1F);
        }
        drawCentered(GuiLang.raw("pvzce.award.coins", "金币 +{0}")
                        .replace("{0}", String.valueOf(reward.awardedCoins())),
                centerX, coinY, scale * 0.85F, 0.5F, 0.34F, 0.06F);
    }

    /** The heading over a grant: which registry it came from decides the wording. */
    private static String headingFor(LevelRewardS2C.Grant grant) {
        return switch (grant.kind()) {
            case CARD -> GuiLang.raw("pvzce.award.new_card", "获得新植物！");
            case BUFF -> GuiLang.raw("pvzce.award.new_buff", "获得关卡增益！");
            case ITEM -> GuiLang.raw("pvzce.award.new_item", "获得战利品！");
        };
    }

    /** The language category a grant's id is named through. */
    private static String categoryOf(LevelRewardS2C.Grant grant) {
        return switch (grant.kind()) {
            case CARD -> {
                var card = SlotResolver.resolve(Identifier.tryParse(grant.id())).orElse(null);
                yield card == null ? "plant" : SlotResolver.languageCategory(card.kind());
            }
            case BUFF -> "level_buff";
            case ITEM -> "resource";
        };
    }

    /** A grant's display name, with its count when it has one. */
    private static String nameOf(LevelRewardS2C.Grant grant) {
        String name = GuiLang.name(categoryOf(grant), Identifier.tryParse(grant.id()));
        return grant.kind() == LevelRewardS2C.Grant.Kind.ITEM && grant.amount() > 1
                ? name + " ×" + grant.amount()
                : name;
    }

    /**
     * A grant's own sentence, from the content's language entry.
     *
     * <p>The almanac's line, not a second one written for this page: "what does this plant do" has
     * one answer in this project, and a receipt that paraphrased it would be the second place to
     * update when a plant changes.
     */
    private static String descriptionOf(LevelRewardS2C.Grant grant) {
        return GuiLang.contentOr(categoryOf(grant), Identifier.tryParse(grant.id()), "desc", "");
    }

    /**
     * Centred text that wraps to the parchment's width.
     *
     * <p>{@link #drawCentered} is one line; a plant's description is a sentence, and one line of
     * it would run off the strip. The return value is the next baseline, the same contract.
     */
    private float drawCenteredWrapped(String text, float centerX, float y, float maxWidth,
                                      float scale, float r, float g, float b) {
        for (String line : client.fonts().body().wrapLines(text, maxWidth, scale)) {
            client.fonts().body().draw(line,
                    centerX - client.fonts().body().width(line, scale) / 2F, y, scale, r, g, b, 1F);
            y -= client.fonts().body().lineHeight(scale) - 1F;
        }
        return y;
    }

    /**
     * The new buff falls into the frame and settles, exactly like the card.
     *
     * <p>Same beat, same place, different chrome content: the page should read as "you got
     * something", and animating it differently would only make the player wonder why.
     */
    private void renderDroppingBuff(CoverFit fit, float fade) {
        float elapsed = (System.nanoTime() - startNanos) / (float) DROP_NANOS;
        float progress = MathUtil.easeOutCubic(Math.max(0F, Math.min(1F, elapsed)));
        // The same packet size and resting place as a new card: the frame holds one thing, and
        // "you got something new" should look the same whichever system it came from.
        float cardHeight = (WINDOW_BOTTOM - WINDOW_TOP) * 0.85F;
        float size = fit.scale() * (cardHeight * 100F / 140F);
        float centerX = fit.mapX((WINDOW_LEFT + WINDOW_RIGHT) / 2F);
        float settledY = fit.mapY(WINDOW_BOTTOM - 12F, ART_HEIGHT);
        float fromY = fit.mapY(-160F, ART_HEIGHT);
        float y = MathUtil.lerp(fromY, settledY, progress);
        SeedCardRenderer.draw(client, new SeedCardRenderer.CardModel(
                        buffIcon, SeedCardRenderer.CardKind.BUFF,
                        com.pvzce.common.network.packet.SlotInfo.NO_PRICE,
                        1F, fade, true, 0F, false, null, false),
                centerX - size / 2F, y, size, cardHeight * fit.scale());
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
        client.fonts().body().draw(text, centerX - client.fonts().body().width(text, scale) / 2F, y, scale, r, g, b, 1F);
        return y - client.fonts().body().lineHeight(scale) - 2F;
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
