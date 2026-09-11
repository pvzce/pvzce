package com.pvzce.client.gui.screens;

import com.pvzce.api.util.Identifier;
import com.pvzce.client.renderer.EntityVisuals;
import com.pvzce.client.ClientEntity;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.ResourceCollectAnimation;
import com.pvzce.client.gui.Screen;
import com.pvzce.client.gui.SeedCardRenderer;
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
    /** Small visual grass ring beyond the playable board, in world cells. */
    private static final float GRASS_VISUAL_MARGIN_CELLS = 0.12F;

    private int selectedCard = -1;
    private Button pauseButton;
    private Button speedButton;
    private PauseDialog pauseDialog;
    private long lastParticleNanos = System.nanoTime();
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

    @Override
    public void tick() {
        if (!client.level().gameState().equals("running")) {
            closePause();
        }
        if (speedButton != null) {
            speedButton.setLabel(speedLabel());
        }
        EffectEventS2C effect;
        while ((effect = client.level().effects().poll()) != null) {
            if (!effect.particle().isEmpty()) {
                client.particles().spawn(effect.particle(), effect.x(), effect.y());
            }
            if (!effect.sound().isEmpty() && client.sound() != null) {
                client.sound().play(effect.sound(), effect.volume(), effect.pitch());
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
    }

    @Override
    public void render() {
        renderWorld();
        client.beginGuiView();
        renderHud();
        renderSlots();
        renderCollectAnimations();
        renderEndOverlay();
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
        // eating zombie covers the plant; carriers (flower pot / lily pad)
        // and stacked plants share the same layer and are ordered by spawn id,
        // so the later-planted plant appears on top of its carrier.
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
        float spriteXScale = client.spriteXScale();
        EntityVisuals.Visuals visuals = EntityVisuals.of(entity.kind());
        if (entity.layer() == com.pvzce.api.entity.EntityLayers.UNDERGROUND) {
            // Burrowing zombies are shown as a mound instead of a sprite.
            client.drawSolid(entity.cellX() - 0.3F, entity.cellY() - 0.2F, 0.6F, 0.4F, 0.05F,
                    0.4F, 0.28F, 0.16F, 0.9F);
            return;
        }
        client.drawTexture(texture, entity.cellX() - visuals.spriteOffsetX() * spriteXScale,
                entity.cellY() - visuals.spriteOffsetY() + entity.height(),
                visuals.spriteWidth() * spriteXScale, visuals.spriteHeight(), visuals.baseZ(), 1, 1, 1, 1);
    }

    /**
     * Projected entity shadow rendered through the world shader. Direction,
     * length and tint follow the interpolated sun/moon position.
     */
    private void drawShadow(PvzceClient client, ClientEntity entity, Identifier texture) {
        float[] visual = client.animations() == null ? null : client.animations().visualSize(entity);
        float spriteXScale = client.spriteXScale();
        if (entity.kind().equals("plant")) {
            float width = (visual == null ? 0.68F : Math.max(0.20F, visual[0] * 0.85F)) * spriteXScale;
            float height = visual == null ? 0.76F : Math.max(0.20F, visual[1]);
            client.drawEntityShadow(texture, entity.cellX(), entity.cellY() - 0.46F,
                    width, height, 0.34F);
        } else if (entity.kind().equals("zombie") && entity.layer() != -1) {
            float lift = Math.max(0F, entity.height());
            float alpha = Math.max(0.14F, 0.34F - lift * 0.14F);
            float width = (visual == null
                    ? Math.max(0.46F, 0.62F - lift * 0.06F)
                    : Math.max(0.20F, visual[0] * 0.85F)) * spriteXScale;
            float height = visual == null ? 0.95F : Math.max(0.20F, visual[1]);
            client.drawEntityShadow(texture, entity.cellX(), entity.cellY() - 0.46F,
                    width, height, alpha);
        }
    }

    private void renderHud() {
        int height = client.guiHeight();
        boolean sunBankSelected = hasSunBank();

        // The original PvZ-style sun bank only appears when the player picked
        // the SunBank card in the seed chooser.
        if (sunBankSelected) {
            int bankWidth = 70;
            int bankHeight = 78;
            int bankX = 8;
            int bankY = height - bankHeight - 8;
            client.drawTexture(SUN_BANK, bankX, bankY, bankWidth, bankHeight, 0.1F, 1F, 1F, 1F, 1F);
            String sunText = String.valueOf(client.level().sun());
            client.font().draw(sunText, bankX + (bankWidth - client.font().width(sunText, 1F)) / 2F,
                    bankY + bankHeight * 0.08F, 1F, 0.12F, 0.07F, 0.03F, 1F);
        }

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

    private boolean hasSunBank() {
        return hasSunBank(client.level().slots());
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

    /** True when the selected card bar contains the SunBank slot. */
    static boolean hasSunBank(List<SlotInfo> slots) {
        for (SlotInfo slot : slots) {
            if (SUN_CARD_ID.equals(slot.defId())) {
                return true;
            }
        }
        return false;
    }

    /** PvZ-style wave progress meter with huge/final flags and warning. */
    private void renderWaveBar() {
        int total = client.level().totalWaves();
        if (total <= 0) {
            return;
        }
        int width = Math.max(180, Math.min(560, client.guiWidth() - 360));
        int trackHeight = 12;
        int trackX = client.guiWidth() - width - 16;
        int trackY = 18;
        int current = Math.max(0, Math.min(total, client.level().currentWave()));
        float progress = Math.max(0F, Math.min(1F, client.level().waveProgress()));
        if (current >= total) {
            progress = 1F;
        }

        client.drawSolid(trackX - 2, trackY - 2, width + 4, trackHeight + 4, 0.1F, 0.05F, 0.03F, 0.02F, 0.75F);
        client.drawSolid(trackX, trackY, width, trackHeight, 0.1F, 0.22F, 0.24F, 0.12F, 0.9F);
        float fill = width * Math.min(total, current + progress) / total;
        client.drawSolid(trackX, trackY, fill, trackHeight, 0.1F, 0.45F, 0.5F, 0.2F, 0.9F);

        // Horde marker on the western (right) end, like the original meter.
        client.drawTexture(Identifier.withDefaultNamespace("textures/entities/basic_zombie"),
                trackX + width - 4, trackY - 8, 26, 26, 0.1F, 1, 1, 1, 1);

        List<String> waveTypes = client.level().waveTypes();
        for (int i = 0; i < total; i++) {
            String type = i < waveTypes.size() ? waveTypes.get(i) : "small";
            boolean huge = "huge".equals(type) || "final".equals(type);
            boolean finalWave = "final".equals(type);
            float flagX = trackX + width * (i + 0.5F) / total;
            float poleR = huge ? 0.9F : 0.75F;
            float poleG = huge ? 0.35F : 0.75F;
            float poleB = huge ? 0.2F : 0.75F;
            client.drawSolid(flagX - 1, trackY - 8, 2, trackHeight + 8, 0.1F, poleR, poleG, poleB, 1F);

            float flagR;
            float flagG;
            float flagB;
            if (i < current) {
                flagR = 0.25F;
                flagG = 0.9F;
                flagB = 0.25F;
            } else if (finalWave) {
                flagR = 1F;
                flagG = 0.8F;
                flagB = 0.15F;
            } else if (huge) {
                flagR = 0.95F;
                flagG = 0.2F;
                flagB = 0.15F;
            } else {
                flagR = 0.75F;
                flagG = 0.75F;
                flagB = 0.75F;
            }
            client.drawSolid(flagX, trackY + trackHeight - 2, huge ? 12 : 8, 6,
                    0.1F, flagR, flagG, flagB, 1F);
            if (i == current && current < total) {
                client.drawSolid(flagX - 3, trackY - 6, 6, trackHeight + 12,
                        0.1F, 1F, 0.9F, 0.2F, 0.55F);
            }
        }

        String label = "波次 " + current + "/" + total;
        client.font().draw(label, trackX + width / 2F - client.font().width(label, 0.8F) / 2F,
                trackY + trackHeight + 6, 0.8F, 1F, 0.95F, 0.7F, 1F);

        // 3 Hz blink driven by the world clock so it does not speed up with FPS.
        long blinkPhase = (long) (client.level().smoothGameTicks() / 20D);
        if (client.level().waveWarningActive() && blinkPhase % 2 == 0) {
            String warning = client.level().waveWarningFinal()
                    ? "最终波：一大波僵尸正在接近！"
                    : "一大波僵尸正在接近！";
            float scale = 1.4F;
            client.font().draw(warning, (client.guiWidth() - client.font().width(warning, scale)) / 2F,
                    trackY + trackHeight + 28, scale, 1F, 0.25F, 0.2F, 1F);
        }
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
        int sunBankWidth = 70;
        int left = 8 + (hasSunBank() ? sunBankWidth + 8 : 0);
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
     * position to the top-left resource bank. All resource types share the
     * same bank for now (the HUD only has the sun bank).
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
        float bankWidth = 70F;
        float bankHeight = 78F;
        float bankX = 8F;
        float bankY = guiH - bankHeight - 8F;
        float targetX = bankX + bankWidth / 2F;
        float targetY = bankY + bankHeight / 2F;

        for (ResourceCollectAnimation animation : animations) {
            float progress = animation.progress(now);
            if (progress >= 1F) {
                continue;
            }
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
    public void mouseClicked(double mouseX, double mouseY, int button) {
        if (!client.level().gameState().equals("running")) {
            if (button == 0) {
                client.leaveLevel();
            }
            return;
        }

        double guiX = client.guiMouseX(mouseX);
        double guiY = client.guiMouseY(mouseY);

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
        if (!camera.inBoard(mouseX, mouseY)) {
            if (button == 1) {
                selectedCard = -1;
            }
            return;
        }
        int cellX = camera.cellX(mouseX, mouseY);
        int cellY = camera.cellY(mouseX, mouseY);
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
                client.connection().send(new UseToolC2S(selectedCard, cellX, cellY));
            } else {
                client.connection().send(new PlacePlantC2S(selectedCard, cellX, cellY));
            }
            selectedCard = -1;
            return;
        }

        ClientEntity sun = client.level().entities().values().stream()
                .filter(e -> e.kind().equals("sun"))
                .filter(e -> Math.abs(e.cellX() - (cellX + 0.5F)) < 0.9F && Math.abs(e.cellY() - (cellY + 0.5F)) < 1.5F)
                .findFirst()
                .orElse(null);
        if (sun != null) {
            client.connection().send(new CollectResourceC2S(sun.id()));
        }
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
    public void mouseScrolled(double mouseX, double mouseY, double amount) {
        Dialog modal = modalDialog();
        if (modal != null) {
            modal.mouseScrolled(client.guiMouseX(mouseX), client.guiMouseY(mouseY), amount);
            return;
        }
        if (client.level().gameState().equals("running")) {
            List<SlotInfo> slots = visibleCardSlots();
            if (!slots.isEmpty()) {
                updateCardLayout(slots.size());
                double guiX = client.guiMouseX(mouseX);
                double guiY = client.guiMouseY(mouseY);
                if (guiX >= cardViewportX && guiX <= cardViewportX + cardViewportWidth
                        && guiY >= cardViewportY && guiY <= cardViewportY + cardViewportHeight) {
                    int delta = amount > 0 ? -1 : 1;
                    cardScrollOffset = Math.max(0, Math.min(cardMaxScroll,
                            cardScrollOffset + delta * (cardWidth + cardGap)));
                    return;
                }
            }
        }
        super.mouseScrolled(mouseX, mouseY, amount);
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
