package com.pvzce.client.gui.screens;

import com.pvzce.api.util.Identifier;
import com.pvzce.client.renderer.EntityVisuals;
import com.pvzce.client.ClientEntity;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.ResourceCollectAnimation;
import com.pvzce.client.gui.Screen;
import com.pvzce.client.gui.SeedCardRenderer;
import com.pvzce.common.core.SlotResolver;
import com.pvzce.common.network.packet.LevelRewardS2C;
import com.pvzce.common.util.MathUtil;
import com.pvzce.client.gui.components.Button;
import com.pvzce.client.gui.components.Dialog;
import com.pvzce.client.renderer.PvzceCamera;
import com.pvzce.client.renderer.LevelStage;
import com.pvzce.client.renderer.SceneTileRenderer;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.api.content.SlotDef;
import com.pvzce.common.network.packet.CollectResourceC2S;
import com.pvzce.common.network.packet.EffectEventS2C;
import com.pvzce.common.network.packet.PickCardC2S;
import com.pvzce.common.network.packet.PlacePlantC2S;
import com.pvzce.common.network.packet.PauseGameC2S;
import com.pvzce.common.network.packet.SetGameSpeedC2S;
import com.pvzce.common.network.packet.SlotInfo;
import com.pvzce.common.network.packet.UseToolC2S;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** The playable level: board rendering + card bar + HUD. */
public final class InGameScreen extends Screen {
    private static final Identifier SEED_PACKET = Identifier.withDefaultNamespace("textures/gui/hud/seed_packet");
    private static final Identifier SHOVEL_BANK = Identifier.withDefaultNamespace("textures/gui/hud/shovel_bank");
    private static final Identifier SUN_BANK = Identifier.withDefaultNamespace("textures/gui/hud/sun_bank");
    /** The resource card that toggles the sun bank instead of a normal packet. */
    private static final String SUN_CARD_ID = com.pvzce.common.PvzceIds.SUN.toString();
    /** Coins get their own bank in the corner; they are not spendable in a level. */
    private static final Identifier COIN_ICON = Identifier.withDefaultNamespace("textures/resource/coin_gold");
    /** The original's money-bag bank; drawn at 128x31 natively. */
    private static final Identifier COIN_BANK = Identifier.withDefaultNamespace("textures/gui/award/coin_bank");
    /** The original's flag meter: the track (top half) and the green fill (bottom half). */
    private static final Identifier FLAG_METER = Identifier.withDefaultNamespace("textures/gui/hud/flag_meter");
    /** Zombie head, pole and flag, on one 75x25 sheet. */
    private static final Identifier FLAG_METER_PARTS =
            Identifier.withDefaultNamespace("textures/gui/hud/flag_meter_parts");
    private static final Identifier FLAG_METER_LABEL =
            Identifier.withDefaultNamespace("textures/gui/hud/flag_meter_label");
    /** One bar of {@link #FLAG_METER}, which stacks two of them. */
    private static final float METER_NATIVE_HEIGHT = 23F;
    /** The bar's width in the art; the meter keeps this ratio instead of stretching. */
    private static final float METER_NATIVE_WIDTH = 158F;
    /** The whole sheet, for converting pixel rows to V. */
    private static final float FLAG_METER_NATIVE_HEIGHT = 54F;
    /**
     * Drawn at the sun bank's scale ({@code 70/78}), so the HUD's pieces agree on how big
     * an original pixel is. The meter is a fixed-size gauge in the original, not a stretchy
     * bar, and stretching it to a third of the screen made the caps oval and the flags
     * float apart.
     */
    private static final float METER_SCALE = 0.9F;
    /**
     * The two bars inside {@link #FLAG_METER}, as image rows from the top.
     *
     * <p>Both live in one 158x54 texture: the empty track on top (rows 1..24) and the
     * green fill below it (rows 28..51). Textures are loaded flipped - UV (0,0) is the
     * bottom-left corner (see {@code TextureManager}) - so these are converted with
     * {@link #vFromTop} rather than used as V directly.
     */
    private static final float[] METER_TRACK_ROWS = {1F, 24F};
    private static final float[] METER_FILL_ROWS = {28F, 51F};
    /** The sheet the parts below are cut from. */
    private static final float METER_PARTS_WIDTH = 75F;
    private static final float METER_PARTS_HEIGHT = 25F;
    /** Pixel rectangles inside {@link #FLAG_METER_PARTS}: {x, y, w, h}. */
    private static final int[] PARTS_HEAD = {2, 1, 24, 24};
    private static final int[] PARTS_POLE = {28, 5, 4, 19};
    private static final int[] PARTS_FLAG = {53, 1, 20, 18};
    /** The reward money bag, same art the award page uses. */
    private static final Identifier REWARD_BAG = Identifier.withDefaultNamespace("textures/gui/award/money_bag");
    /** Sun bank geometry, shared by the HUD, the card layout and the fly-to-bank target. */
    private static final int BANK_WIDTH = 78;
    private static final int BANK_HEIGHT = 87;
    private static final int BANK_MARGIN = 8;
    private static final int BANK_GAP = 6;
    /**
     * Bottom-left coin bank: the art's own 128x31, so the frame is not stretched.
     *
     * <p>It used to be drawn at 140x34 - nine percent wider than the art - which read as a
     * flattened plaque next to the round money bag it frames.
     */
    private static final int COIN_BANK_WIDTH = 128;
    private static final int COIN_BANK_HEIGHT = 31;
    /**
     * How long the coin bank stays on screen after the last coin.
     *
     * <p>It is a receipt, not a fixture: the original only shows the coin counter while
     * money is moving, and the corner is otherwise part of the lawn. The fade at the
     * end is {@link #COIN_BANK_FADE_NANOS}.
     */
    private static final long COIN_BANK_SHOW_NANOS = 2_600_000_000L;
    private static final long COIN_BANK_FADE_NANOS = 600_000_000L;
    /** Small visual grass ring beyond the playable board, in world cells. */
    private static final float GRASS_VISUAL_MARGIN_CELLS = 0.12F;
    /** How long the "Ready... Set... Plant!" banner stays up. */
    private static final long ENTRY_BANNER_NANOS = 2_200_000_000L;
    /** The reward packet falls from above the lawn onto this many cells. */
    private static final float REWARD_DROP_CELLS = 2.6F;
    private static final long REWARD_DROP_NANOS = 900_000_000L;
    /** Click to centre: slow on purpose, it is the victory lap. */
    private static final long REWARD_RISE_NANOS = 1_900_000_000L;
    /**
     * Then it grows a little, in place, before the award page takes over.
     *
     * <p>Arriving and being presented are two different beats; without the pause the
     * packet simply vanished into the page.
     */
    private static final long REWARD_GROW_NANOS = 600_000_000L;
    private static final long REWARD_HANDOFF_NANOS = 400_000_000L;
    /** How much bigger it gets while it grows. */
    private static final float REWARD_GROW_SCALE = 0.35F;
    /**
     * How long an unclaimed reward waits before claiming itself.
     *
     * <p>The coins are already banked either way, so this is purely a way out: a player
     * who wanders off must not come back to a frozen board whose only exit is a sprite
     * they no longer know to click.
     */
    private static final long REWARD_AUTO_CLAIM_NANOS = 20_000_000_000L;
    private static final float REWARD_WIDTH_CELLS = 0.72F;
    /**
     * How long a wave announcement stays "already played" in this level instance.
     *
     * <p>Longer than any gap between two genuine waves of one level (the shortest built-in
     * gap is half a minute), so it only ever swallows a duplicate of the same announcement,
     * never the next wave's own call.
     */
    private static final long ANNOUNCEMENT_ONCE_NANOS = 90_000_000_000L;

    private int selectedCard = -1;
    private Button pauseButton;
    private Button speedButton;
    private PauseDialog pauseDialog;
    private long lastParticleNanos = System.nanoTime();
    /**
     * When each one-shot announcement last played, by sound id.
     *
     * <p>The screen is rebuilt for each level, so the map is per level instance. See
     * {@link #playEffectSound}.
     */
    private final java.util.Map<String, Long> announcementsPlayed = new java.util.HashMap<>();
    /** When this level screen appeared; drives the entry banner's fade. */
    private final long entryNanos = System.nanoTime();
    /** Last coin count the HUD noticed, and when it last changed. */
    private int seenCoins = -1;
    private long coinBankNanos;
    /**
     * The end-of-level reward, once the server has paid it out.
     *
     * <p>Held here rather than opened immediately: the reward lands on the lawn as a
     * seed packet or a money bag, and only the player's click turns it into the award
     * page. A level with no reward packet (a defeat, or a modded server that does not
     * send one) never sets this and keeps the plain end overlay.
     */
    private LevelRewardS2C reward;
    private long rewardDropNanos;
    private long rewardRiseNanos;
    private boolean rewardHandedOff;
    private float rewardDropX;
    private float rewardDropY;
    private boolean paused;

    private int cardScrollOffset;
    private int cardMaxScroll;
    private int cardViewportX;
    private int cardViewportY;
    private int cardViewportWidth;
    private int cardViewportHeight;
    private int cardWidth = 44;
    private int cardHeight = 62;
    private int cardGap = 4;

    public InGameScreen(PvzceClient client) {
        super(client);
    }

    @Override
    protected void init() {
        int width = client.guiWidth();
        int height = client.guiHeight();
        int pauseWidth = Math.min(154, width / 3);
        int pauseHeight = Math.min(56, height / 5);
        int speedWidth = Math.min(72, Math.max(52, width / 8));
        int speedHeight = Math.min(36, Math.max(24, height / 12));
        int speedX = width - speedWidth - 12;
        speedButton = new Button(speedX, height - pauseHeight - 12 + (pauseHeight - speedHeight) / 2,
                speedWidth, speedHeight, speedLabel(), this::cycleSpeed);
        int pauseX = Math.max(12, speedX - pauseWidth - 8);
        pauseButton = new Button(pauseX, height - pauseHeight - 12,
                pauseWidth, pauseHeight, "暂停", this::openPause);
        addWidget(speedButton);
        addWidget(pauseButton);
        pauseDialog = PauseDialog.create(client);
        pauseDialog.onClose(this::handlePauseDialogClosed);
        pauseDialog.setVisible(paused);
        if (paused) {
            client.connection().send(new PauseGameC2S(true));
        }
        addWidget(pauseDialog);
    }

    /**
     * Hands the finished level's payout to this screen: the reward falls onto the lawn.
     *
     * <p>Called from {@code PvzceClient.onLevelReward}, which arrives one packet after
     * the game state, so the board is already frozen. A reward with nothing to unlock
     * still lands - it is the money bag.
     */
    public void showReward(LevelRewardS2C reward) {
        this.reward = reward;
        this.rewardDropNanos = System.nanoTime();
        this.rewardRiseNanos = 0L;
        this.rewardHandedOff = false;
        // Where the last zombie died, so the reward appears where the fight ended. A
        // level with no zombies has no such spot, and falls back to the middle.
        boolean hasSpot = Float.isFinite(reward.dropX()) && Float.isFinite(reward.dropY());
        this.rewardDropX = hasSpot
                ? Math.max(0.35F, Math.min(client.level().width() - 0.35F, reward.dropX()))
                : Math.max(0.35F, client.level().width() / 2F - 0.1F);
        this.rewardDropY = hasSpot
                ? Math.max(0F, Math.min(client.level().height() - 1F, reward.dropY()))
                : Math.max(0, (client.level().height() - 1) / 2F);
    }

    /** True while a claimed-but-not-yet-handed-off reward is on screen. */
    private boolean hasRewardDrop() {
        return reward != null && !rewardHandedOff;
    }

    /**
     * Drives the payout: it falls, it is claimed, it becomes the award page.
     *
     * <p>The stinger is deliberately here rather than in {@code onGameState}: a plant
     * win no longer plays the victory music the instant the level ends, because the
     * player has not claimed anything yet. It starts on the click, together with the
     * rise, so the music and the reward moving to the middle are one gesture.
     */
    private void tickReward() {
        if (!hasRewardDrop()) {
            return;
        }
        long now = System.nanoTime();
        if (rewardRiseNanos == 0L) {
            if (now - rewardDropNanos >= REWARD_AUTO_CLAIM_NANOS) {
                claimReward();
            }
            return;
        }
        if (now - rewardRiseNanos
                >= REWARD_RISE_NANOS + REWARD_GROW_NANOS + REWARD_HANDOFF_NANOS) {
            rewardHandedOff = true;
            client.openAwardScreen(reward);
        }
    }

    /** Claims the reward: the victory music and the rise to the middle, at once. */
    private void claimReward() {
        if (rewardRiseNanos != 0L) {
            return;
        }
        rewardRiseNanos = System.nanoTime();
        if (client.music() != null) {
            client.music().playWinLose(true);
        }
    }

    /**
     * Where the reward is drawn, in GUI pixels, and how far the claim has gone.
     *
     * <p>Both phases land in GUI pixels here rather than in world cells: the claim ends
     * at the middle of the window, which is not a place on the board, so the fall is
     * projected through the camera once and everything downstream is one space.
     *
     * @param riseProgress 0 while it lies on the lawn, 1 once it has reached the middle
     * @param growProgress 0 while travelling, 1 once it has finished growing in place
     */
    private record RewardPlacement(float centerX, float bottomY, float riseProgress,
                                   float growProgress) {
    }

    private RewardPlacement rewardPlacement() {
        PvzceCamera camera = client.camera();
        float guiScale = Math.max(1, client.guiScale());
        float unit = Math.max(18F, camera.unitY() / guiScale);
        float fromX = camera.screenX(rewardDropX) / guiScale;
        float fromY = camera.screenY(rewardDropY) / guiScale;
        long elapsed = System.nanoTime() - rewardRiseNanos;
        if (rewardRiseNanos == 0L) {
            float fall = MathUtil.easeOutCubic(MathUtil.clamp01(
                    (System.nanoTime() - rewardDropNanos) / (float) REWARD_DROP_NANOS));
            // Falls in from above, measured in cells so it matches the board's scale.
            return new RewardPlacement(fromX, fromY + (1F - fall) * REWARD_DROP_CELLS * unit, 0F, 0F);
        }
        float progress = MathUtil.easeInOut(MathUtil.clamp01(elapsed / (float) REWARD_RISE_NANOS));
        float grow = MathUtil.easeOutCubic(
                MathUtil.clamp01((elapsed - REWARD_RISE_NANOS) / (float) REWARD_GROW_NANOS));
        return new RewardPlacement(MathUtil.lerp(fromX, client.guiWidth() / 2F, progress),
                MathUtil.lerp(fromY, client.guiHeight() / 2F, progress), progress, grow);
    }

    /**
     * The reward's rectangle in GUI pixels: the click target and the drawn sprite.
     *
     * <p>One implementation for both, so a click can never land where the sprite is not.
     */
    private float[] rewardRect() {
        RewardPlacement placement = rewardPlacement();
        float unit = Math.max(18F, client.camera().unitY() / Math.max(1, client.guiScale()));
        // Grows as it travels, so arriving in the middle reads as being presented.
        float width = unit * REWARD_WIDTH_CELLS
                * (1F + REWARD_GROW_SCALE * (0.45F * placement.riseProgress() + placement.growProgress()));
        float height = width * 140F / 100F;
        return new float[]{placement.centerX() - width / 2F,
                placement.bottomY() - height * 0.25F, width, height};
    }

    /** Draws the unclaimed reward: a seed packet, or the money bag. */
    private void renderRewardDrop() {
        if (!hasRewardDrop()) {
            return;
        }
        float[] rect = rewardRect();
        if (reward().hasUnlock()) {
            SlotResolver.ResolvedCard card = SlotResolver
                    .resolve(Identifier.tryParse(reward().unlockedCard())).orElse(null);
            SeedCardRenderer.draw(client, SeedCardRenderer.CardModel.of(
                            card == null ? null : card.icon().orElse(null),
                            SeedCardRenderer.CardKind.PLANT,
                            card == null ? 0 : card.costSun()),
                    rect[0], rect[1], rect[2], rect[3]);
        } else {
            client.drawTexture(REWARD_BAG, rect[0], rect[1], rect[2], rect[3], 0.55F, 1F, 1F, 1F, 1F);
        }
        if (rewardRiseNanos == 0L) {
            String hint = "点击领取奖励";
            float scale = 1.2F;
            client.font().draw(hint, rect[0] + rect[2] / 2F - client.font().width(hint, scale) / 2F,
                    rect[1] + rect[3] + 8F, scale, 1F, 0.95F, 0.6F, 1F);
        }
    }

    private LevelRewardS2C reward() {
        return reward;
    }

    /**
     * Plays one server-sent effect sound, refusing to repeat a wave announcement.
     *
     * <p>A wave enters the board exactly once per run, and its siren or huge-wave call is
     * six seconds of the loudest sound in the level. The client is a mirror, so when the
     * server said it twice the lawn said it twice: resuming a save that was taken after the
     * last wave began replayed both announcements, because a restored level knew which wave
     * index it was on but not which ones it had already announced. That is fixed on the
     * server ({@code LevelServer.restore} seeds its announced set); this is the client
     * holding up its end, so that "the sound of the last wave" cannot be heard twice in one
     * level instance whatever the server sends.
     *
     * <p>Only announcements are deduplicated. A zombie groan is supposed to repeat, and
     * throttling it here would fight the sound engine, which already rate-limits the same
     * event.
     */
    private void playEffectSound(EffectEventS2C effect) {
        String sound = effect.sound();
        if (isWaveAnnouncement(sound)) {
            long now = System.nanoTime();
            Long last = announcementsPlayed.get(sound);
            if (last != null && now - last < ANNOUNCEMENT_ONCE_NANOS) {
                return;
            }
            announcementsPlayed.put(sound, now);
        }
        client.sound().play(sound, effect.volume(), effect.pitch());
    }

    /**
     * True for the two sounds a wave uses to announce itself.
     *
     * <p>The ids are the wave data's own ({@code LevelServer.triggerWave}), not a second
     * list of names: a level that announces itself with something else keeps the old
     * fire-and-forget behaviour rather than being silently swallowed.
     */
    private static boolean isWaveAnnouncement(String sound) {
        return com.pvzce.common.PvzceSounds.AMBIENT_HUGE_WAVE.toString().equals(sound)
                || com.pvzce.common.PvzceSounds.EFFECT_AWOOGA.toString().equals(sound);
    }

    /** True once the reward is on its way to the middle and no longer clickable. */
    private boolean isRising() {
        return rewardRiseNanos != 0L;
    }

    /** Hit test against a GUI rectangle; the same shape the drop is drawn with. */
    private static boolean inside(double guiX, double guiY, float[] rect) {
        return guiX >= rect[0] && guiX <= rect[0] + rect[2]
                && guiY >= rect[1] && guiY <= rect[1] + rect[3];
    }

    @Override
    public void tick() {
        if (!client.level().gameState().equals("running")) {
            closePause();
        }
        tickReward();
        if (speedButton != null) {
            speedButton.setLabel(speedLabel());
        }
        EffectEventS2C effect;
        while ((effect = client.level().effects().poll()) != null) {
            if (!effect.particle().isEmpty()) {
                client.particles().spawn(effect.particle(), effect.x(), effect.y());
            }
            if (!effect.sound().isEmpty() && client.sound() != null) {
                playEffectSound(effect);
            }
            if (effect.hasRipple()
                    && com.pvzce.client.renderer.liquid.LiquidTextures
                            .liquidFor(effect.ripple()).isPresent()) {
                // The server names the LIQUID, not the renderer, so a modded liquid
                // gets ripples exactly like water does. An id this client cannot
                // resolve is dropped rather than treated as water: the surface it
                // meant is not on screen anyway, and drawing one would put a ring in
                // the middle of a lawn.
                client.liquidRipples().add(effect.x(), effect.y(), effect.rippleStrength());
            }
        }
        // Wall-clock delta: particle motion and lifetime must not scale with the
        // frame rate (the default cap is 120 FPS, not 60).
        float dt = Math.min(0.1F, (System.nanoTime() - lastParticleNanos) / 1_000_000_000F);
        lastParticleNanos = System.nanoTime();
        client.particles().tick(dt);
        // Ripples age on the same wall-clock step as the particles and for the same
        // reason: a ripple is presentation only, so it must not freeze when the
        // server is paused or stepped.
        client.liquidRipples().tick(dt);
        client.level().pruneCollectAnimations(System.nanoTime());
        int coins = inLevelCoins();
        if (seenCoins < 0) {
            // First look: an empty new level must not flash a receipt for coins that
            // were never collected. A resumed save that already holds coins does.
            seenCoins = coins;
            if (coins > 0) {
                coinBankNanos = System.nanoTime();
            }
        } else if (coins != seenCoins) {
            seenCoins = coins;
            coinBankNanos = System.nanoTime();
        }
    }

    @Override
    public void render() {
        renderWorld();
        client.beginGuiView();
        renderHud();
        renderEntryBanner();
        renderSlots();
        renderCollectAnimations();
        renderEndOverlay();
        renderRewardDrop();
        if (client.level().gameState().equals("running")) {
            for (var widget : widgets) {
                widget.render(client);
            }
        }
    }

    private void renderWorld() {
        PvzceCamera camera = client.beginWorldView();
        // The original PvZ backing image is the stage: house on the left, a
        // 9x5 bare-dirt lawn in the middle, road on the right. The board is
        // projected into the lawn region by PvzceCamera.
        client.drawTexture(LevelStage.BACKGROUND_TEXTURE,
                camera.worldLeft(), camera.worldBottom(),
                camera.worldWidth(), camera.worldHeight(),
                -1F, 1F, 1F, 1F, 1F);
        // Grass texture cells are square; draw them at a uniform pixel scale
        // and scissor the scene pass to the actual board rectangle so the
        // wider grass quads cannot bleed into the house/road.
        float boardLeft = camera.screenX(0F);
        float boardBottom = camera.screenY(0F);
        float boardRight = camera.screenX(client.level().width());
        float boardTop = camera.screenY(client.level().height());
        float marginX = GRASS_VISUAL_MARGIN_CELLS * camera.unitX();
        float marginY = GRASS_VISUAL_MARGIN_CELLS * camera.unitY();
        // World-space pixels: pushed through the same stack so a nested clip and a
        // stray exception cannot leak GL_SCISSOR_TEST into the next frame.
        client.clipping().pushPixels(
                (int) Math.floor(Math.min(boardLeft, boardRight) - marginX),
                (int) Math.floor(Math.min(boardBottom, boardTop) - marginY),
                Math.max(1, (int) Math.ceil(Math.abs(boardRight - boardLeft) + marginX * 2F)),
                Math.max(1, (int) Math.ceil(Math.abs(boardTop - boardBottom) + marginY * 2F)));
        try {
            SceneTileRenderer.render(client, client.level().width(), client.level().height(),
                    (x, y) -> client.level().sceneAt(x, y),
                    camera.unitY() / Math.max(0.0001F, camera.unitX()),
                    GRASS_VISUAL_MARGIN_CELLS);
        } finally {
            client.clipping().pop();
        }

        if (selectedCard >= 0) {
            int hoverX = camera.cellX(client.window().cursorX(), client.window().cursorY());
            int hoverY = camera.cellY(client.window().cursorX(), client.window().cursorY());
            if (camera.inBoard(client.window().cursorX(), client.window().cursorY())
                    && hoverX >= 0 && hoverX < client.level().width()
                    && hoverY >= 0 && hoverY < client.level().height()) {
                client.drawSolid(hoverX, hoverY, 1F, 1F, 0.2F, 1F, 1F, 0.2F, 0.25F);
            }
        }

        // Stable back-to-front order. Plants are drawn before zombies so an
        // eating zombie covers the plant. A cell's plants share one render layer
        // and are ordered by spawn id, and the server places a plant above
        // whatever it rests on (PlacementDef.layer + the #c:carrier tags), so the
        // later-planted plant appears on top of its carrier without this loop
        // needing to know what a carrier is.
        List<ClientEntity> renderEntities = new ArrayList<>(client.level().entities().values());
        renderEntities.sort(Comparator.comparingInt(InGameScreen::renderOrder).thenComparingInt(ClientEntity::id));
        for (ClientEntity entity : renderEntities) {
            renderEntity(entity);
        }
        client.particles().render(client);
    }

    private static int renderOrder(ClientEntity entity) {
        return com.pvzce.client.renderer.EntityVisuals.sortBucket(entity.kind(), entity.layer());
    }

    /** Namespace-preserving sprite id; shared with the seed chooser and editor. */
    private static Identifier entityTexture(ClientEntity entity) {
        return com.pvzce.client.renderer.EntityTextures.forEntity(entity.defId());
    }

    private void renderEntity(ClientEntity entity) {
        Identifier texture = entityTexture(entity);

        // State-change driven local playback: the call is idempotent, so this
        // can safely run every frame without restarting the current clip.
        entity.playAnimation(entity.animation());
        drawShadow(client, entity, texture);

        boolean underground = entity.layer() == com.pvzce.api.entity.EntityLayers.UNDERGROUND;
        if (!underground && client.animations() != null && client.animations().render(entity)) {
            return;
        }

        // No animation resource: show the first frame. The old _2.png toggle
        // is intentionally gone; content opts in through animation JSON.
        // Same two factors the animation path uses (see AnimationManager#xScaleFor): the
        // board's aspect correction for everything but a drop, and the definition's own
        // render_scale for everything, applied to both axes so it never changes the shape.
        boolean drop = entity.kind().equals(com.pvzce.api.entity.EntityKind.RESOURCE);
        float renderScale = com.pvzce.common.core.EntityArt.renderScale(entity.defId());
        float xScale = (drop ? 1F : client.spriteXScale()) * renderScale;
        float yScale = renderScale;
        EntityVisuals.Visuals visuals = EntityVisuals.of(entity.kind());
        if (entity.layer() == com.pvzce.api.entity.EntityLayers.UNDERGROUND) {
            // Burrowing zombies are shown as a mound instead of a sprite.
            client.drawSolid(entity.cellX() - 0.3F, entity.cellY() - 0.2F, 0.6F, 0.4F, 0.05F,
                    0.4F, 0.28F, 0.16F, 0.9F);
            return;
        }
        client.drawTexture(texture, entity.cellX() - visuals.spriteOffsetX() * xScale,
                entity.cellY() - visuals.spriteOffsetY() * yScale + entity.height(),
                visuals.spriteWidth() * xScale, visuals.spriteHeight() * yScale, visuals.baseZ(),
                1, 1, 1, 1);
    }

    /**
     * Projected entity shadow rendered through the world shader. Direction,
     * length and tint follow the interpolated sun/moon position.
     */
    private void drawShadow(PvzceClient client, ClientEntity entity, Identifier texture) {
        float[] visual = client.animations() == null ? null : client.animations().visualSize(entity);
        float spriteXScale = client.spriteXScale();
        // A definition can ask to be drawn bigger or smaller than its art; the shadow has
        // to follow it, or a scaled entity slides around on a shadow that belongs to the
        // size it no longer is.
        float renderScale = com.pvzce.common.core.EntityArt.renderScale(entity.defId());
        if (entity.kind().equals("plant")) {
            float width = (visual == null ? 0.68F : Math.max(0.20F, visual[0] * 0.85F)) * spriteXScale;
            float height = visual == null ? 0.76F : Math.max(0.20F, visual[1]);
            client.drawEntityShadow(texture, entity.cellX(), entity.cellY() - 0.46F,
                    width * renderScale, height * renderScale, 0.34F);
        } else if (entity.kind().equals("zombie") && entity.layer() != -1) {
            float lift = Math.max(0F, entity.height());
            float alpha = Math.max(0.14F, 0.34F - lift * 0.14F);
            float width = (visual == null
                    ? Math.max(0.46F, 0.62F - lift * 0.06F)
                    : Math.max(0.20F, visual[0] * 0.85F)) * spriteXScale;
            float height = visual == null ? 0.95F : Math.max(0.20F, visual[1]);
            client.drawEntityShadow(texture, entity.cellX(), entity.cellY() - 0.46F,
                    width * renderScale, height * renderScale, alpha);
        }
    }

    private void renderHud() {
        int height = client.guiHeight();
        // The original PvZ-style sun bank appears when the player picked the SunBank card
        // in the seed chooser: collecting sun is what that card buys.
        if (hasSunBank()) {
            int bankY = height - BANK_HEIGHT - BANK_MARGIN;
            client.drawTexture(SUN_BANK, BANK_MARGIN, bankY, BANK_WIDTH, BANK_HEIGHT, 0.1F, 1F, 1F, 1F, 1F);
            String sunText = String.valueOf(client.level().sun());
            client.font().draw(sunText, BANK_MARGIN + (BANK_WIDTH - client.font().width(sunText, 1F)) / 2F,
                    bankY + BANK_HEIGHT * 0.08F, 1F, 0.12F, 0.07F, 0.03F, 1F);
        }
        renderCoinBank();

        String team = client.level().controlledTeamName().isEmpty()
                ? (client.level().controlledTeam().contains("zombie") ? "僵尸方" : "植物方")
                : client.level().controlledTeamName();
        client.font().draw("当前队伍：" + team, 16, 72, 0.8F, 0.85F, 0.85F, 0.9F, 1F);

        int y = 96;
        for (String message : client.level().messages()) {
            client.font().draw(message, 16, y, 0.9F, 0.1F, 0.1F, 0.1F, 1F);
            y += 22;
        }

        renderWaveBar();
    }

    /**
     * The original's "Ready... Set... Plant!" banner when a level begins.
     *
     * <p>The server already plays {@code pvzce:sfx/ambient/readysetplant} as part of
     * the level's full state, so this is the visual half of the same beat: without
     * it the sound had nothing on screen and the level simply started. It fades out
     * on the wall clock and never blocks input.
     */
    private void renderEntryBanner() {
        long elapsed = System.nanoTime() - entryNanos;
        if (elapsed >= ENTRY_BANNER_NANOS || !client.level().gameState().equals("running")) {
            return;
        }
        float progress = elapsed / (float) ENTRY_BANNER_NANOS;
        // Fade in over the first 15%, hold, fade out over the last 30%.
        float alpha = progress < 0.15F
                ? progress / 0.15F
                : (progress > 0.7F ? Math.max(0F, (1F - progress) / 0.3F) : 1F);
        String text = com.pvzce.client.gui.GuiLang.raw("pvzce.readysetplant", "准备… 安放… 种植！");
        float scale = Math.max(1.6F, Math.min(3.4F, client.guiHeight() / 80F));
        float width = client.font().width(text, scale);
        float x = (client.guiWidth() - width) / 2F;
        float y = client.guiHeight() * 0.62F;
        client.drawSolid(x - 24F, y - 14F, width + 48F, client.font().lineHeight(scale) + 28F,
                0.4F, 0.12F, 0.07F, 0.03F, 0.72F * alpha);
        client.font().draw(text, x, y, scale, 1F, 0.94F, 0.42F, alpha);
    }

    private boolean hasSunBank() {
        return hasSunBank(client.level().slots());
    }

    /** True when the selected card bar contains the SunBank slot. */
    static boolean hasSunBank(List<SlotInfo> slots) {
        for (SlotInfo slot : slots) {
            if (SUN_CARD_ID.equals(slot.defId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The run's coin count, immediately right of the sun bank.
     *
     * <p>Coins are not spendable inside a level, so this is a tally rather than a
     * resource: it counts what this run has picked up and what will be banked when
     * the level ends, win or lose. It sits beside the sun bank because that is
     * where the player already looks for "what have I collected", and it is drawn
     * whenever the sun bank is, so the fly-to-bank animation has a target before the
     * first coin exists.
     */
    private void renderCoinBank() {
        float alpha = coinBankAlpha();
        if (alpha <= 0F) {
            return;
        }
        // Bottom-left corner: the sun bank owns the top-left, and the wave meter owns
        // the bottom-right, so this is the one corner that is always lawn.
        int bankX = BANK_MARGIN;
        int bankY = BANK_MARGIN;
        if (client.hasTexture(COIN_BANK)) {
            client.drawTexture(COIN_BANK, bankX, bankY, COIN_BANK_WIDTH, COIN_BANK_HEIGHT,
                    0.1F, 1F, 1F, 1F, alpha);
        } else {
            client.drawSolid(bankX, bankY, COIN_BANK_WIDTH, COIN_BANK_HEIGHT,
                    0.1F, 0.42F, 0.34F, 0.12F, 0.9F * alpha);
        }
        // The art puts the money bag on the left, so the number goes to its right.
        String coins = String.valueOf(inLevelCoins());
        float textScale = COIN_BANK_HEIGHT / 40F;
        client.font().draw(coins, bankX + COIN_BANK_WIDTH * 0.32F,
                bankY + (COIN_BANK_HEIGHT - client.font().lineHeight(textScale)) / 2F,
                textScale, 1F, 0.96F, 0.6F, alpha);
    }

    /**
     * 1 while the bank is showing, fading to 0 in its last moments.
     *
     * <p>Zero before the first coin, which is why the bank is not drawn at all on a
     * fresh level: an empty counter in the corner is noise.
     */
    private float coinBankAlpha() {
        if (coinBankNanos == 0L) {
            return 0F;
        }
        long elapsed = System.nanoTime() - coinBankNanos;
        if (elapsed >= COIN_BANK_SHOW_NANOS) {
            return 0F;
        }
        long remaining = COIN_BANK_SHOW_NANOS - elapsed;
        if (remaining >= COIN_BANK_FADE_NANOS) {
            return 1F;
        }
        return remaining / (float) COIN_BANK_FADE_NANOS;
    }

    /**
     * Coins this run has collected, summed over the four denominations.
     *
     * <p>Each denomination's amount is already its worth (10 / 50 / 1000 / 250), so this
     * is a sum rather than a conversion: the ladder lives in the resource definitions.
     */
    private int inLevelCoins() {
        Identifier teamId = Identifier.tryParse(client.level().controlledTeam());
        if (teamId == null) {
            return 0;
        }
        int total = 0;
        for (Identifier denomination : com.pvzce.common.PvzceIds.COIN_DENOMINATIONS) {
            total += client.level().resource(teamId, denomination);
        }
        return total;
    }

    /**
     * Centre of the bank a collected resource flies into.
     *
     * <p>Sun and coins have their own banks and everything else shares the sun's,
     * matching where {@link #renderCoinBank} draws the tally. Computed from the same
     * constants the HUD lays the banks out with, so a coin cannot fly to a bank
     * that is not where the number appears.
     */
    private float[] bankTarget(String resourceId) {
        if (com.pvzce.common.PvzceIds.isCoin(resourceId)) {
            // The coin bank's bag, which is the left third of the art.
            return new float[]{BANK_MARGIN + COIN_BANK_WIDTH * 0.15F, BANK_MARGIN + COIN_BANK_HEIGHT / 2F};
        }
        return new float[]{BANK_MARGIN + BANK_WIDTH / 2F, client.guiHeight() - BANK_HEIGHT / 2F - BANK_MARGIN};
    }

    /**
     * The SunBank is a HUD element, not a card. Keeping it out of the visible
     * card list prevents a second bank from being drawn in the top bar while
     * the server still uses the slot for resource-card validation.
     */
    private List<SlotInfo> visibleCardSlots() {
        return orderCardSlots(client.level().slots());
    }

    /**
     * Cards are grouped by kind in the same order the seed chooser uses —
     * resources leftmost, then plants, then shovels/hammers on the far right —
     * so the two views never disagree about where a card lives. The SunBank is
     * skipped because it is HUD, not a card.
     */
    static List<SlotInfo> orderCardSlots(List<SlotInfo> slots) {
        List<SlotInfo> resources = new ArrayList<>();
        List<SlotInfo> plants = new ArrayList<>();
        List<SlotInfo> tools = new ArrayList<>();
        for (SlotInfo slot : slots) {
            if (SUN_CARD_ID.equals(slot.defId())) {
                continue;
            }
            if ("tool".equals(slot.kind())) {
                tools.add(slot);
            } else if ("resource".equals(slot.kind())) {
                resources.add(slot);
            } else {
                plants.add(slot);
            }
        }
        resources.addAll(plants);
        resources.addAll(tools);
        return resources;
    }

    /**
     * The original's flag meter, bottom-right.
     *
     * <p>Layout, and every piece of it means something:
     *
     * <ul>
     *   <li>the {@code LEVEL PROGRESS} plate is centred under the bar and touches it;
     *   <li>the level's name sits to the left of the bar, so the row reads
     *       {@code [name] [====================]};
     *   <li>the green fill is how far the level has come, and <b>the zombie head rides its
     *       leading edge</b> - the head is the progress marker, not a fixed ornament at one
     *       end of the bar;
     *   <li>a flag is planted for {@code huge}/{@code final} waves only. The meter used to
     *       flag every wave, which said the opposite of what a flag means: in the original
     *       a flag is the announcement that a big wave is coming, so a flag per small wave
     *       makes the meter useless for the one thing it is for.
     * </ul>
     *
     * <p>The art is the original's three pieces: {@code FlagMeter} holds the empty track in
     * its top half and the green fill in its bottom half, {@code FlagMeterParts} holds the
     * zombie head, the pole and the flag, and {@code FlagMeterLevelProgress} is the plate.
     */
    private void renderWaveBar() {
        int total = client.level().totalWaves();
        if (total <= 0) {
            return;
        }
        int current = Math.max(0, Math.min(total, client.level().currentWave()));
        // The *level's* progress, not the current wave's: the fill has to reach a flag as
        // that wave arrives, which is the whole point of putting them on one axis.
        float progress = Math.max(0F, Math.min(1F,
                (current + Math.max(0F, Math.min(1F, client.level().waveProgress()))) / total));
        if (current >= total) {
            progress = 1F;
        }

        int guiW = client.guiWidth();
        float scale = METER_SCALE;
        int meterHeight = Math.round(METER_NATIVE_HEIGHT * scale);
        int meterWidth = Math.round(METER_NATIVE_WIDTH * scale);
        int headWidth = Math.round(PARTS_HEAD[2] * scale);
        int plateHeight = Math.round(11F * scale);
        int plateY = 6;
        // Butted: the plate's top row is the bar's bottom row. The two are one gauge, and a
        // gap between them read as two separate HUD pieces.
        int meterY = plateY + plateHeight;
        int meterRight = guiW - 14;
        // The head travels from end to end, so the bar is inset by half a head on each
        // side: that keeps the head inside the bar at 0% and at 100%, and exactly on the
        // fill's leading edge everywhere between.
        int trackX = meterRight - meterWidth + headWidth / 2;
        int trackWidth = Math.max(40, meterWidth - headWidth);

        drawMeterBar(trackX, meterY, trackWidth, meterHeight, progress);
        for (int i = 0; i < total; i++) {
            String type = i < client.level().waveTypes().size() ? client.level().waveTypes().get(i) : "small";
            if (!"huge".equals(type) && !"final".equals(type)) {
                continue;
            }
            // The meter runs right to left, so the first wave's flag is the rightmost one.
            drawWaveFlag(trackX + trackWidth * (1F - (i + 0.5F) / total), meterY + meterHeight - 2,
                    scale * ("final".equals(type) ? 1.15F : 1F), i < current ? 1F : 0F);
        }
        // The head last, so it reads as standing in front of the flags it has reached.
        drawMeterHead(trackX + trackWidth * (1F - progress), meterY, meterHeight, scale);
        drawMeterLabel(trackX, trackWidth, meterY, meterHeight, plateY, plateHeight);
        renderWaveWarning(trackX + trackWidth / 2F, meterY + meterHeight + 4F);
    }

    /** The head, riding the leading edge of the fill: it is the progress marker. */
    private void drawMeterHead(float x, float meterY, float meterHeight, float scale) {
        float headSize = PARTS_HEAD[2] * scale;
        float headY = meterY + (meterHeight - PARTS_HEAD[3] * scale) / 2F;
        drawMeterPart(PARTS_HEAD, x - headSize / 2F, headY, headSize, PARTS_HEAD[3] * scale);
    }

    /**
     * One meter bar: the empty track, then the green fill over as much of it as the
     * level is done. Both live in the same texture, stacked.
     */
    private void drawMeterBar(float x, float y, float width, float height, float progress) {
        if (client.hasTexture(FLAG_METER)) {
            float[] track = vRangeForRows(METER_TRACK_ROWS, FLAG_METER_NATIVE_HEIGHT);
            client.drawTextureRegion(FLAG_METER, 0F, track[0], 1F, track[1],
                    x, y, width, height, 0.15F, 1F, 1F, 1F, 1F);
            if (progress > 0F) {
                // Right to left, like the original: the fill is anchored at the right end
                // and its leading edge sweeps left as the level goes on. Trimming it by UV
                // rather than stretching keeps the art's caps the size they were drawn.
                float[] fill = vRangeForRows(METER_FILL_ROWS, FLAG_METER_NATIVE_HEIGHT);
                client.drawTextureRegion(FLAG_METER, 1F - progress, fill[0], 1F, fill[1],
                        x + width * (1F - progress), y, width * progress, height,
                        0.2F, 1F, 1F, 1F, 1F);
            }
            return;
        }
        client.drawSolid(x, y, width, height, 0.15F, 0.18F, 0.19F, 0.3F, 0.9F);
        client.drawSolid(x, y, width * progress, height, 0.2F, 0.56F, 0.78F, 0.21F, 0.95F);
    }

    /**
     * The plate, centred under the bar and touching it, plus the level's name to the left.
     *
     * <p>The plate is the meter's caption, so it belongs to the meter rather than to one
     * end of it; the name is what the meter is about, and sits where the eye starts.
     */
    private void drawMeterLabel(int trackX, int trackWidth, int meterY, int meterHeight,
                                int plateY, float plateHeight) {
        float plateWidth = Math.round(86F * METER_SCALE);
        float plateX = Math.round(trackX + (trackWidth - plateWidth) / 2F);
        if (client.hasTexture(FLAG_METER_LABEL)) {
            client.drawTexture(FLAG_METER_LABEL, plateX, plateY, plateWidth, plateHeight,
                    0.2F, 1F, 1F, 1F, 1F);
        } else {
            client.drawSolid(plateX, plateY, plateWidth, plateHeight, 0.2F, 0.2F, 0.21F, 0.32F, 0.9F);
        }

        String name = client.currentLevelName();
        if (name.isEmpty()) {
            return;
        }
        // Capped so the line box stays inside the meter's band; the meter sits close to the
        // bottom edge, and a taller line would be clipped off-screen.
        float nameScale = Math.max(0.75F, Math.min(0.95F, 1.2F * (meterHeight / METER_NATIVE_HEIGHT)));
        float nameWidth = client.font().width(name, nameScale);
        float nameX = trackX - nameWidth - 8F;
        if (nameX < 4F) {
            return;
        }
        float nameY = meterY + (meterHeight - client.font().lineHeight(nameScale)) / 2F + 4F;
        // Drawn twice: the meter sits on whatever the level's background art happens to be
        // there, and a drop shadow is what keeps a level name readable on a pale sidewalk.
        client.font().draw(name, nameX + 1F, nameY - 1F, nameScale, 0.05F, 0.05F, 0.05F, 0.8F);
        client.font().draw(name, nameX, nameY, nameScale, 1F, 0.96F, 0.72F, 1F);
    }

    /** A pole with a flag on it, standing on the track at one big wave's position. */
    private void drawWaveFlag(float x, float baseY, float scale, float finished) {
        float poleWidth = 4F * scale;
        float poleHeight = 19F * scale;
        float flagWidth = 20F * scale;
        float flagHeight = 18F * scale;
        float alpha = finished > 0F ? 0.55F : 1F;
        if (client.hasTexture(FLAG_METER_PARTS)) {
            drawMeterPart(PARTS_POLE, x - poleWidth / 2F, baseY, poleWidth, poleHeight, alpha);
            drawMeterPart(PARTS_FLAG, x - poleWidth / 2F, baseY + poleHeight - flagHeight,
                    flagWidth, flagHeight, alpha);
            return;
        }
        client.drawSolid(x - 1F, baseY, 2F, poleHeight, 0.28F, 0.9F, 0.25F, 0.2F, alpha);
        client.drawSolid(x + 1F, baseY + poleHeight - flagHeight, flagWidth, flagHeight,
                0.28F, 0.85F, 0.15F, 0.12F, alpha);
    }

    /** The blinking "a huge wave is coming" text above the meter. */
    private void renderWaveWarning(float centerX, float y) {
        // 3 Hz blink driven by the world clock so it does not speed up with FPS.
        long blinkPhase = (long) (client.level().smoothGameTicks() / 20D);
        if (!client.level().waveWarningActive() || blinkPhase % 2 != 0) {
            return;
        }
        String warning = client.level().waveWarningFinal()
                ? "最终波：一大波僵尸正在接近！"
                : "一大波僵尸正在接近！";
        float scale = 1.4F;
        client.font().draw(warning, centerX - client.font().width(warning, scale) / 2F, y,
                scale, 1F, 0.25F, 0.2F, 1F);
    }

    /** One piece of the parts sheet, by its pixel rectangle in the original art. */
    private void drawMeterPart(int[] part, float x, float y, float width, float height) {
        drawMeterPart(part, x, y, width, height, 1F);
    }

    private void drawMeterPart(int[] part, float x, float y, float width, float height, float alpha) {
        if (!client.hasTexture(FLAG_METER_PARTS)) {
            return;
        }
        float[] v = vRangeForRows(new float[]{part[1], part[1] + part[3]}, METER_PARTS_HEIGHT);
        client.drawTextureRegion(FLAG_METER_PARTS,
                part[0] / METER_PARTS_WIDTH, v[0],
                (part[0] + part[2]) / METER_PARTS_WIDTH, v[1],
                x, y, width, height, 0.25F, 1F, 1F, 1F, alpha);
    }

    /**
     * The V of a row range given from the top of the image: {@code [0]} is the lower V of
     * the pair and {@code [1]} the upper one.
     *
     * <p>Textures are uploaded flipped ({@code TextureManager} sets
     * {@code stbi_set_flip_vertically_on_load}), so UV (0,0) is the bottom-left corner and
     * an author who reads pixel rows off the PNG has to flip them - and the pair comes out
     * reversed, because the image's *last* row is the region's low V.
     */
    private static float[] vRangeForRows(float[] rowsFromTop, float textureHeight) {
        return new float[]{(textureHeight - rowsFromTop[1]) / textureHeight,
                (textureHeight - rowsFromTop[0]) / textureHeight};
    }

    private void renderSlots() {
        List<SlotInfo> slots = visibleCardSlots();
        if (slots.isEmpty()) {
            return;
        }
        updateCardLayout(slots.size());

        client.clipping().push(cardViewportX, cardViewportY, cardViewportWidth, cardViewportHeight);
        try {
            int visibleIndex = 0;
            for (SlotInfo slot : slots) {
                float x = cardViewportX + visibleIndex * (cardWidth + cardGap) - cardScrollOffset;
                visibleIndex++;
                if (x + cardWidth < cardViewportX || x > cardViewportX + cardViewportWidth) {
                    continue;
                }
                drawCard(slot, x, cardViewportY, cardWidth, cardHeight);
            }
        } finally {
            client.clipping().pop();
        }

        if (cardMaxScroll > 0) {
            client.font().draw("<", cardViewportX + 2, cardViewportY + cardHeight / 2F - 8, 1F, 1F, 1F, 1F, 1F);
            client.font().draw(">", cardViewportX + cardViewportWidth - 12,
                    cardViewportY + cardHeight / 2F - 8, 1F, 1F, 1F, 1F, 1F);
        }
    }

    private void drawCard(SlotInfo slot, float x, float y, float width, float height) {
        boolean ready = slot.available() && slot.cooldownLeft() <= 0;
        float dark = ready ? 1F : 0.45F;
        Identifier background = "pvzce:shovel".equals(slot.defId()) ? SHOVEL_BANK : SEED_PACKET;
        client.drawTexture(background, x, y, width, height, 0.1F, dark, dark, dark, 1F);

        String path = slot.defId().contains(":")
                ? slot.defId().substring(slot.defId().indexOf(':') + 1) : slot.defId();
        Identifier icon = Identifier.withDefaultNamespace("textures/entities/" + path);
        Identifier slotId = Identifier.tryParse(slot.defId());
        if (slotId != null) {
            SlotDef slotDef = BuiltInRegistries.SLOT_TYPES.get(slotId);
            if (slotDef != null && slotDef.icon().isPresent()) {
                icon = slotDef.icon().get();
            }
        }
        float iconAreaBottom = y + height * 0.24F;
        float iconAreaHeight = height * 0.76F;
        float iconSize = Math.min(width * 0.80F, iconAreaHeight * 0.78F);
        client.drawTexture(icon,
                x + (width - iconSize) / 2F, iconAreaBottom + (iconAreaHeight - iconSize) / 2F,
                iconSize, iconSize, 0.2F, dark, dark, dark, 1F);

        SeedCardRenderer.draw(client, new SeedCardRenderer.CardModel(
                icon, SeedCardRenderer.CardKind.fromJson(slot.kind()), slot.costSun(),
                dark, 1F, ready, slot.cooldownLeft() / 300F, selectedCard == slot.index()),
                x, y, width, height);
    }

    private void updateCardLayout(int slotCount) {
        int guiW = client.guiWidth();
        int guiH = client.guiHeight();
        int left = BANK_MARGIN + (hasSunBank() ? BANK_WIDTH + BANK_GAP : 0);
        int right = (pauseButton != null ? pauseButton.x() : guiW - 12) - 8;
        cardViewportX = left;
        cardViewportWidth = Math.max(120, right - left);
        cardHeight = Math.max(56, Math.min(74, guiH / 6));
        cardWidth = Math.max(38, Math.round(cardHeight * 100F / 140F));
        cardGap = Math.max(2, cardWidth / 10);
        cardViewportY = guiH - cardHeight - 8;
        cardViewportHeight = cardHeight;

        int contentWidth = slotCount * (cardWidth + cardGap) - cardGap;
        cardMaxScroll = Math.max(0, contentWidth - cardViewportWidth);
        cardScrollOffset = Math.max(0, Math.min(cardScrollOffset, cardMaxScroll));
    }

    /**
     * Client-only collect animation: the server has already credited the
     * resource, so this is a pure cosmetic flight from the former drop
     * position to that resource's bank.
     *
     * <p>Each drop flies to its own bank - sun to the sun bank, coins to the coin
     * bank - because landing a coin on the sun counter would say the wrong thing
     * about where the number went. Anything else shares the sun bank, which is
     * still the only bank a level without the SunBank card has.
     */
    private void renderCollectAnimations() {
        long now = System.nanoTime();
        List<ResourceCollectAnimation> animations = client.level().collectAnimations();
        if (animations.isEmpty() || !hasSunBank()) {
            return;
        }
        PvzceCamera camera = client.camera();
        float guiScale = Math.max(1, client.guiScale());
        int guiH = client.guiHeight();

        for (ResourceCollectAnimation animation : animations) {
            float progress = animation.progress(now);
            if (progress >= 1F) {
                continue;
            }
            float[] target = bankTarget(animation.resourceId());
            float targetX = target[0];
            float targetY = target[1];
            float startX = camera.screenX(animation.worldX()) / guiScale;
            float startY = camera.screenY(animation.worldY() + Math.max(0F, animation.height()) - 0.22F) / guiScale;
            float eased = easeOutCubic(progress);
            float distance = Math.max(0F, (float) Math.hypot(targetX - startX, targetY - startY));
            float arc = Math.min(90F, 18F + distance * 0.16F) * (float) Math.sin(Math.PI * progress);
            float x = lerp(startX, targetX, eased);
            float y = lerp(startY, targetY, eased) + arc;

            float baseSize = Math.max(22F, Math.min(40F, guiH / 15F));
            float size = baseSize * (1F - 0.62F * progress);
            float alpha = progress < 0.86F ? 1F : Math.max(0F, (1F - progress) / 0.14F);
            client.drawTexture(animation.icon(), x - size / 2F, y - size / 2F,
                    size, size, 0.6F, 1F, 1F, 1F, alpha);
        }
    }

    private static float easeOutCubic(float t) {
        return com.pvzce.common.util.MathUtil.easeOutCubic(t);
    }

    private static float lerp(float from, float to, float delta) {
        return com.pvzce.common.util.MathUtil.lerp(from, to, delta);
    }

    private void renderEndOverlay() {
        if (client.level().gameState().equals("running")) {
            return;
        }
        int width = client.guiWidth();
        int height = client.guiHeight();
        // A claimed reward owns the screen: dimming it and shouting "胜利！" over the
        // drop would fight the thing the player is meant to click. It gets a light
        // wash and nothing else.
        if (hasRewardDrop() && rewardRiseNanos != 0L) {
            client.drawSolid(0, 0, width, height, 0.5F, 0F, 0F, 0F, 0.30F);
            return;
        }
        if (hasRewardDrop()) {
            client.drawSolid(0, 0, width, height, 0.5F, 0F, 0F, 0F, 0.20F);
            return;
        }
        client.drawSolid(0, 0, width, height, 0.5F, 0F, 0F, 0F, 0.45F);
        boolean plantWin = client.level().winTeam().contains("plant");
        String text = plantWin ? "胜利！" : "失败！";
        float scale = 4F;
        client.font().draw(text, (width - client.font().width(text, scale)) / 2F, height / 2F + 40, scale,
                plantWin ? 1F : 0.9F, plantWin ? 0.85F : 0.1F, plantWin ? 0.1F : 0.1F, 1F);
        String sub = "点击任意处返回世界选择";
        float subScale = 1.2F;
        client.font().draw(sub, (width - client.font().width(sub, subScale)) / 2F, height / 2F, subScale, 1, 1, 1, 1);
    }

    @Override
    protected void onMouseClicked(double guiX, double guiY, int button) {
        if (!client.level().gameState().equals("running")) {
            // The reward drop is the only clickable thing on a finished board; a click
            // that misses it is not "leave the level", it is a miss.
            if (hasRewardDrop()) {
                if (button == 0 && !isRising() && inside(guiX, guiY, rewardRect())) {
                    claimReward();
                }
                return;
            }
            if (button == 0) {
                client.leaveLevel();
            }
            return;
        }

        // Any visible modal dialog (save prompt, pause, ...) owns input first.
        // The pause dialog is modal too, so it is covered by the same lookup -
        // the explicit branch that used to follow was unreachable.
        Dialog modal = modalDialog();
        if (modal != null) {
            modal.mouseClicked(guiX, guiY, button);
            return;
        }
        if (pauseButton != null && pauseButton.isMouseOver(guiX, guiY)) {
            pauseButton.mouseClicked(guiX, guiY, button);
            return;
        }
        if (speedButton != null && speedButton.isMouseOver(guiX, guiY)) {
            speedButton.mouseClicked(guiX, guiY, button);
            return;
        }

        int slot = slotAt(guiX, guiY);
        if (slot >= 0) {
            if (button == 0) {
                SlotInfo info = client.level().slots().stream().filter(s -> s.index() == slot).findFirst().orElse(null);
                if (info != null && (info.kind().equals("plant") || info.kind().equals("tool"))) {
                    selectedCard = slot;
                    client.connection().send(new PickCardC2S(slot));
                } else {
                    selectedCard = -1;
                }
            }
            return;
        }
        if (!visibleCardSlots().isEmpty()
                && guiX >= cardViewportX && guiX <= cardViewportX + cardViewportWidth
                && guiY >= cardViewportY && guiY <= cardViewportY + cardViewportHeight) {
            return;
        }

        PvzceCamera camera = client.camera();
        // The camera maps raw cursor pixels itself, so hand it the raw pair.
        double rawX = rawMouseX(guiX);
        double rawY = rawMouseY(guiY);

        // Drops are tested before the board bounds reject the click: a sun starts two
        // cells above its landing cell, so for most of its fall it is drawn outside the
        // board and the player is looking straight at something the old order refused
        // to hit.
        if (button == 0) {
            ClientEntity drop = resourceDropAt(rawX, rawY);
            if (drop != null) {
                client.connection().send(new CollectResourceC2S(drop.id()));
                return;
            }
        }

        if (!camera.inBoard(rawX, rawY)) {
            if (button == 1) {
                selectedCard = -1;
            }
            return;
        }
        int cellX = camera.cellX(rawX, rawY);
        int cellY = camera.cellY(rawX, rawY);
        if (cellX < 0 || cellX >= client.level().width() || cellY < 0 || cellY >= client.level().height()) {
            return;
        }

        if (button == 1) {
            selectedCard = -1;
            return;
        }
        if (selectedCard >= 0) {
            SlotInfo selected = client.level().slots().stream()
                    .filter(s -> s.index() == selectedCard).findFirst().orElse(null);
            if (selected != null && selected.kind().equals("tool")) {
                // The glove is a two-click move, so the card stays selected until the
                // plant has been put down - the level says whether it is still carrying
                // one, and the server answers the second click with the drop.
                boolean glove = "pvzce:glove".equals(selected.defId());
                client.connection().send(new UseToolC2S(selectedCard, cellX, cellY));
                if (!glove) {
                    selectedCard = -1;
                }
            } else {
                client.connection().send(new PlacePlantC2S(selectedCard, cellX, cellY));
                selectedCard = -1;
            }
            return;
        }
    }

    /**
     * The resource drop under the cursor, if any.
     *
     * <p>Hit-tested in world space against where the drop is <em>drawn</em> - which is
     * {@code cellY + height} while it is still falling - rather than against the cell it
     * will land on. The old cell-based test could not see a drop that was more than a
     * row above its landing cell, so sun was unclickable for most of its fall even
     * though the player could see it.
     */
    private ClientEntity resourceDropAt(double rawMouseX, double rawMouseY) {
        PvzceCamera camera = client.camera();
        float worldX = camera.worldX(rawMouseX, rawMouseY);
        float worldY = camera.worldY(rawMouseX, rawMouseY);
        // A generous radius: the sprites are small and this is a click, not a shot.
        float radius = 0.45F;
        ClientEntity best = null;
        float bestDistance = Float.MAX_VALUE;
        for (ClientEntity entity : client.level().entities().values()) {
            if (!entity.kind().equals(com.pvzce.api.entity.EntityKind.RESOURCE)) {
                continue;
            }
            float dx = entity.cellX() - worldX;
            float dy = entity.cellY() + Math.max(0F, entity.height()) - worldY;
            float distance = dx * dx + dy * dy;
            if (distance <= radius * radius && distance < bestDistance) {
                best = entity;
                bestDistance = distance;
            }
        }
        return best;
    }

    private int slotAt(double mouseX, double guiY) {
        List<SlotInfo> slots = visibleCardSlots();
        if (slots.isEmpty()) {
            return -1;
        }
        updateCardLayout(slots.size());
        if (mouseX < cardViewportX || mouseX > cardViewportX + cardViewportWidth
                || guiY < cardViewportY || guiY > cardViewportY + cardViewportHeight) {
            return -1;
        }
        int visibleIndex = 0;
        for (SlotInfo slot : slots) {
            float x = cardViewportX + visibleIndex * (cardWidth + cardGap) - cardScrollOffset;
            visibleIndex++;
            if (mouseX >= x && mouseX < x + cardWidth) {
                return slot.index();
            }
        }
        return -1;
    }

    @Override
    protected void onMouseScrolled(double guiX, double guiY, double amount) {
        // No modal check here: Screen.mouseScrolled already routed a modal dialog
        // before calling this hook.
        if (client.level().gameState().equals("running")) {
            List<SlotInfo> slots = visibleCardSlots();
            if (!slots.isEmpty()) {
                updateCardLayout(slots.size());
                if (guiX >= cardViewportX && guiX <= cardViewportX + cardViewportWidth
                        && guiY >= cardViewportY && guiY <= cardViewportY + cardViewportHeight) {
                    int delta = amount > 0 ? -1 : 1;
                    cardScrollOffset = Math.max(0, Math.min(cardMaxScroll,
                            cardScrollOffset + delta * (cardWidth + cardGap)));
                    return;
                }
            }
        }
        
    }

    @Override
    public void keyPressed(int key) {
        Dialog modal = modalDialog();
        if (modal != null && modal != pauseDialog) {
            modal.keyPressed(key);
            return;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (pauseDialog != null && pauseDialog.isVisible()) {
                closePause();
            } else {
                openPause();
            }
            return;
        }
        super.keyPressed(key);
    }

    private void openPause() {
        if (paused || !client.level().gameState().equals("running")) {
            return;
        }
        paused = true;
        if (pauseDialog != null) {
            pauseDialog.setVisible(true);
        }
        // Single-player integrated server: freeze level ticks while the pause
        // dialog is open.
        client.connection().send(new PauseGameC2S(true));
    }

    private void handlePauseDialogClosed() {
        if (!paused) {
            return;
        }
        paused = false;
        client.connection().send(new PauseGameC2S(false));
    }

    private void closePause() {
        if (pauseDialog != null) {
            // Dialog.close() invokes handlePauseDialogClosed through onClose.
            pauseDialog.close();
        } else {
            handlePauseDialogClosed();
        }
    }

    private void cycleSpeed() {
        float tps = client.level().targetTps();
        int next;
        if (Math.abs(tps - 60F) < 0.5F) {
            next = 2;
        } else if (Math.abs(tps - 120F) < 0.5F) {
            next = 3;
        } else {
            next = 1;
        }
        client.connection().send(new SetGameSpeedC2S(next));
    }

    private String speedLabel() {
        float tps = client.level().targetTps();
        if (Math.abs(tps - 60F) < 0.5F) {
            return "1x";
        }
        if (Math.abs(tps - 120F) < 0.5F) {
            return "2x";
        }
        if (Math.abs(tps - 180F) < 0.5F) {
            return "3x";
        }
        return "自定义";
    }
}
