package com.pvzce.client.gui.screens;

import com.pvzce.api.util.Identifier;
import com.pvzce.client.renderer.EntityVisuals;
import com.pvzce.client.ClientEntity;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.ResourceCollectAnimation;
import com.pvzce.client.gui.GuiLang;
import com.pvzce.client.gui.Screen;
import com.pvzce.client.gui.hud.cardbar.CardBar;
import com.pvzce.client.gui.hud.cardbar.CardBarLayout;
import com.pvzce.client.input.ScrollRegion;
import com.pvzce.client.gui.SeedCardRenderer;
import com.pvzce.common.core.SlotResolver;
import com.pvzce.common.PvzceConstants;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.network.packet.LevelRewardS2C;
import com.pvzce.common.util.MathUtil;
import com.pvzce.client.gui.components.Button;
import com.pvzce.client.gui.components.DialogueOverlay;
import com.pvzce.client.gui.components.Dialog;
import com.pvzce.client.renderer.PvzceCamera;
import com.pvzce.client.renderer.LevelStage;
import com.pvzce.client.renderer.SceneTileRenderer;
import com.pvzce.common.network.packet.CollectResourceC2S;
import com.pvzce.common.network.packet.EffectEventS2C;
import com.pvzce.common.network.packet.PickCardC2S;
import com.pvzce.common.network.packet.PlacePlantC2S;
import com.pvzce.common.network.packet.PauseGameC2S;
import com.pvzce.common.network.packet.SetGameSpeedC2S;
import com.pvzce.api.content.SlotDef;
import com.pvzce.common.network.packet.SlotInfo;
import com.pvzce.common.network.packet.UseToolC2S;
import org.lwjgl.glfw.GLFW;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** The playable level: board rendering + card bar + HUD. */
public final class InGameScreen extends Screen implements com.pvzce.client.gui.hud.cardbar.CardBar.Host {

    /** How wide a health bar is, in cells, and how far above the entity it sits. */
    private static final float HEALTH_BAR_CELLS = 0.72F;
    private static final float HEALTH_BAR_GAP = 0.12F;
    /** Above the entities and their shadows, below the HUD (which is drawn in GUI space). */
    private static final float HEALTH_BAR_Z = 0.62F;
    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("PVZCE/InGame");
    private static final Identifier SUN_BANK = Identifier.withDefaultNamespace("textures/gui/hud/sun_bank");
    /** The resource card that toggles the sun bank instead of a normal packet. */
    private static final String SUN_CARD_ID = com.pvzce.common.PvzceIds.SUN.toString();
    /** The one tool card with its own chrome and its own pick-up sound. */
    private static final String SHOVEL_CARD_ID = "pvzce:shovel";
    /**
     * The glove, which is the one card that has to stay clickable while it recharges.
     *
     * <p>See {@link #gloveCard}: the glove is a two-click move and its cooldown starts on the
     * first click, so refusing the card while it recharges refuses the second click too.
     */
    private static final String GLOVE_ID = "pvzce:glove";
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
    /**
     * The clip a click asks the default tool's cursor for.
     *
     * <p>Not an {@link com.pvzce.api.entity.EntityAnimations} state: those are the wire states
     * the server publishes, and nothing on the server knows or cares that the cursor swings -
     * the blow is already the server's, this is only its gesture. It is a clip name in the
     * tool's own animation file, like the {@code drive} clip the lawn mower's mechanic asks for.
     */
    private static final String ATTACK_CLIP = "attack";
    /**
     * How much bigger the cursor's box is than the card sprite's was.
     *
     * <p>The sprite was drawn inside a transparent margin while an animation's box is the
     * model's bounding box, so the same number would arrive as a visibly smaller mallet.
     */
    private static final float CURSOR_BOX_SCALE = 1.35F;
    /**
     * Half the world-space box the cursor's animation is projected into, in cells.
     *
     * <p>Half of the hammer model's own 0.5-cell box: the projection is square and the drawing
     * scale is 1, so the model lands in the box at exactly the size the converter authored it.
     */
    private static final float CURSOR_HALF_CELLS = 0.25F;
    /**
     * How long a summoned swing stays on the board, in nanoseconds.
     *
     * <p>The hammer's own {@code attack} clip: 0.75 s of keyframes played at the clip's rate of
     * 2.5, and then the file hands itself back to its held pose. A swing is forgotten when that
     * beat is over, because unlike the cursor it has nothing to go back to - the mallet was called
     * up to hit one thing and is not something the player is holding.
     */
    private static final long MALLET_SWING_NANOS = 300_000_000L;

    /** The mallet a summoned swing is drawn from: the same tool the cursor uses. */
    private static final Identifier HAMMER_ID = Identifier.withDefaultNamespace("hammer");

    /**
     * The picture the board is played on: the level's own backdrop, or the built-in yard.
     *
     * <p>The level's declaration wins even when the texture is missing, so a level that names
     * a pack's backdrop and is played without that pack shows the missing-texture checkerboard
     * rather than a yard that is not the level's - the same loud failure every other missing
     * texture gets.
     */
    private Identifier backdropTexture() {
        Identifier declared = client.level().background();
        return declared == null ? LevelStage.BACKGROUND_TEXTURE : declared;
    }

    /**
     * The arm a zombie reaches out of its grave with, and its size in world cells.
     *
     * <p>The original's own sprite (26x50 px of an 80x100 cell), named here rather than in a
     * particle definition because the thing it illustrates is a state of the zombie - see
     * {@link #renderRisingZombies} - and an effect that runs on the wall clock would keep
     * waving while a paused zombie hangs half out of the ground.
     */
    private static final Identifier RISE_ARM_TEXTURE =
            Identifier.withDefaultNamespace("textures/particles/zombie/zombiearm");
    // 26x50 pixels, i.e. 0.325 x 0.5 of an 80x100 cell: the same size the dropped arm is
    // drawn at (`pvzce:zombie_arm`), because it is the same arm.
    private static final float RISE_ARM_WIDTH_CELLS = 0.325F;
    private static final float RISE_ARM_HEIGHT_CELLS = 0.5F;
    /** The arm sprite's own pixel size, the unit {@code drawTextureQuad} takes its UVs in. */
    private static final float RISE_ARM_SPRITE_WIDTH_PX = 26F;
    private static final float RISE_ARM_SPRITE_HEIGHT_PX = 50F;
    /**
     * The arm's turn, and when it is fully out and starts going back under.
     *
     * <p>All three are fractions of the climb, and the first is also when the body starts:
     * one second of climbing spends its first {@code 0.3} on a hand pushing up out of the
     * dirt and the rest on the body following it, which is how the original stages a grave
     * opening. The arm goes back under as the body passes it - it has to be gone by the time
     * the climb ends, because this whole pass stops drawing the moment it does.
     */
    private static final float RISE_ARM_CLIMB = 0.3F;
    private static final float RISE_ARM_OUT_CLIMB = 0.2F;
    private static final float RISE_ARM_HOLD_CLIMB = 0.45F;
    /**
     * The "Ready... Set... Plant!" banner, timed from the original reanim rather than from
     * a constant: it is three words and lasts exactly as long as they do.
     */
    private static final com.pvzce.client.gui.components.BannerAnimation ENTRY_BANNER =
            com.pvzce.client.gui.components.BannerAnimation.readySetPlant();
    /** The "FINAL WAVE" banner, played when the final wave's own sound lands. */
    private static final com.pvzce.client.gui.components.BannerAnimation FINAL_WAVE_BANNER =
            com.pvzce.client.gui.components.BannerAnimation.finalWave();
    /** How wide the banners are drawn, as a fraction of the window's smaller axis. */
    private static final float BANNER_WIDTH_FRACTION = 0.62F;
    /** Where the banner sits vertically, as a fraction of the window height. */
    private static final float BANNER_CENTER_Y = 0.56F;
    /** The huge-wave warning's own fade, in and out, in seconds. */
    private static final float WAVE_WARNING_FADE_SECONDS = 0.4F;
    /** How much the warning grows over its whole window. */
    private static final float WAVE_WARNING_GROWTH = 0.12F;
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
     * The white light the reward gives off once it has been presented.
     *
     * <p>Not a fade-through-white of the whole screen: the original's light starts
     * <em>at the reward</em> and spreads outward until it has swallowed the board, which is
     * what makes it read as the reward being collected rather than as the game changing
     * scene. Its source is captured at the click - where the reward was when the player
     * touched it - so the light looks like it came out of the packet even though the packet
     * has travelled to the middle of the window by the time the spread begins.
     */
    private static final long REWARD_LIGHT_SPREAD_NANOS = 700_000_000L;
    /** Held fully white for this long after the light has covered the screen. */
    private static final long REWARD_LIGHT_HOLD_NANOS = 350_000_000L;
    /**
     * How long the player holds the mouse on a parked mower to send it by hand.
     *
     * <p>Not a click: a parked mower is a one-shot, level-long resource - spending one on a
     * wave that was already handled costs the player that row for the rest of the level -
     * so the gesture has to be deliberate. Half a second is long enough that no ordinary
     * click on the board can trigger it by accident, and short enough that it does not feel
     * like the game is ignoring the button.
     */
    private static final long MOWER_HOLD_NANOS = 500_000_000L;
    /** The bar that fills while a mower is being held, in GUI pixels. */
    private static final float MOWER_HOLD_BAR_WIDTH = 46F;
    private static final float MOWER_HOLD_BAR_HEIGHT = 7F;
    /** How far above the mower's own cell centre the bar sits, in cells. */
    private static final float MOWER_HOLD_BAR_LIFT_CELLS = 0.68F;

    /**
     * The original's own pointer: {@code DownArrow.png} from the rip, which is what
     * {@code AwardPickupArrow.xml} hangs over a reward the player has to click.
     */
    private static final Identifier REWARD_ARROW =
            Identifier.withDefaultNamespace("textures/gui/hud/down_arrow");
    private static final float REWARD_ARROW_ART_WIDTH = 32F;
    private static final float REWARD_ARROW_ART_HEIGHT = 26F;
    /** 32px of art at the original's 80 pixels per cell. */
    private static final float REWARD_ARROW_WIDTH_CELLS = 0.4F;
    /**
     * How far the arrow bobs, and how long one half-cycle takes.
     *
     * <p>The original moves it over ten frames at 12 fps - about 0.83s down and back - so the
     * period is that doubled. In GUI pixels rather than cells: the arrow is the game pointing
     * at something, so it must not shrink with the board.
     */
    private static final float REWARD_ARROW_BOB_PIXELS = 10F;
    private static final long REWARD_ARROW_PERIOD_NANOS = 1_660_000_000L;
    /** Clearance between the reward's top edge and the arrow's tip. */
    private static final float REWARD_ARROW_GAP_PIXELS = 6F;
    /** The reward's own on/off flash while it waits to be claimed. */
    private static final long REWARD_FLASH_PERIOD_NANOS = 620_000_000L;
    private static final float REWARD_FLASH_MIN_BRIGHTNESS = 0.62F;
    /**
     * How the reward's own coin bonus is broken into visible coins.
     *
     * <p>One coin per hundred, so the number the player watches matches the number they
     * were paid; the cap keeps a large clear from turning the celebration into a swarm.
     * The ids are negative because collect animations are keyed by entity id and no entity
     * has one - the mowers use the same trick with their own offset.
     */
    private static final int REWARD_BAG_MAX_COINS = 12;
    private static final int REWARD_BAG_FIRST_ID = -10_000;
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
     * How long a card that cannot be played shakes, and how far.
     *
     * <p>The original answers an unusable card with the buzzer alone. The sound says
     * "no"; the shake says <em>which card</em> said it, which a player clicking a row of
     * eight of them cannot hear.
     */
    private static final long CARD_REFUSED_SHAKE_NANOS = 380_000_000L;
    private static final float CARD_REFUSED_SHAKE_PIXELS = 6F;
    /**
     * The defeat sequence: look at the house, watch the zombie eat, then the original's
     * screen.
     *
     * <p>A defeat is not a state the player reads off a counter, it is a thing that
     * happened: the zombie that got through is standing in the doorway eating. So the
     * camera turns to it for a beat before the game says anything, which is also what the
     * original does.
     */
    private static final long DEFEAT_PAN_NANOS = 900_000_000L;
    private static final long DEFEAT_CHEW_NANOS = 1_200_000_000L;
    /** How far toward the house the defeat turns; clamped to the backdrop's own edge. */
    private static final float DEFEAT_PAN_CELLS = 2.1F;
    /** The bite that plays while the loser eats, and how often. */
    private static final long DEFEAT_CHOMP_PERIOD_NANOS = 600_000_000L;
    /**
     * How long a wave announcement stays "already played" in this level instance.
     *
     * <p>Longer than any gap between two genuine waves of one level (the shortest built-in
     * gap is half a minute), so it only ever swallows a duplicate of the same announcement,
     * never the next wave's own call.
     */
    private static final long ANNOUNCEMENT_ONCE_NANOS = 90_000_000_000L;

    private int selectedCard = -1;
    private final com.pvzce.client.renderer.EchoLilyLinks echoLilyLinks =
            new com.pvzce.client.renderer.EchoLilyLinks();
    /**
     * The plant the player is aiming right now, or -1.
     *
     * <p>The cob cannon's first click. Aiming is a <em>client</em> mode with no server mirror - the
     * same shape as the card selection ({@code PickCardC2S} is sent and ignored) - because the only
     * thing it changes is what the next click means, and the server re-derives everything that
     * matters when the shot is actually fired: that entity, that cell, whether the cannon is loaded.
     * A shot at a cannon the player is not allowed to fire is refused with a reason, which is what
     * makes remembering it locally safe.
     *
     * <p>Held by entity id rather than by cell because the plant can be eaten while the player aims
     * (and because two plants can stand in one cell, one on the other).
     */
    private int aimingPlantId = -1;
    /**
     * The content id to put back in hand once the bar has been rebuilt, or {@code null}.
     *
     * <p>Set while the bar is being replaced and consumed on the same call, because the new bar is
     * built from {@code ClientLevel.slots()} which the bar replacement also rewrites - there is no
     * moment in between where both the old selection and the new bar exist.
     */
    private String pendingSelection;
    /**
     * The slot whose click lifted the plant the client is now carrying, or -1.
     *
     * <p>The second click of a move is not "the selected card": a tool card is put back after
     * each click, so by the time the player clicks the lawn to put the plant down nothing is
     * selected any more - which is why the drop used to need the glove clicked again first.
     * The move remembers the card it started from instead.
     */

    /** The carry state the last frame saw, so a *change* in it can be noticed; see syncCarry. */
    private String lastCarried = "";
    /** The seed-packet-in-hand state the last frame saw; part of the same change detection. */
    private boolean lastHoldingPacket;
    /**
     * When each sleeping plant last breathed a Zzz, and which size is due next.
     *
     * <p>Per-entity rather than per-level: two mushrooms do not share a breath, and the map is
     * tiny (only plants that are actually asleep ever get an entry). Entries are not cleared
     * when a plant wakes - the id is never reused within a level, and a level's entity count is
     * in the dozens, so the map is not worth the bookkeeping.
     */
    private final java.util.Map<Integer, Long> zzzNextNanos = new java.util.HashMap<>();
    private final java.util.Map<Integer, Integer> zzzStep = new java.util.HashMap<>();
    /**
     * How visible the placement ghost is.
     *
     * <p>Solid enough to recognise the plant, faint enough that the cell it covers is still
     * readable - the original's preview is a hint, not the plant itself.
     */
    private static final float PLACEMENT_PREVIEW_ALPHA = 0.5F;
    /** Id of the detached preview entity; it is never part of the level's entity table. */
    private static final int PLACEMENT_PREVIEW_ID = 1_000_001;
    /** The ghost of the plant about to be placed, or {@code null}. */
    private ClientEntity placementPreview;
    private Button pauseButton;
    private Button speedButton;
    /**
     * The preparation phase's "开始" button, created lazily.
     *
     * <p>Not in {@link #init()} like the other two: whether a level has a preparation phase arrives
     * with the level's streamed state, which is after the screen was built. Created the first time
     * the phase is seen, hidden when it ends.
     */
    private Button startWavesButton;
    private PauseDialog pauseDialog;
    private long lastParticleNanos = System.nanoTime();
    /**
     * When each one-shot announcement last played, by sound id.
     *
     * <p>The screen is rebuilt for each level, so the map is per level instance. See
     * {@link #playEffectSound}.
     */
    private final java.util.Map<String, Long> announcementsPlayed = new java.util.HashMap<>();
    /**
     * When the level was handed to the player; drives the entry banner's fade.
     *
     * <p>Zero while the opening dialogue is still up: "准备… 安放… 种植！" is the moment
     * play starts, so it cannot have faded away behind a conversation. A level without a
     * dialogue sets this the instant its screen appears, which is what it always did.
     */
    private long entryNanos;
    /**
     * When the "FINAL WAVE" banner started, or zero.
     *
     * <p>Started by the final wave's own {@code awooga} effect rather than by the warning
     * flag: in the original the words land with the wave, and that sound is the wave
     * landing.
     */
    private long finalWaveNanos;
    /**
     * When the huge-wave warning currently on screen began, and whether one is up.
     *
     * <p>The server only says "a warning is showing"; the fade needs to know when it
     * started, and the transition is the only place that is observable.
     */
    private long waveWarningNanos;
    private boolean waveWarningShown;
    /** When the warning went away, or zero while one is up. Drives its fade-out. */
    private long waveWarningEndedNanos;
    /** The level's opening conversation, or {@code null} when it has none. */
    private DialogueOverlay dialogue;
    /** True while this screen is holding the server paused for a dialogue. */
    private boolean dialogueHoldsPause;
    private final com.pvzce.api.content.LevelDialogue openingDialogue;
    /**
     * How far through the level's timed lines this screen has got.
     *
     * <p>An index rather than a queue of remaining lines: the script is content, and "which line is
     * next" is the only thing the screen has to remember. Read from the level's own definition, like
     * the dialogue and the hints, because the client loads the same data packs.
     */
    private int timedLineIndex;
    /** The timed line currently on screen, or {@code null}. */
    private DialogueOverlay timedDialogue;
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
    /**
     * Where the reward's white light starts, in GUI pixels, and when it started.
     *
     * <p>Both are captured on the click, at the rectangle the reward was drawn in at that
     * instant: the light has to look like it came out of the packet, and by the time it has
     * finished the packet has travelled to the middle of the window, so "where the reward
     * is now" would put the source somewhere the player never saw it.
     */
    private float rewardLightX;
    private float rewardLightY;
    private long rewardLightNanos;
    /**
     * Coins the victory paid for the lawn mowers that were never needed.
     *
     * <p>Client-side only: the wallet was credited by the server before the reward packet,
     * and this is the same amount added to the on-screen tally so the bank the coins fly
     * into shows the number they made. Without it the coins arrived at a counter that did
     * not move.
     */
    private int claimedMowerCoins;
    /**
     * The reward's own coin bonus, added to the tally for the same reason.
     *
     * <p>Without it the bag's coins fly into a counter that never changes: the wallet was
     * credited by the server before the packet arrived, so the on-screen number only moves
     * if the client says it did. The tally is a receipt, not a ledger.
     */
    private int claimedRewardCoins;
    /** The card currently shaking off a refused click, or {@code -1}. */
    private int refusedCard = -1;
    private long refusedCardNanos;
    /**
     * When the defeat sequence started, or zero while nothing has been lost.
     *
     * <p>Zero also means "the level is running, or it was won": only a zombie win sets it.
     */
    private long defeatNanos;
    /** The zombie eating its way into the house, whose animation is held on "eat". */
    private int defeatZombieId = -1;
    /** When the last bite played, so the chewing is audible without being a loop. */
    private long defeatChompNanos;
    /** How far the defeat has turned the camera toward the house, in cells. */
    private float defeatPan;
    private boolean paused;
    /**
     * The rhythm chart this level plays, or {@code null}.
     *
     * <p>Built once when the level starts: the chart is level data and does not change, and the
     * playing state inside it (which notes have been pressed) has to survive every frame.
     */
    private com.pvzce.client.RhythmPlay rhythm;
    /**
     * The last jalapeno total the screen saw, and when the banner for the newest one started.
     *
     * <p>A total rather than a flag: the reward is announced when the number goes up, which is an
     * event this side can see in the state it already receives (see {@code RhythmMechanic.Status}).
     */
    private int lastJalapenos;
    private long jalapenoBannerNanos;

    /**
     * The card a press picked up, or {@code -1}.
     *
     * <p>Set on the press and read on the release, so a card can be dragged from the bar to
     * a cell. It is deliberately separate from {@link #selectedCard}: a click that selects a
     * card and lets go without leaving the bar is the old gesture and must keep working.
     */
    private int draggingCard = -1;
    /** True while the button is held on the board, so passing over a pickup collects it. */
    private boolean sweeping;
    /** The last drop a sweep asked for, so the same one is not requested every frame. */
    private int lastSweptDropId = -1;
    /**
     * The original's grey box at the bottom of the board, and the level's script for it.
     *
     * <p>Every trigger is decided here rather than by a packet: the three moments a hint can
     * key off - the board coming up, a drop being clicked, a card being refused - are all
     * things this screen already sees. See {@link com.pvzce.client.gui.hud.HintBox}.
     */
    private final com.pvzce.client.gui.hud.HintBox hints =
            new com.pvzce.client.gui.hud.HintBox(client);
    private com.pvzce.client.gui.hud.LevelHints levelHints;
    private boolean hintsStarted;
    /** True once the player has clicked any drop, so the "click a sun" lesson stops. */
    private boolean clickedAnyDrop;
    /**
     * The row of the parked mower the mouse is being held on, or {@code -1}.
     *
     * <p>By row rather than by position: the mower is a thing the player pointed at, and if
     * the pointer drifts a few pixels while the ring fills the gesture must not be lost.
     */
    private int mowerHoldRow = -1;
    private long mowerHoldNanos;
    /**
     * The level's card bar: the ordinary seed row, or whatever a mechanic deals.
     *
     * <p>Created lazily and kept across resizes - the screen is rebuilt for every level, so
     * "this screen" is already the bar's lifetime - because a belt that re-entered from the
     * right every time the window changed size would look like a delivery.
     */
    private CardBar cardBar;
    /**
     * The mutation announcements: the banner, the list down the right edge, the dark wash.
     *
     * <p>Owned by the screen rather than by the level mirror because everything it holds is
     * presentation state - which banner is on screen and when it started - and the level mirror is
     * replaced wholesale whenever the client enters a level.
     */
    private final com.pvzce.client.gui.hud.MutationHud mutationHud =
            new com.pvzce.client.gui.hud.MutationHud();
    /** The mutation packet this screen already reacted to; identity stands in for a revision. */
    private com.pvzce.common.network.packet.MutationStateS2C mutationRevision;
    /** Which kind of card bar is currently built, so a mutation's takeover can be noticed. */
    private String cardBarKind = "";
    /** World-space overlays the level's mechanics ask for (the plantable area's line). */
    private java.util.List<com.pvzce.client.mechanic.ClientMechanic.WorldOverlay> overlays;
    /** The default tool's cursor animation, built on first draw; see {@link #toolCursor}. */
    private com.pvzce.client.animation.ArtTarget toolCursor;
    /**
     * The mallets called up by clicking vases, in the order they were swung.
     *
     * <p>Client-side and short-lived, and that is the whole design of the interaction: a click on
     * a container is a request to the server ({@code SmashContainerC2S}) plus a gesture the client
     * plays over the cell. Nothing about the mallet travels, which is why the vase level no longer
     * has to grant a hammer for its pots to be breakable - the swing exists in every level.
     *
     * <p>A list rather than one mallet, because a player clearing a row of pots clicks faster than
     * one swing lasts and the second must not cut the first short.
     */
    private final java.util.List<MalletSwing> malletSwings = new java.util.ArrayList<>();
    /** Which tool {@link #toolCursor} was built for, so a changed tool rebuilds it. */
    private Identifier toolCursorTool;

    /** One summoned swing: where it lands, when it started, and the art playing it. */
    private static final class MalletSwing {
        final int cellX;
        final int cellY;
        final long startNanos = System.nanoTime();
        com.pvzce.client.animation.ArtTarget target;

        MalletSwing(int cellX, int cellY) {
            this.cellX = cellX;
            this.cellY = cellY;
        }
    }

    public InGameScreen(PvzceClient client) {
        this(client, com.pvzce.api.content.LevelDialogue.EMPTY);
    }

    /**
     * @param openingDialogue the level's opening conversation. A level entered directly -
     *                        a conveyor level, which never shows the seed chooser - has no
     *                        other screen to show it on, so it plays here, over the lawn,
     *                        with the level paused until it is over. A level entered
     *                        through the chooser passes the empty block: it has already
     *                        been shown there, and replaying it here would say everything
     *                        twice.
     */
    public InGameScreen(PvzceClient client, com.pvzce.api.content.LevelDialogue openingDialogue) {
        super(client);
        this.openingDialogue = openingDialogue == null
                ? com.pvzce.api.content.LevelDialogue.EMPTY : openingDialogue;
    }

    @Override
    protected void init() {
        // The preparation button is rebuilt from scratch here rather than kept: `init()` runs again
        // after a resize or a GUI-scale change, and `clearWidgets()` has already thrown the old
        // widget away - a field still pointing at it would be a button nothing draws.
        startWavesButton = null;
        // The rhythm chart is level content, and it is read before the widgets are built: whether
        // this level has one decides whether the speed button exists at all. Built once per level
        // rather than per init, because the chart's own state - which notes have been pressed -
        // has to survive a resize.
        if (rhythm == null && client.level() != null) {
            rhythm = com.pvzce.client.RhythmPlay.forLevel(client.level());
        }
        int width = client.guiWidth();
        int height = client.guiHeight();
        // Pause is the rightmost thing on the bar and the tallest; the speed button sits to its
        // left, matching its height so the pair reads as one row, and narrower because "1x" is two
        // glyphs where "暂停" is two hanzi wide.
        int pauseWidth = Math.min(154, width / 3);
        int pauseHeight = Math.min(56, height / 5);
        int speedWidth = Math.min(64, Math.max(44, width / 10));
        int pauseX = Math.max(12, width - pauseWidth - 12);
        pauseButton = new Button(pauseX, height - pauseHeight - 12,
                pauseWidth, pauseHeight, "暂停", this::openPause);
        int speedX = Math.max(12, pauseX - speedWidth - 8);
        speedButton = new Button(speedX, height - pauseHeight - 12,
                speedWidth, pauseHeight, speedLabel(), this::cycleSpeed);
        // A rhythm level does not show the speed control at all: its chart is written against the
        // level's tick count, so 2x would not move the notes, it would halve the time to answer
        // them. The server refuses the packet too (see LevelServer.forbidsSpeedChange) - this is
        // the half that stops the player from asking.
        if (rhythm == null) {
            addWidget(speedButton);
        }
        addWidget(pauseButton);
        pauseDialog = PauseDialog.create(client);
        pauseDialog.onClose(this::handlePauseDialogClosed);
        pauseDialog.setVisible(paused);
        if (paused) {
            client.connection().send(new PauseGameC2S(true));
        }
        addWidget(pauseDialog);
        // Added last so the dialogue is drawn over the HUD, and as a modal so it - not the
        // pause/speed buttons - receives the clicks that advance it. Built once and
        // re-attached on a resize, so a resize mid-conversation does not start it over.
        if (dialogue == null) {
            dialogue = DialogueOverlay.create(client, openingDialogue, this::onDialogueFinished);
        }
        if (dialogue != null) {
            showDialog(dialogue);
            if (dialogue.isActive() && !dialogueHoldsPause) {
                // The level is already running - the server is not waiting for anything -
                // so a conversation over it would cost the player the ticks it takes to
                // read. Freeze the level, not the client: the portrait and the bubble are
                // drawn by the client and keep animating.
                dialogueHoldsPause = true;
                client.connection().send(new PauseGameC2S(true));
            }
        }
        if ((dialogue == null || !dialogue.isActive()) && entryNanos == 0L) {
            entryNanos = System.nanoTime();
        }
        startHints();
    }

    /**
     * Starts the level's hint script, once per screen.
     *
     * <p>Called from {@code init()} rather than from the constructor because this screen is
     * constructed before the level it describes exists on the client - the payload arrives
     * with {@code LevelInitS2C}, which {@code PvzceClient} applies and only then builds the
     * screen. Read from the local level definition, like the dialogue and the belt flag:
     * hints are level content, and the client already loads the same data packs.
     */
    private void startHints() {
        if (hintsStarted) {
            return;
        }
        hintsStarted = true;
        String levelId = client.level().levelId();
        com.pvzce.api.util.Identifier id =
                levelId == null ? null : com.pvzce.api.util.Identifier.tryParse(levelId);
        com.pvzce.api.content.LevelDef def = id == null
                ? null
                : com.pvzce.common.core.BuiltInRegistries.LEVELS.get(id);
        levelHints = new com.pvzce.client.gui.hud.LevelHints(hints, def);
        levelHints.onLevelStart();
    }

    /**
     * Plays the level's timed lines, one at a time, as their ticks come up.
     *
     * <p>Read from the local level definition: {@code timed} lines are content, like the opening
     * conversation, and the client already has the same data packs as the server. The tick they are
     * aimed at is the level's own counter, so a line written against a mutation's schedule fires on
     * the same beat as that mutation - which is the whole reason a tutorial can say "look, the bar
     * just changed" and be right.
     *
     * <p>One at a time, and the level is paused while one is up: a line that is read while a zombie
     * is eating the lawn is a line nobody reads. The pause is the same one the opening conversation
     * takes, so a level whose opening dialogue is still on screen simply waits.
     */
    private void tickTimedDialogue() {
        if (timedDialogue != null && timedDialogue.isActive()) {
            return;
        }
        if (dialogue != null && dialogue.isActive()) {
            return;
        }
        if (!client.level().gameState().equals("running")) {
            return;
        }
        java.util.List<com.pvzce.api.content.LevelDialogue.Timed> script = timedLines();
        if (timedLineIndex >= script.size()) {
            return;
        }
        // The level's own counter, not the server's: the server's counts across levels, so a
        // moment written against it would be "already passed" the instant a second level started.
        long tick = client.level().levelTickCount();
        if (tick < script.get(timedLineIndex).atTick()) {
            return;
        }
        com.pvzce.api.content.LevelDialogue.Timed next = script.get(timedLineIndex++);
        DialogueOverlay overlay = DialogueOverlay.create(client,
                new com.pvzce.api.content.LevelDialogue(
                        java.util.List.of(next.line()),
                        com.pvzce.api.content.DialogueEffect.SLIDE,
                        com.pvzce.api.content.DialogueEffect.SLIDE),
                this::onTimedDialogueFinished);
        if (overlay == null) {
            return;
        }
        timedDialogue = overlay;
        showDialog(overlay);
        if (!dialogueHoldsPause) {
            // Same hold the opening conversation takes: the level freezes for as long as the player
            // takes to read, and the next line waits behind it.
            dialogueHoldsPause = true;
            client.connection().send(new PauseGameC2S(true));
        }
    }

    /** The level's timed lines, or an empty list when it has none. */
    private java.util.List<com.pvzce.api.content.LevelDialogue.Timed> timedLines() {
        String levelId = client.level().levelId();
        com.pvzce.api.util.Identifier id =
                levelId == null ? null : com.pvzce.api.util.Identifier.tryParse(levelId);
        com.pvzce.api.content.LevelDef def = id == null
                ? null : com.pvzce.common.core.BuiltInRegistries.LEVELS.get(id);
        return def == null ? java.util.List.of() : def.dialogue().timed();
    }

    /** A timed line is over: let go of the level and forget the overlay. */
    private void onTimedDialogueFinished() {
        timedDialogue = null;
        onDialogueFinished();
    }

    /** The conversation is over: unpause and let the "准备… 安放… 种植！" banner play. */
    private void onDialogueFinished() {
        if (dialogueHoldsPause) {
            dialogueHoldsPause = false;
            client.connection().send(new PauseGameC2S(false));
        }
        if (entryNanos == 0L) {
            entryNanos = System.nanoTime();
        }
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
     * Drives the defeat sequence: turn to the house, hold on the loser, then show the
     * original's screen.
     *
     * <p>Started from the state transition rather than from a packet: a loss is the
     * client's own mirror reaching "not running with the zombie team winning", and the
     * server has nothing more to say about it.
     */
    private void tickDefeat() {
        if (!client.level().gameState().equals("running")) {
            if (defeatNanos == 0L && isDefeat()) {
                startDefeat();
            }
        }
        if (defeatNanos == 0L) {
            return;
        }
        long elapsed = System.nanoTime() - defeatNanos;
        PvzceCamera camera = client.camera();
        float travel = Math.min(DEFEAT_PAN_CELLS, camera.maxPanX());
        defeatPan = travel * MathUtil.easeInOut(
                MathUtil.clamp01(elapsed / (float) DEFEAT_PAN_NANOS));
        if (elapsed >= DEFEAT_PAN_NANOS && elapsed < DEFEAT_PAN_NANOS + DEFEAT_CHEW_NANOS
                && System.nanoTime() - defeatChompNanos >= DEFEAT_CHOMP_PERIOD_NANOS) {
            defeatChompNanos = System.nanoTime();
            if (client.sound() != null) {
                client.sound().play(com.pvzce.common.PvzceSounds.EFFECT_BITE.toString(), 1F, 1F);
            }
        }
    }

    /**
     * "存活 N 轮 · 击杀 M · 用时 T" - the three numbers a run leaves behind.
     *
     * <p>Waves rather than a score: an endless level has no score, and the wave count is the same
     * currency an ordinary level's progress bar already uses, so the player has been reading it all
     * along.
     */
    private String runSummary() {
        int seconds = Math.max(0, client.level().survivedTicks() / 60);
        String clock = String.format(java.util.Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
        return "存活 " + client.level().wavesArrived() + " 波 · 击杀 " + client.level().kills()
                + " · 用时 " + clock;
    }

    /**
     * What the run amounted to, as the lines a finished board shows.
     *
     * <p>A rhythm level gets the song's report instead of the wave line, and that is not a
     * decoration: those levels have no waves at all (their zombies are generated from the chart),
     * so "存活 0 波" is not a summary of the run, it is a wrong one. What a player wants to read
     * there is how they played - the four verdicts and the streak - which is the same text the
     * award page prints when they won.
     */
    private java.util.List<String> runSummaryLines() {
        java.util.List<String> rhythm =
                com.pvzce.client.gui.RhythmScoreText.lines(client.level().rhythmScore());
        return rhythm.isEmpty() ? java.util.List.of(runSummary()) : rhythm;
    }

    /**
     * True when the level ended with the player losing.
     *
     * <p>Read from the game state rather than from which team won: {@code WON}/{@code LOST} are the
     * *player's* outcome (see {@code LevelServer.markEnd}), while the winning team's id says which
     * side of the board it happened on. On I, Zombie those two are opposite - the zombies won and
     * so did the player - so the id is the one answer that cannot be asked here.
     */
    private boolean isDefeat() {
        return client.level().gameState().equals("lost");
    }

    /**
     * Begins the defeat: the loser starts eating the house.
     *
     * <p>The animation is set here rather than sent by the server because the level stops
     * ticking the moment it is lost, so whatever the zombie was doing when it crossed the
     * line - walking - is what it would be frozen in. "Eat" is the truth of what just
     * happened, and it is presentation: nothing else reads this entity again.
     */
    private void startDefeat() {
        defeatNanos = System.nanoTime();
        // Set to "now", not zero: the first bite then lands at the end of the pan rather
        // than being measured against the epoch.
        defeatChompNanos = defeatNanos;
        defeatPan = 0F;
        ClientEntity loser = leftmostZombie();
        if (loser != null) {
            defeatZombieId = loser.id();
            loser.setAnimation(com.pvzce.api.entity.EntityAnimations.EAT);
            loser.playAnimation(com.pvzce.api.entity.EntityAnimations.EAT);
        }
    }

    /** The ground zombie furthest into the house, i.e. the one that ended the level. */
    private ClientEntity leftmostZombie() {
        ClientEntity best = null;
        for (ClientEntity entity : client.level().entities().values()) {
            if (!com.pvzce.api.entity.EntityKind.ZOMBIE.equals(entity.kind())
                    || entity.layer() != com.pvzce.api.entity.EntityLayers.GROUND) {
                continue;
            }
            if (best == null || entity.cellX() < best.cellX()) {
                best = entity;
            }
        }
        return best;
    }

    /** True while the defeat sequence is still moving the camera or showing the eating. */
    private boolean defeatSequenceRunning() {
        return defeatNanos != 0L && !defeatScreenVisible();
    }

    /** True once the original's defeat screen is up. */
    private boolean defeatScreenVisible() {
        return defeatNanos != 0L
                && System.nanoTime() - defeatNanos >= DEFEAT_PAN_NANOS + DEFEAT_CHEW_NANOS;
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
        long elapsed = now - rewardRiseNanos;
        if (elapsed >= REWARD_RISE_NANOS + REWARD_GROW_NANOS) {
            // Three beats, in order: the packet arrives and is presented (the rise and the
            // grow), the light climbs out of it, and only then is the award page opened -
            // at the moment the white has finished fading, so there is no frame where the
            // board is visible again between the two screens.
            if (rewardLightNanos == 0L) {
                startRewardLight();
            } else if (now - rewardLightNanos >= REWARD_LIGHT_SPREAD_NANOS
                    + REWARD_LIGHT_HOLD_NANOS + REWARD_HANDOFF_NANOS) {
                rewardHandedOff = true;
                client.openAwardScreen(reward);
            }
        }
    }

    /**
     * Claims the reward: the victory music, the mower payout and the rise, at once.
     *
     * <p>The light starts here too, from the rectangle the reward is drawn in right now, so
     * the spread is anchored to something the player was looking at.
     */
    private void claimReward() {
        if (rewardRiseNanos != 0L) {
            return;
        }
        rewardRiseNanos = System.nanoTime();
        float[] rect = rewardRect();
        rewardLightX = rect[0] + rect[2] / 2F;
        rewardLightY = rect[1] + rect[3] / 2F;
        rewardLightNanos = 0L;
        payForParkedMowers();
        if (client.music() != null) {
            client.music().playWinLose(true);
        }
    }

    /** Starts the spread once the reward has finished travelling. */
    private void startRewardLight() {
        if (rewardLightNanos != 0L) {
            return;
        }
        rewardLightNanos = System.nanoTime();
        rewardLightX = client.guiWidth() / 2F;
        rewardLightY = client.guiHeight() / 2F;
    }

    /**
     * 0..1 opacity of the reward's white light, from the wall clock.
     *
     * <p>Zero until the packet has been presented, ramps while the light spreads, holds
     * while the board is gone, then fades over the handoff beat - and the award page is
     * opened the instant that fade reaches zero, so the two screens meet without a frame of
     * lawn between them. Derived from the wall clock rather than accumulated, so a dropped
     * frame cannot stretch the sequence.
     */
    public float rewardLightAlpha() {
        if (rewardLightNanos == 0L) {
            return 0F;
        }
        long elapsed = System.nanoTime() - rewardLightNanos;
        if (elapsed <= REWARD_LIGHT_SPREAD_NANOS) {
            return MathUtil.easeInOut(MathUtil.clamp01(elapsed / (float) REWARD_LIGHT_SPREAD_NANOS));
        }
        long afterSpread = elapsed - REWARD_LIGHT_SPREAD_NANOS;
        if (afterSpread <= REWARD_LIGHT_HOLD_NANOS) {
            return 1F;
        }
        // Fades over the handoff beat, so the page is revealed through the light rather
        // than after it. The page is opened when this reaches zero.
        return MathUtil.clamp01(1F - (afterSpread - REWARD_LIGHT_HOLD_NANOS)
                / (float) REWARD_HANDOFF_NANOS);
    }

    /**
     * The light's radius in GUI pixels: the on-screen distance the white has reached.
     *
     * <p>The spread is sized to the window's <em>diagonal from the reward's own corner</em>,
     * so by the end it has covered every edge whatever the aspect ratio - a radius picked
     * from the height alone would leave the far corners of a wide window uncovered.
     */
    public float rewardLightRadius() {
        if (rewardLightNanos == 0L) {
            return 0F;
        }
        float farthest = 0F;
        float[] corners = {0F, 0F, client.guiWidth(), 0F, 0F, client.guiHeight(),
                client.guiWidth(), client.guiHeight()};
        for (int i = 0; i < corners.length; i += 2) {
            farthest = Math.max(farthest, (float) Math.hypot(
                    corners[i] - rewardLightX, corners[i + 1] - rewardLightY));
        }
        long elapsed = System.nanoTime() - rewardLightNanos;
        float progress = MathUtil.easeInOut(MathUtil.clamp01(elapsed / (float) REWARD_LIGHT_SPREAD_NANOS));
        return farthest * progress;
    }

    /**
     * Turns the lawn mowers the level never used into gold coins.
     *
     * <p>A mower that is still parked is a row the zombies never got through, and the
     * payout already paid for it ({@code LevelRewardS2C.mowers}/{@code mowerCoins}). This
     * is the visible half: one coin leaves each parked mower and flies to the coin bank,
     * which is why the count comes from the packet rather than from the client's own
     * mirror - what the player watches must be exactly what the wallet was given.
     *
     * <p>A mirror that knows fewer mowers than the packet does (a mower that started
     * rolling on the level's last tick, say) still shows every coin: the extras leave from
     * the left edge of the lawn, where a mower would have been standing anyway.
     */
    private void payForParkedMowers() {
        // Read-and-consume in one call: the coins need the rows' positions, and the mowers
        // themselves have to stop being drawn at the same instant, because "the mower became
        // this coin" only reads if there is no longer a mower standing under it.
        java.util.List<com.pvzce.common.level.mechanic.MowerMechanic.Row> parked =
                com.pvzce.client.mechanic.ClientMechanics.consumeParkedMowers(client.level());
        if (reward != null && reward.mowers() > 0 && reward.mowerCoins() > 0) {
            claimedMowerCoins = reward.mowerCoins();
            float fallbackY = Math.max(0F, (client.level().height() - 1) / 2F);
            for (int i = 0; i < reward.mowers(); i++) {
                var row = i < parked.size() ? parked.get(i) : null;
                float x = row == null ? -0.4F : row.x();
                float y = row == null ? fallbackY : row.row() + 0.5F;
                client.level().addCollectAnimation(new ResourceCollectAnimation(
                        -(i + 1), com.pvzce.common.PvzceIds.COIN_GOLD.toString(), 0, COIN_ICON,
                        x, y, 0.3F));
            }
        }
        payForRewardBag();
    }

    /**
     * Pays out the reward's own share: coins out of the money bag, or the object itself.
     *
     * <p>This is what a win pays when it has no card to unlock - a first clear's bounty, a
     * repeat clear's stipend, the flat bonus - and the packet's {@code bonusCoins} is that
     * number. In the original the bag bursts where it lies and the coins arc into the bank;
     * the award page that follows is a summary, not a second place to collect the same money
     * from. A level that paid in objects sends {@code rewardItem} instead, and then the
     * object flies: the wallet was credited what it is worth, and paying that out as ten
     * silver coins would show an exchange the player never asked for.
     *
     * <p>The count is derived from the value rather than fixed, so the player watches the
     * amount they were actually credited - one coin per hundred, at least one, capped so a
     * five-thousand-coin clear does not become a swarm that outlasts the celebration. The
     * per-coin worth is that division, not a constant, so the coins add up to the share.
     */
    private void payForRewardBag() {
        if (reward == null || reward.hasUnlock() || reward.bonusCoins() <= 0) {
            return;
        }
        // What is left of the bonus once the mowers have been paid for themselves. The two
        // are one wallet deposit, but they are two gestures: each mower turns into its own
        // coin where it stands, and the bag (or the item) is the level's completion payout.
        // Paying the bag the whole bonus as well counted the mowers twice - once here and
        // once at their own positions - so the receipt read higher than the wallet moved.
        int share = rewardOnlyCoins();
        if (share <= 0) {
            return;
        }
        claimedRewardCoins = share;
        // From the bag's own rectangle, which is also where it is drawn and where the player
        // just clicked - the same rule the mowers follow, for the same reason.
        float[] rect = rewardRect();
        PvzceCamera camera = client.camera();
        float guiScale = Math.max(1, client.guiScale());
        float worldX = camera.worldX(rect[0] + rect[2] / 2F, rect[1] + rect[3] / 2F);
        float worldY = camera.worldY(rect[0] + rect[2] / 2F, rect[1] + rect[3] / 2F);
        if (reward.hasRewardItem()) {
            // The object itself flies into the bank. It is worth exactly what the wallet was
            // credited for it, so the diamond arrives as one diamond rather than as ten
            // silver coins - the exchange the player never asked for.
            client.level().addCollectAnimation(new ResourceCollectAnimation(
                    REWARD_BAG_FIRST_ID, reward.rewardItem(), share, rewardItemIcon(),
                    worldX, worldY, 0.3F));
            return;
        }
        int coins = Math.max(1, Math.min(REWARD_BAG_MAX_COINS, share / 100));
        int worth = Math.max(1, share / coins);
        for (int i = 0; i < coins; i++) {
            client.level().addCollectAnimation(new ResourceCollectAnimation(
                    REWARD_BAG_FIRST_ID - i, claimResourceId(), worth, COIN_ICON,
                    worldX + (i % 3 - 1) * 0.08F, worldY, 0.3F));
        }
    }

    /**
     * The reward's own coins: the bonus minus the share the parked mowers were paid.
     *
     * <p>{@code bonusCoins} is one deposit that already includes the mower payout (see
     * {@code PvzceServer.awardProfile}), so this is the part the bag stands for. Never
     * negative: a level whose whole bonus was its mowers pays nothing out of the bag.
     */
    private int rewardOnlyCoins() {
        return Math.max(0, reward.bonusCoins() - reward.mowerCoins());
    }

    /**
     * The sprite a reward item is drawn with: the resource's own icon.
     *
     * <p>Falls back to the shared missing-texture tile through {@code EntityArt} when the
     * resource is unknown, so a client that is missing the pack draws the same magenta tile
     * it draws for every other unresolved reference instead of nothing at all - the wallet
     * was credited either way.
     */
    /**
     * A buff's own sprite, or the shared missing-texture stand-in.
     *
     * <p>Asked of the buff registry rather than carried in the packet: the icon belongs to the
     * buff, and the two sides load the same definition.
     */
    private Identifier buffIcon(String buffId) {
        Identifier id = Identifier.tryParse(buffId);
        com.pvzce.api.content.LevelBuff buff =
                id == null ? null : com.pvzce.common.buff.LevelBuffs.get(id);
        return buff == null || buff.icon().isEmpty() ? null : buff.icon().texture();
    }

    private Identifier rewardItemIcon() {
        Identifier id = Identifier.tryParse(reward.rewardItem());
        com.pvzce.api.content.ResourceDef def = id == null
                ? null : com.pvzce.common.core.BuiltInRegistries.RESOURCES.get(id);
        if (def != null) {
            return def.icon();
        }
        return com.pvzce.common.core.EntityArt.sprite(id);
    }

    /**
     * Which resource the reward's coins are counted as.
     *
     * <p>The level's own denomination, so a level that pays in silver counts silver - the
     * bank and the tally are the same number the wallet moved.
     */
    private String claimResourceId() {
        com.pvzce.api.util.Identifier levelId = com.pvzce.api.util.Identifier.tryParse(
                client.level().levelId() == null ? "" : client.level().levelId());
        com.pvzce.api.content.LevelDef def = levelId == null
                ? null : com.pvzce.common.core.BuiltInRegistries.LEVELS.get(levelId);
        if (def == null || def.rewards() == null || def.rewards().coinDrop() == null) {
            return com.pvzce.common.PvzceIds.COIN_GOLD.toString();
        }
        return def.rewards().coinDrop().toString();
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

    /**
     * Draws the reward, the light it gives off, and - while it waits - the arrow over it.
     *
     * <p>Three things are on this layer and they are one sequence:
     *
     * <ol>
     *   <li>unclaimed: the reward flashes and a downward arrow bobs above it. The original
     *       does not write "click to collect" over the lawn; it points at the thing and lets
     *       the flashing say the rest, which reads at a glance and in any language;</li>
     *   <li>claimed: the arrow goes, the reward travels to the middle as before;</li>
     *   <li>arrived: the white light spreads out of it until the board is gone.</li>
     * </ol>
     */
    private void renderRewardDrop() {
        if (!hasRewardDrop()) {
            return;
        }
        float[] rect = rewardRect();
        // Flashing is a brightness modulation rather than an alpha one: fading the packet
        // out would show the lawn through it, and the original's packet stays solid.
        float brightness = rewardRiseNanos == 0L ? rewardFlash() : 1F;
        if (reward().hasUnlock()) {
            SlotResolver.ResolvedCard card = SlotResolver
                    .resolve(Identifier.tryParse(reward().unlockedCard())).orElse(null);
            SeedCardRenderer.draw(client, SeedCardRenderer.CardModel.of(
                            card == null ? null : card.icon().orElse(null),
                            SeedCardRenderer.CardKind.PLANT,
                            card == null ? 0 : card.costSun())
                            .withBrightness(brightness),
                    rect[0], rect[1], rect[2], rect[3]);
        } else if (reward().hasUnlockedBuff()) {
            // A buff is not a card and not an object: it is drawn in the seed packet's own
            // chrome - the same frame the game uses for a card - because that is what "the
            // level handed you something new" looks like on this lawn. Its icon is the buff's,
            // and there is no price on it, which is what the BUFF kind means to the painter.
            SeedCardRenderer.draw(client, new SeedCardRenderer.CardModel(
                            buffIcon(reward().unlockedBuff()),
                            SeedCardRenderer.CardKind.BUFF, SlotInfo.NO_PRICE,
                            brightness, 1F, true, 0F, false, null, false),
                    rect[0], rect[1], rect[2], rect[3]);
        } else if (reward().hasRewardItem()) {
            // A resource is an object, not a packet: drawn square inside the slot the drop is
            // laid out for, so the diamond keeps its shape on a card-shaped rectangle.
            float size = rect[3];
            client.drawTexture(rewardItemIcon(), rect[0] + (rect[2] - size) / 2F, rect[1], size, size,
                    0.55F, brightness, brightness, brightness, 1F);
        } else {
            client.drawTexture(REWARD_BAG, rect[0], rect[1], rect[2], rect[3], 0.55F,
                    brightness, brightness, brightness, 1F);
        }
        if (rewardRiseNanos == 0L) {
            renderRewardArrow(rect);
        }
        renderRewardLight();
    }

    /**
     * The reward's wait-for-me flash: a brightness that dips and returns.
     *
     * <p>Started from the drop's own timestamp rather than from the click, so the phase is
     * the same every run - the flash is a signal, and a signal that starts mid-cycle reads
     * as a flicker.
     */
    private float rewardFlash() {
        double phase = (System.nanoTime() - rewardDropNanos)
                % (double) REWARD_FLASH_PERIOD_NANOS / (double) REWARD_FLASH_PERIOD_NANOS;
        // A raised cosine: dwells at both ends, so the packet spends most of the cycle
        // readable rather than mid-fade.
        float wave = (float) (0.5D - 0.5D * Math.cos(phase * Math.PI * 2D));
        return REWARD_FLASH_MIN_BRIGHTNESS
                + (1F - REWARD_FLASH_MIN_BRIGHTNESS) * wave;
    }

    /**
     * A downward arrow bobbing above the reward, drawn with the original's own sprite.
     *
     * <p>The rip has the exact emitter for this - {@code AwardPickupArrow.xml}, whose second
     * emitter is {@code IMAGE_DOWNARROW} moved by a {@code Position} field of
     * {@code 0 EaseInOutWeak 10 EaseInOutWeak 0}: down ten frames and back, on a weak ease.
     * That is why this is a sprite and not a shape: the arrow is the original's, the bob is
     * the original's curve, and the only thing left is to place it.
     *
     * <p>In GUI pixels rather than cells, because it is the game pointing at something -
     * the same reason the wave warning does not scale with the board. Its size comes from
     * the art at the original's own 80-pixels-per-cell scale.
     */
    private void renderRewardArrow(float[] rect) {
        if (!client.hasTexture(REWARD_ARROW)) {
            return;
        }
        double phase = (System.nanoTime() - rewardDropNanos)
                % (double) REWARD_ARROW_PERIOD_NANOS / (double) REWARD_ARROW_PERIOD_NANOS;
        // The original's curve is symmetric, so one sine half-period reads the same.
        float bob = (float) Math.sin(phase * Math.PI) * REWARD_ARROW_BOB_PIXELS;
        float width = REWARD_ARROW_WIDTH_CELLS * arrowUnit();
        float height = width * REWARD_ARROW_ART_HEIGHT / REWARD_ARROW_ART_WIDTH;
        float centerX = rect[0] + rect[2] / 2F;
        // Above the reward, and never over it: the point of the arrow is where the eye
        // should land, so the tip sits just clear of the sprite's top edge.
        float bottom = rect[1] + rect[3] + REWARD_ARROW_GAP_PIXELS + bob;
        client.drawTexture(REWARD_ARROW, centerX - width / 2F, bottom, width, height,
                0.6F, 1F, 1F, 1F, 1F);
    }

    /**
     * One world cell in GUI pixels, for sizing a HUD sprite that has to match the board.
     *
     * <p>The same conversion {@code rewardRect} uses; kept in one place so the arrow cannot
     * end up a different size from the thing it points at.
     */
    private float arrowUnit() {
        return Math.max(18F, client.camera().unitY() / Math.max(1, client.guiScale()));
    }

    /**
     * The white light the claimed reward gives off, until it has covered the window.
     *
     * <p>Drawn as a square rather than a disc: the GUI has no circle primitive and no
     * additive shader, and at the speed this spreads the corners of a growing square read
     * as light arriving from that direction. The square's half-width is the radius, so by
     * the end its corners reach past the window's diagonal and nothing is left uncovered.
     */
    private void renderRewardLight() {
        float alpha = rewardLightAlpha();
        if (alpha <= 0F) {
            return;
        }
        float radius = Math.max(1F, rewardLightRadius());
        client.drawSolid(rewardLightX - radius, rewardLightY - radius,
                radius * 2F, radius * 2F, 0.85F, 1F, 1F, 1F, alpha);
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
            // A hint belongs to the fight. Once it is over the bottom of the screen belongs
            // to the reward (and, on a loss, to the defeat sequence), so the box goes down
            // with the level rather than lingering under the payout.
            hints.clear();
            // Same for a mower hold in progress: the level is over, and the packet it would
            // have sent must not arrive after the payout counted the mower as surviving.
            cancelMowerHold();
        }
        tickReward();
        tickDefeat();
        tickSleepZzz();
        tickTimedDialogue();
        // The bar ticks here rather than in render(): the click that picks a card is
        // dispatched before this frame's render, and a belt card has to be hit where the
        // player last saw it.
        cardBar().tick();
        if (speedButton != null) {
            speedButton.setLabel(speedLabel());
        }
        EffectEventS2C effect;
        while ((effect = client.level().effects().poll()) != null) {
            if (Boolean.getBoolean("pvzce.traceEffects")) {
                LOGGER.info("effect trace: particle='{}' sound='{}' x={} y={}",
                        effect.particle(), effect.sound(), effect.x(), effect.y());
            }
            if (!effect.particle().isEmpty()) {
                client.particles().spawn(effect.particle(), effect.x(), effect.y());
            }
            if (!effect.sound().isEmpty() && client.sound() != null) {
                playEffectSound(effect);
            }
            if (com.pvzce.common.PvzceSounds.EFFECT_AWOOGA.toString().equals(effect.sound())) {
                // The original's cue for the last wave *arriving*: the banner and that
                // sound are one beat, which is why this is driven by the sound rather
                // than by the warning flag the huge-wave text uses.
                finalWaveNanos = System.nanoTime();
            }
            if (effect.hasRipple()
                    && com.pvzce.client.renderer.liquid.LiquidTextures
                            .liquidFor(effect.ripple()).isPresent()) {
                // The server names the LIQUID, not the renderer, so a modded liquid
                // gets ripples exactly like water does. An id this client cannot
                // resolve is dropped rather than treated as water: the surface it
                // meant is not on screen anyway, and drawing one would put a ring in
                // the middle of a lawn.
                //
                // Through the stage's liquid frame, because a ripple is placed in the
                // surface's space: the pool draws its water two thirds of a lane below
                // its water rows, and a ring left on the cell grid would float above the
                // splash that made it.
                com.pvzce.client.renderer.LevelStage.LiquidFrame frame =
                        client.camera().liquidFrame();
                client.liquidRipples().add(frame.x(effect.x()), frame.y(effect.y()),
                        effect.rippleStrength());
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
        tickMowerHold();
        tickWaveWarning();
        applyMutationState();
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
        // Under the HUD and over the board: the wash belongs to the level, and the panel and the
        // banner are messages about it.
        mutationHud.renderDarkness(client);
        echoLilyLinks.renderHud(client);
        renderHud();
        com.pvzce.client.mechanic.ClientMechanics.renderHud(client);
        if (rhythm != null) {
            // The chart's clock is read here rather than in `tick()`: the first note may be pressed
            // on any frame, and the render loop is the one that runs on all of them.
            rhythm.tick(client.level().smoothLevelTicks(), rhythmStatus());
        }
        renderRhythmHud();
        renderRhythmBanner();
        renderEntryBanner();
        renderFinalWaveBanner();
        mutationHud.renderBanner(client);
        mutationHud.renderPanel(client);
        renderCardBar();
        renderMowerHold();
        renderCollectAnimations();
        renderDraggedCard();
        renderEndOverlay();
        renderRewardDrop();
        if (!defeatSequenceRunning()) {
            // Not over the defeat sequence: that is the game explaining what just happened,
            // and a lesson from thirty seconds ago on top of it would be noise. The box is
            // cleared outright on a loss for the same reason.
            hints.render();
        }
        // The lane keys, the judgement line and the notes in the air are the last thing over the
        // board, and deliberately so. Over the dragged card's placement preview, because a note
        // arriving on the beat must not wash out under the ghost of whatever the player was about
        // to plant - and over the hint box, because the mode's own bottom edge is where the hint
        // box is: while a lesson is up, the keys are still the thing being played.
        renderRhythmHighway(client.camera(), Math.max(1, client.guiScale()));
        if (client.level().gameState().equals("running")) {
            boolean dialogueActive = dialogue != null && dialogue.isActive();
            for (var widget : widgets) {
                if (dialogueActive && widget != dialogue) {
                    // The card bar and the pause button are still there when the
                    // conversation ends; drawing them under it would offer the player
                    // controls the modal is already swallowing.
                    continue;
                }
                widget.render(client);
            }
        }
        renderDefaultToolCursor();
    }

    /**
     * The "Zzz" a sleeping plant breathes out.
     *
     * <p>Client-side, and deliberately so: the client already knows everything this needs -
     * which plants are asleep is the state the server published ({@code sleep}), and which
     * plants *can* sleep is in the animation file it loaded. Sleeping is a continuous fact
     * rather than an event, so emitting it from the server would mean a packet every time a
     * mushroom breathes; here it is a wall-clock timer and no packets at all.
     */
    private void tickSleepZzz() {
        if (!client.level().gameState().equals("running")) {
            return;
        }
        long now = System.nanoTime();
        // Both maps are keyed by entity id, and sleeping plants come and go (planted, dug up,
        // eaten), so the ids seen this frame are what survives the sweep at the bottom.
        java.util.Set<Integer> breathing = new java.util.HashSet<>();
        for (ClientEntity entity : client.level().entities().values()) {
            if (!com.pvzce.api.entity.EntityKind.PLANT.equals(entity.kind())
                    || !com.pvzce.api.entity.EntityAnimations.SLEEP.equals(entity.animation())) {
                continue;
            }
            breathing.add(entity.id());
            // The first breath is staggered by entity id so a row of sleeping mushrooms does
            // not puff in lockstep: they are asleep, not a chorus line. Every later breath is
            // ZZZ_INTERVAL_NANOS after the previous due time, so the offsets never converge.
            long due = zzzNextNanos.computeIfAbsent(entity.id(),
                    id -> now + (id % 4) * ZZZ_STAGGER_NANOS);
            if (now < due) {
                continue;
            }
            // A pause or a level reload can leave the timer far behind; resynchronise instead
            // of letting the plant puff once a frame until it has caught up.
            long next = due + ZZZ_INTERVAL_NANOS;
            zzzNextNanos.put(entity.id(), next < now ? now + ZZZ_INTERVAL_NANOS : next);
            // The three sizes in order, so one plant's breath is a spiral: a small z, then a
            // bigger one further up, then the biggest, and back to the start.
            int step = zzzStep.getOrDefault(entity.id(), 0);
            zzzStep.put(entity.id(), (step + 1) % com.pvzce.common.PvzceParticles.SLEEP_ZZZ.size());
            Identifier zzz = com.pvzce.common.PvzceParticles.SLEEP_ZZZ.get(step);
            // Above the plant and a little to its left, which is where a mushroom's breath
            // would be: the sprite itself then leans further left as it rises (see the
            // particle's own angle).
            client.particles().spawn(zzz.toString(),
                    entity.cellX() - 0.18F, entity.cellY() + ZZZ_HEIGHT);
        }
        zzzNextNanos.keySet().retainAll(breathing);
        zzzStep.keySet().retainAll(breathing);
    }

    /** How often a sleeping plant breathes one Zzz, in nanoseconds. */
    private static final long ZZZ_INTERVAL_NANOS = 620_000_000L;
    /** How far apart two plants' first puffs are, spread over the interval. */
    private static final long ZZZ_STAGGER_NANOS = 150_000_000L;
    /** How high above its cell a plant's Zzz starts. */
    private static final float ZZZ_HEIGHT = 0.55F;

    /**
     * The mallet under the pointer, in a level that makes a tool its click.
     *
     * <p>Whack-a-Zombie's cursor <em>is</em> the mallet in the original, and the gesture it
     * describes is not "click this cell" but "hit this zombie" - so what the cursor has to say is
     * what the blow is, and it says it by swinging. Drawn last, in GUI space, so it is over the
     * HUD the way the system pointer is: a cursor that the card bar can cover is not a cursor.
     *
     * <p>Drawn as the tool's own animation rather than as its card sprite: the two clips the
     * animation file carries are the held pose and the swing ({@link #swingDefaultToolCursor}),
     * and a still picture cannot tell the player that a click landed. The box is square and the
     * projection inside it is square, so the mallet keeps its shape; the offset up and to the
     * right of the pointer is unchanged, so the head still sits where the system arrow's tip was.
     *
     * <p>Nothing is drawn while a card is selected, and nothing is drawn for a level with no
     * default tool: this is the tool's own advertisement, not a new HUD element.
     */
    private void renderDefaultToolCursor() {
        if (!client.level().gameState().equals("running")) {
            return;
        }
        Identifier toolId = cursorToolId();
        if (toolId == null) {
            return;
        }
        com.pvzce.client.animation.AnimationManager animations = client.animations();
        if (animations == null) {
            return;
        }
        com.pvzce.client.animation.ArtTarget cursor = toolCursor(animations, toolId);
        if (cursor == null) {
            return;
        }
        // The held pose unless a swing is on screen. Asking every frame is what makes the
        // transition back from `attack` automatic (`on_end: idle` is the clip's own answer), and
        // asking only when the swing is over is what keeps this from cancelling it: the manager
        // reads a request for a different state as "replace what is playing".
        com.pvzce.client.animation.AnimationPlayback playback = animations.playback(cursor);
        if (playback == null || !ATTACK_CLIP.equals(playback.activeName())) {
            cursor.play(com.pvzce.api.entity.EntityAnimations.IDLE);
            playback = animations.playback(cursor);
        }
        if (playback == null) {
            return;
        }
        float size = Math.max(24F, client.guiHeight() * 0.055F);
        float box = size * CURSOR_BOX_SCALE;
        float half = box / 2F;
        // The centre of the box the card sprite used to occupy, so the mallet does not move.
        float centerX = (float) client.guiMouseX(client.window().cursorX()) + size * 0.65F;
        float centerY = (float) client.guiMouseY(client.window().cursorY()) - size * 0.4F;
        // An overlay world view rather than the GUI space the other HUD pieces are drawn in:
        // the animation renderer works in world units, and this is the one projection that maps
        // a world rectangle onto a GUI rectangle. The tint is whatever the GUI pass left in the
        // shader - neutral - so the mallet is not lit by the lawn's night.
        client.beginOverlayWorldView(centerX - half, centerY - half, box, box,
                -CURSOR_HALF_CELLS, CURSOR_HALF_CELLS, -CURSOR_HALF_CELLS, CURSOR_HALF_CELLS);
        try {
            // Anchored at the *bottom* of that box, because a converted model's origin is the
            // ground line it was fitted on: y = 0 is the bottom of the model and the art grows
            // upward from it. Anchoring at the centre - which is what a cursor "at the pointer"
            // suggests - draws the mallet half a box too high and the viewport clips its head.
            playback.render(client, 0F, -CURSOR_HALF_CELLS, 0F, 1F, 1F);
        } finally {
            client.beginGuiView();
        }
    }

    /**
     * Swings the mallet under the pointer, for the click that asked the server for a blow.
     *
     * <p>Played on the click rather than on the server's answer: that answer is one round trip
     * away, and a cursor that waits for it feels broken every time the player swings at a zombie
     * that has already died. A swing at an empty cell therefore still animates - which is also
     * what the original does, and the reason the bonk is the server's business while the gesture
     * is the client's.
     */
    private void swingDefaultToolCursor() {
        com.pvzce.client.animation.AnimationManager animations = client.animations();
        if (animations == null) {
            return;
        }
        Identifier toolId = cursorToolId();
        if (toolId == null) {
            return;
        }
        com.pvzce.client.animation.ArtTarget cursor = toolCursor(animations, toolId);
        if (cursor != null) {
            cursor.play(ATTACK_CLIP);
        }
    }

    /**
     * Which tool is under the pointer: the card in hand, or the level's own click.
     *
     * <p>Two ways to be holding a tool, and the cursor is the same answer for both. A level
     * that grants one (Whack-a-Zombie's mallet) has it in hand with no card selected; every
     * other tool is a card the player picked up, which is how the original's shovel and
     * watering can follow the pointer too - the card is not "a thing that will be used
     * somewhere", it is what the player is holding.
     *
     * <p>Null for a plant card and for a tool card whose art this build has not got: a
     * missing animation must leave the ordinary pointer alone rather than draw nothing over
     * it.
     */
    private Identifier cursorToolId() {
        if (selectedCard >= 0) {
            SlotInfo selected = slotInfo(selectedCard);
            if (selected == null || !"tool".equals(selected.kind())) {
                return null;
            }
            return Identifier.tryParse(selected.defId());
        }
        com.pvzce.api.content.ToolData granted =
                com.pvzce.client.mechanic.ClientMechanics.defaultTool(client.level());
        return granted == null ? null : granted.tool();
    }

    /**
     * The playback target for the default tool's cursor, built on first use.
     *
     * <p>An {@link com.pvzce.client.animation.ArtTarget} rather than a {@code ClientEntity}: the
     * mallet is not on the board, it has no health, no team and no cell, and the animation
     * manager's entity path would ask the level for all three. A target that names its own file
     * is the same route the lawn mower takes.
     *
     * <p>Rebuilt when the level hands over a different tool: the file is part of the target, and
     * a level that swaps its mallet for something else must not keep drawing the old one.
     */
    private com.pvzce.client.animation.ArtTarget toolCursor(
            com.pvzce.client.animation.AnimationManager animations, Identifier toolId) {
        if (toolCursor != null && toolId.equals(toolCursorTool)) {
            return toolCursor;
        }
        Identifier file = com.pvzce.common.core.EntityArt.animationFile(toolId);
        if (file == null) {
            return null;
        }
        com.pvzce.client.animation.ArtTarget created = new com.pvzce.client.animation.ArtTarget(file);
        created.attach(animations);
        toolCursor = created;
        toolCursorTool = toolId;
        return created;
    }

    /** Drops the cursor's playback; the animation manager outlives this screen. */
    private void releaseToolCursor() {
        if (toolCursor != null) {
            toolCursor.stopAnimation();
            toolCursor = null;
            toolCursorTool = null;
        }
    }

    private void renderWorld() {
        // What is out of place on the board right now, before anything reads it: the scene
        // renderer asks per cell, and half the answer is a clock.
        client.level().syncSceneShifts();
        PvzceCamera base = client.camera();
        // The only time this differs is the defeat move, which looks toward the house.
        PvzceCamera camera = base.panned(defeatPan);
        client.beginWorldView(camera);
        // The original PvZ backing image is the stage: house on the left, a
        // 9x5 bare-dirt lawn in the middle, road on the right. The board is
        // projected into the lawn region by PvzceCamera.
        //
        // Drawn at the UNPANNED rectangle: the backdrop is a fixed thing in the world, so
        // a camera that turns must slide it across the screen. Drawing it at the panned
        // rectangle - which is what "the viewport, in world coordinates" means - would pin
        // it to the screen and slide the lawn out from under it instead.
        client.drawTexture(backdropTexture(),
                base.worldLeft(), base.worldBottom(),
                base.worldWidth(), base.worldHeight(),
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
                    // The ring of grass outside the board exists to keep the edge tiles from
                    // ending in a hard line. A level that hides its lawn has a backdrop under
                    // it that continues on its own, so the ring would be a green frame around
                    // a lawn the level deliberately does not draw.
                    client.level().hidesSceneElement(PvzceIds.GRASS.toString())
                            ? 0F : GRASS_VISUAL_MARGIN_CELLS,
                    // A tombstone raised mid-level pushes up through the lawn instead of
                    // appearing on it, and one being eaten sinks from the top down; see
                    // SceneShifts.
                    client.level().sceneShifts()::at,
                    // The level's own backdrop may already contain some of the terrain, so the
                    // elements it hides are not painted - see SceneVisibility.
                    client.level().sceneVisibility(),
                    // Where this stage's water surface goes; see LevelStage.POOL.
                    camera.liquidFrame());
        } finally {
            client.clipping().pop();
        }

        for (com.pvzce.client.mechanic.ClientMechanic.WorldOverlay overlay : worldOverlays()) {
            overlay.render(client, camera);
        }

        echoLilyLinks.renderWorld(client);

        // After the lawn, not before it: a riser is drawn at its standing position and cut
        // off at its row's ground line (see renderRisingZombies), so nothing of it may end up
        // behind the grass - and the part that is out has to be in front of it, like every
        // other zombie.
        renderRisingZombies(camera);

        renderContainerHover(camera);

        renderAimReticle();

        if (selectedCard >= 0) {
            int hoverX = camera.cellX(client.window().cursorX(), client.window().cursorY());
            int hoverY = camera.cellY(client.window().cursorX(), client.window().cursorY());
            if (camera.inBoard(client.window().cursorX(), client.window().cursorY())
                    && hoverX >= 0 && hoverX < client.level().width()
                    && hoverY >= 0 && hoverY < client.level().height()) {
                // Green inside the plantable area, red outside it. The server is still the
                // one that decides (terrain and stacking are not visible here), but a
                // mini-game's red line is: a level that only accepts the left half must not
                // look like it accepts a click it is going to refuse.
                boolean allowed = client.level().inPlacementZone(hoverX, hoverY);
                if (PvzceIds.ECHO_LILY.toString().equals(selectedCardId())
                        || PvzceIds.RESONANCE_MOSS.toString().equals(selectedCardId())) {
                    echoLilyLinks.renderPreview(client, hoverX, hoverY);
                }
                // On a level whose cards plant a whole column, the tint covers the column - which
                // is the one thing the player has to know before clicking, and the reason the rule
                // travels to the client at all. The hovered cell is drawn again on top, brighter,
                // because the ghost below is still only on that cell.
                if (client.level().plantsWholeColumn()) {
                    for (int row = 0; row < client.level().height(); row++) {
                        boolean rowAllowed = client.level().inPlacementZone(hoverX, row);
                        client.drawSolid(hoverX, row + terrainHeight(hoverX, row), 1F, 1F, 0.19F,
                                rowAllowed ? 0.2F : 1F, rowAllowed ? 1F : 0.2F, 0.2F, 0.18F);
                    }
                }
                client.drawSolid(hoverX, hoverY + terrainHeight(hoverX, hoverY), 1F, 1F, 0.2F,
                        allowed ? 0.2F : 1F, allowed ? 1F : 0.2F, 0.2F, 0.25F);
            }
        }

        // Stable back-to-front order, and it is a *total* order so nothing flickers:
        // back rows first (y is depth on this board), then plants before zombies so an
        // eating zombie covers the plant, then spawn id. A cell's plants share one render
        // layer and the server places a plant above whatever it rests on
        // (PlacementDef.layer + the #c:carrier tags), so the later-planted plant appears on
        // top of its carrier without this loop needing to know what a carrier is.
        List<ClientEntity> renderEntities = new ArrayList<>(client.level().entities().values());
        renderEntities.sort(Comparator.comparingLong(InGameScreen::renderOrder));
        for (ClientEntity entity : renderEntities) {
            // Risers were already drawn, before the lawn. Drawing them again here would put
            // the buried half back on top of it.
            if (isRising(entity)) {
                continue;
            }
            if (com.pvzce.client.mechanic.FogClientMechanic.hides(
                    client.level(), entity.cellX(), entity.cellY())) {
                // Inside the part of the fog that hides what is in it. Not drawn at all rather
                // than drawn dimmed: "a zombie is only drawn once it walks into view" is the
                // whole behaviour, and a half-visible zombie is one the player will argue about.
                continue;
            }
            if (isSomebodyElsesPickup(entity)) {
                // A resource drop the player cannot pick up. On a versus level the plant side's sun
                // lands on the lawn whether or not the player is the plant side, and a zombie player
                // has no way to collect it: drawn, it is a lie about what can be clicked. The team
                // check is the collection rule itself (a drop belongs to its own team), so a level
                // where the player is the plant side hides nothing.
                continue;
            }
            if (com.pvzce.client.mechanic.StormClientMechanic.hides(client.level())) {
                // The storm's half of the same rule, and the whole of the original's storm
                // level: between strikes the lawn is black and nothing standing on it is drawn.
                // No position is consulted - a storm has no edge.
                continue;
            }
            renderEntity(entity);
        }
        renderPlacementPreview();
        renderCarriedPlant();
        client.particles().render(client);
        // Last of the board's own layers, so a summoned mallet is drawn over the burst it just
        // caused. It used to be drawn under the particles, and the pot's own POW is a
        // three-quarter-second cloud - the swing is a third of a second, so the gesture the
        // player asked for was entirely hidden behind the hit it made.
        com.pvzce.client.renderer.MagnetItems.renderWorld(client);
        renderMalletSwings();
    }

    /**
     * True for a resource drop that belongs to a team other than the local player's.
     *
     * <p>"Cannot pick it up" is spelled as "its team is not mine", which is the rule the server's
     * collection path enforces. A level with no local team (the editor, a spectator) hides nothing,
     * because there is no player to compare against.
     */
    private boolean isSomebodyElsesPickup(ClientEntity entity) {
        com.pvzce.api.util.Identifier mine = client.level().controlledTeamId();
        return !com.pvzce.client.renderer.PickupVisibility.isVisible(
                mine == null ? null : mine.toString(), entity.kind(), entity.teamId());
    }

    /**
     * The translucent ghost of the plant the player is holding, on the cell under the
     * cursor - the original's "this is where it goes" preview.
     *
     * <p>Shown for a selected <em>plant</em> card whether it was clicked or is being dragged
     * (both gestures leave {@link #selectedCard} set), and only over the board: the preview
     * is about a cell, so there is nothing to show anywhere else. It plays the plant's own
     * idle animation, because a player picking a plant is looking for the plant they know.
     *
     * <p>It says nothing about whether the plant may be placed - that answer belongs to the
     * server ({@code PlantPlacement} needs the terrain and the stack, and the client has
     * neither) - which is why the hover tint underneath still carries the one rule the
     * client does know, the level's plantable area.
     */
    /**
     * The plant a glove is holding, drawn under the cursor.
     *
     * <p>The plant has been lifted off the board, so without this the player is carrying
     * something invisible and the glove reads as "the click did nothing". Drawn at the cursor
     * rather than on the hovered cell - which is where the card preview below draws - because
     * what the player is holding is not yet anywhere: the cell under the pointer is where it
     * would go, and the two are only the same thing once the cell is chosen.
     */
    private void renderCarriedPlant() {
        syncCarry();
        String carried = client.level().carriedPlant();
        // The glove's carry and a picked-up seed packet are the same picture to the player: one
        // plant, riding the cursor until the next click puts it down. Which of the two it is only
        // decides where the art comes from - a bar card, or the packet's own card id.
        Identifier art = carried.isEmpty()
                ? (client.level().holdingCard()
                        ? com.pvzce.client.gui.hud.cardbar.CardPainter.icon(client.level().heldCard())
                        : null)
                : carriedPlantArt(carried);
        if (art == null || !client.level().gameState().equals("running")) {
            return;
        }
        float size = Math.max(20F, client.guiHeight() * 0.09F);
        float centerX = (float) client.guiMouseX(client.window().cursorX());
        // Lifted clear of the pointer, so the art is beside the arrow rather than under it.
        float centerY = (float) client.guiMouseY(client.window().cursorY()) + size * 0.35F;
        client.beginOverlayWorldView(centerX - size / 2F, centerY - size / 2F, size, size,
                -0.5F, 0.5F, -0.5F, 0.5F);
        try {
            client.drawTexture(art, -0.5F, -0.5F, 1F, 1F, 0.2F, 1F, 1F, 1F, 0.85F);
        } finally {
            client.beginGuiView();
        }
    }

    /**
     * The bar icon of the plant a glove is holding.
     *
     * <p>The card bar's own icon lookup, so the thing in hand is the picture the player clicked: a
     * plant's art is an animation and its definition names no single sprite, so resolving a path
     * from the id answered the missing-texture tile.
     */
    private Identifier carriedPlantArt(String carried) {
        SlotInfo slot = cardGranting(carried);
        return slot == null ? null : com.pvzce.client.gui.hud.cardbar.CardPainter.icon(slot);
    }

    /**
     * Follows the server's carry state, and keeps the card in hand while it lasts.
     *
     * <p>Two things happen on a change and nowhere else. When the plant is lifted the card is
     * <em>re-selected</em>: the player is holding something, the highlighted card is how they
     * can see it, and a click on the lawn is then the second half of the move through exactly
     * the same path as the first. When the carry ends the card is let go with it.
     *
     * <p>Cleared here rather than when the plant is drawn, which is what the first version of
     * this did: the render path runs every frame, including the one right after the lift click
     * and before the server has answered it, so the slot was forgotten before it was ever
     * needed - and the drop needed the glove clicked again.
     */
    private void syncCarry() {
        String carried = client.level().carriedPlant();
        boolean holdingPacket = client.level().holdingCard();
        if (carried.equals(lastCarried) && holdingPacket == lastHoldingPacket) {
            return;
        }
        lastCarried = carried;
        lastHoldingPacket = holdingPacket;
        if (holdingPacket) {
            // A packet in hand takes the hand: a bar card left selected would light up a second
            // plant that is not the one the next click plants.
            selectedCard = -1;
            return;
        }
        if (carried.isEmpty()) {
            // The move finished (dropped, eaten or timed out): let the card go too. This is the
            // path that catches a carry the player did not finish with a click of their own.
            if (isGloveSlot(selectedCard)) {
                selectedCard = -1;
            }
            return;
        }
        // A plant is in hand: keep its card lit. The card is the *glove's*, looked up in the bar
        // rather than remembered from the click that lifted the plant - a remembered slot could
        // only ever be right for a client that started the move, and the drop needs the same
        // answer from a client that did not (a save resumed mid-move, a bar rebuilt by a
        // resize) and from one whose memory had been let go.
        SlotInfo glove = gloveInBar();
        if (glove != null) {
            selectedCard = glove.index();
        }
    }

    /**
     * The bar's glove card, or {@code null} when this level has none.
     *
     * <p>The slot every drop is sent with. Not the carried plant's own card: that one is a
     * <em>plant</em> card, and the server answers a plant card on the tool path with "不是工具卡"
     * - which is exactly what a drop sent with it looked like (the plant stayed in hand, and the
     * player was told to select a tool).
     */
    private SlotInfo gloveInBar() {
        int index = gloveSlotIn(client.level().slots());
        return index < 0 ? null : slotInfo(index);
    }

    /** True when this slot index is the bar's glove card. */
    private boolean isGloveSlot(int slotIndex) {
        SlotInfo card = slotIndex < 0 ? null : slotInfo(slotIndex);
        return card != null && card.kind().equals("tool") && GLOVE_ID.equals(card.defId());
    }

    /**
     * The card that grants this content, for a carry the client did not start itself.
     *
     * <p>Normally the slot is remembered at the click that lifted the plant; this is the way
     * back if that was lost (a save restored mid-move), and it asks the same resolver the bar
     * draws from rather than guessing at a path.
     */
    private SlotInfo cardGranting(String contentId) {
        Identifier wanted = Identifier.tryParse(contentId);
        if (wanted == null) {
            return null;
        }
        for (int index = 0; index < client.level().slots().size(); index++) {
            SlotInfo info = slotInfo(index);
            if (info == null) {
                continue;
            }
            Identifier slotId = Identifier.tryParse(info.defId());
            SlotDef def = slotId == null ? null
                    : com.pvzce.common.core.BuiltInRegistries.SLOT_TYPES.get(slotId);
            Identifier content = def == null ? null : def.content();
            if (wanted.equals(content)) {
                return info;
            }
        }
        return null;
    }

    private void renderPlacementPreview() {
        if (!client.level().gameState().equals("running")) {
            return;
        }
        // The plant in hand is one plant, whichever way it got there: a bar card the player
        // selected, or a packet they picked up. The ghost, the hover tint and "can this cell take
        // it" are the same question for both.
        String defId;
        if (client.level().holdingCard()) {
            defId = heldCardPlantId();
        } else if (selectedCard >= 0) {
            SlotInfo selected = slotInfo(selectedCard);
            if (selected == null
                    || !com.pvzce.api.entity.EntityKind.PLANT.equals(selected.kind())) {
                return;
            }
            defId = selected.defId();
        } else {
            return;
        }
        if (defId.isEmpty()) {
            return;
        }
        PvzceCamera camera = client.camera();
        double cursorX = client.window().cursorX();
        double cursorY = client.window().cursorY();
        if (!camera.inBoard(cursorX, cursorY)) {
            return;
        }
        int cellX = camera.cellX(cursorX, cursorY);
        int cellY = camera.cellY(cursorX, cursorY);
        if (cellX < 0 || cellX >= client.level().width()
                || cellY < 0 || cellY >= client.level().height()) {
            return;
        }
        ClientEntity preview = placementPreview(defId);
        if (preview == null) {
            return;
        }
        // The cursor maps to a cell index, but an entity's position is the CENTRE of its
        // cell: the server spawns a plant at (gridX + 0.5, gridY + 0.5). Writing the index
        // straight in drew the ghost half a cell down and to the left of the cell it was
        // hovering - the preview and the plant it promised were never in the same place.
        //
        preview.setCellX(cellX + 0.5F);
        preview.setCellY(cellY + 0.5F);
        preview.setHeight(terrainHeight(cellX, cellY));
        preview.playAnimation(com.pvzce.api.entity.EntityAnimations.IDLE);
        client.pushEntityAlpha(PLACEMENT_PREVIEW_ALPHA);
        try {
            drawEntityArt(preview);
        } finally {
            client.popEntityAlpha();
        }
    }

    private float terrainHeight(int x, int y) {
        return terrainHeight(x + 0.5F, y);
    }

    private float terrainHeight(float x, int y) {
        Identifier id = Identifier.tryParse(client.level().sceneAt((int) Math.floor(x), y));
        var terrain = id == null ? null : com.pvzce.common.core.BuiltInRegistries.SCENE_ELEMENTS.get(id);
        return terrain == null ? 0F : terrain.heightAt(x, client.level().width());
    }

    /**
     * The preview entity for a card, created on first use and kept for the level.
     *
     * <p>One entity, re-attached when the player picks a different plant: creating one per
     * frame would allocate a playback per frame, and the animation manager keys playbacks by
     * target, so the old one would never be released.
     */
    private ClientEntity placementPreview(String defId) {
        if (defId == null || defId.isEmpty()) {
            return null;
        }
        if (placementPreview != null && defId.equals(placementPreview.defIdString())) {
            return placementPreview;
        }
        releasePlacementPreview();
        if (client.animations() == null) {
            return null;
        }
        ClientEntity preview = new ClientEntity(PLACEMENT_PREVIEW_ID,
                com.pvzce.api.entity.EntityKind.PLANT, defId, 0F, 0F, 1,
                com.pvzce.api.entity.EntityLayers.GROUND,
                com.pvzce.api.entity.EntityAnimations.IDLE, 0F, "pvzce:plant_team");
        preview.attachAnimationManager(client.animations());
        placementPreview = preview;
        return preview;
    }

    /** Drops the preview's playback; the level's animation manager is shared, not owned. */
    private void releasePlacementPreview() {
        if (placementPreview != null) {
            placementPreview.stopAnimation();
            placementPreview = null;
        }
    }

    @Override
    protected void onRemoved() {
        // Playbacks are keyed by target and would otherwise outlive this screen: the
        // animation manager belongs to the level, not to the HUD.
        releasePlacementPreview();
        releaseToolCursor();
    }

    /**
     * Draw order: back rows first, then kind, then spawn id. See
     * {@link com.pvzce.client.renderer.EntityVisuals#renderOrder} for why the row leads.
     */
    private static long renderOrder(ClientEntity entity) {
        return com.pvzce.client.renderer.EntityVisuals.renderOrder(
                entity.kind(), entity.layer(), entity.gridY(), entity.id());
    }

    /** Namespace-preserving sprite id; shared with the seed chooser and editor. */
    private static Identifier entityTexture(ClientEntity entity) {
        return com.pvzce.client.renderer.EntityTextures.forEntity(entity.defId(), entity.kind());
    }

    /**
     * Whether this entity is still on its way up out of the ground.
     *
     * <p>A zombie in that state carries a negative height (see
     * {@code PvzceConstants#ZOMBIE_RISE_DEPTH_CELLS}), which is the whole of what the client
     * is told: the climb is a height animation, so no new field travels for it.
     */
    private static boolean isRising(ClientEntity entity) {
        return entity.kind().equals(com.pvzce.api.entity.EntityKind.ZOMBIE) && entity.height() < 0F
                && entity.layer() != com.pvzce.api.entity.EntityLayers.UNDERGROUND;
    }

    /**
     * How much of a rising zombie is still underground, 1 (buried) to 0 (standing).
     *
     * <p>The client's own reading of the published height, in units of the one shared depth
     * constant - see {@link PvzceConstants#ZOMBIE_RISE_DEPTH_CELLS}. Clamped because the
     * interpolation between two syncs can overshoot either end.
     */
    private static float riseBuried(ClientEntity entity) {
        return MathUtil.clamp01(-entity.visualHeight() / PvzceConstants.ZOMBIE_RISE_DEPTH_CELLS);
    }

    /**
     * How far the row's ground line has to sweep down to reveal a body of this art, in cells.
     *
     * <p>The model is drawn with its feet at {@code anchorLift} below the cell centre, so
     * hiding all of it means starting the cut {@code height + gap} above the ground line -
     * where the gap is what is left between the feet and the tile's bottom edge. Read from
     * the art rather than assumed: a gargantuar rising out of a grave is twice as tall as an
     * ordinary zombie, and a cut placed for the ordinary one would leave its head and
     * shoulders standing in the dirt from the first frame.
     */
    private float riseRevealCells(ClientEntity entity) {
        float[] size = client.animations() == null ? null : client.animations().visualSize(entity);
        float height = size != null && size[1] > 0F
                ? size[1]
                : EntityVisuals.of(com.pvzce.api.entity.EntityKind.ZOMBIE).spriteHeight();
        return height + (0.5F - EntityVisuals.anchorLift(com.pvzce.api.entity.EntityKind.ZOMBIE));
    }

    /**
     * Paints every zombie that is still climbing out of a grave.
     *
     * <p>Each one is drawn at the position it will <em>stand</em> in and cut off at a line
     * that sweeps down from above its head to the row's ground line as the climb proceeds.
     * That is the same picture as sliding the art up out of the dirt - the buried part is the
     * part under the line either way - and it is the version that needs no second copy of an
     * entity's position: the height the server publishes is the climb's clock, and the ground
     * line is where the row's tile ends.
     *
     * <p>The arm comes out first, the way the original stages it: a hand pushes up out of the
     * dirt before the body does, then sinks back under it as the zombie's own arms rise with
     * the rest of it. It is drawn from the same climb clock rather than as a particle so the
     * two cannot drift apart - a paused level freezes both, and the arm is never left waving
     * over a zombie that is already walking.
     *
     * <p>No shadow: a shadow under the grass would be the one part of a buried zombie the
     * player could see, and the sun does not reach down there.
     */
    private void renderRisingZombies(PvzceCamera camera) {
        for (ClientEntity entity : client.level().entities().values()) {
            if (!isRising(entity)) {
                continue;
            }
            entity.playAnimation(entity.animation());
            float climb = 1F - riseBuried(entity);
            float groundY = entity.cellY() - 0.5F;
            // The arm first, on its own cut: a hand's length of zombie is out while the body
            // is still entirely under the lawn.
            drawRiseArm(entity, camera, climb, groundY);
            // Then the body, which does not start until the arm has had its turn.
            float body = MathUtil.clamp01((climb - RISE_ARM_CLIMB) / (1F - RISE_ARM_CLIMB));
            pushGroundClip(camera, groundY + (1F - body) * riseRevealCells(entity));
            try {
                drawEntityArt(entity);
            } finally {
                client.clipping().pop();
            }
        }
    }

    /**
     * The hand a climbing zombie reaches out of its grave with.
     *
     * <p>Drawn at the position it ends in - standing on the ground line - and revealed from
     * the top down by its own cut, exactly like the body. The art is upside down on purpose:
     * {@code zombiearm} is the arm as it hangs off a walking zombie (sleeve up, hand down),
     * and an arm coming out of the dirt reaches the other way.
     */
    private void drawRiseArm(ClientEntity entity, PvzceCamera camera, float climb, float groundY) {
        float out = riseArmReveal(climb);
        if (out <= 0.01F) {
            return;
        }
        float left = entity.cellX() - RISE_ARM_WIDTH_CELLS / 2F;
        float right = left + RISE_ARM_WIDTH_CELLS;
        pushGroundClip(camera, groundY + (1F - out) * RISE_ARM_HEIGHT_CELLS);
        try {
            // The two v coordinates are swapped rather than the quad being rotated: the sprite
            // is a rectangle drawn in world units, so turning it around would also have to
            // undo the board's 80x100 cell aspect. Swapping v draws the art upside down, which
            // is what a hand reaching out of the dirt is: `zombiearm` is the arm as it hangs
            // off a walking zombie, sleeve up and hand down. UVs are in the sprite's own
            // pixels - that is the unit this call takes, unlike drawTextureRegion.
            client.drawTextureQuad(RISE_ARM_TEXTURE,
                    left, groundY, right, groundY, right, groundY + RISE_ARM_HEIGHT_CELLS,
                    left, groundY + RISE_ARM_HEIGHT_CELLS,
                    0F, 0F, RISE_ARM_SPRITE_WIDTH_PX, 0F,
                    RISE_ARM_SPRITE_WIDTH_PX, RISE_ARM_SPRITE_HEIGHT_PX,
                    0F, RISE_ARM_SPRITE_HEIGHT_PX,
                    EntityVisuals.baseZ(com.pvzce.api.entity.EntityKind.ZOMBIE), 1F, 1F, 1F, 1F);
        } finally {
            client.clipping().pop();
        }
    }

    /**
     * How much of the arm is out of the dirt at {@code climb} through the rise.
     *
     * <p>Up quickly, held while the body comes up past it, then pulled back under: an arm that
     * stayed out until the last frame would vanish in one frame, because the climb ending is
     * also the moment this whole pass stops drawing it.
     */
    private static float riseArmReveal(float climb) {
        if (climb <= 0F) {
            return 0F;
        }
        if (climb < RISE_ARM_OUT_CLIMB) {
            return climb / RISE_ARM_OUT_CLIMB;
        }
        if (climb < RISE_ARM_HOLD_CLIMB) {
            return 1F;
        }
        return Math.max(0F, 1F - (climb - RISE_ARM_HOLD_CLIMB) / (1F - RISE_ARM_HOLD_CLIMB));
    }

    /**
     * Clips everything below a world height, for the rest of this frame's world draws.
     *
     * <p>The lawn is a plane and a rising zombie is under it, so the clip is a half-plane: the
     * full width of the window, from the line up. Paired with a {@code pop()} by every caller,
     * and the whole stack is dropped at the end of the frame anyway.
     */
    private void pushGroundClip(PvzceCamera camera, float worldY) {
        int line = (int) Math.floor(camera.screenY(worldY));
        client.clipping().pushPixels(0, line,
                Math.max(1, client.window().width()),
                Math.max(1, client.window().height() - line));
    }

    private void renderEntity(ClientEntity entity) {
        if (entity.id() == defeatZombieId) {
            // The zombie that just walked in keeps eating: it is what the defeat sequence
            // turned the camera to look at. Re-asserted every frame because the mirror is
            // the server's - this is the one client-side exception, and it lasts as long as
            // the sequence does.
            entity.setAnimation(com.pvzce.api.entity.EntityAnimations.EAT);
        }
        // State-change driven local playback: the call is idempotent, so this
        // can safely run every frame without restarting the current clip.
        entity.playAnimation(entity.animation());
        // The bungee zombie's rope and target go under its shadow and art: they are objects
        // around it rather than parts of it (see BungeeRig).
        com.pvzce.client.renderer.BungeeRig.render(client, entity);
        if (entity.layer() != com.pvzce.api.entity.EntityLayers.UNDERGROUND) {
            drawShadow(client, entity, entityTexture(entity));
        }
        // The ice goes under the art: it is around the zombie's feet, so the legs have to come
        // down into it. Drawn every frame while the freeze lasts rather than spawned as a
        // particle, because it lasts as long as the status does - a particle is a burst.
        if ("zombie".equals(entity.kind()) && entity.frozen()) {
            drawFrozenSpikes(entity);
        }
        drawEntityArt(entity);
        if (entity.fertilized()) {
            client.drawSolid(entity.visualCellX() - 0.22F, entity.visualCellY() + entity.visualHeight() + 0.3F,
                    0.44F, 0.045F, 0.55F, 0.35F, 0.9F, 0.2F, 0.9F);
        }
        if (entity.laddered()) {
            client.drawTexture(com.pvzce.api.util.Identifier.withDefaultNamespace("textures/entities/plant/environment/roof_ladder"),
                    entity.visualCellX() - 0.18F, entity.visualCellY() + entity.visualHeight() - 0.45F,
                    0.36F, 1.25F, 0.24F, 1F, 1F, 1F, 1F);
        }
        drawHealthBar(entity);
    }

    /**
     * F10: a bar over anything that is not at full health.
     *
     * <p><b>Only what is hurt</b>, which is what makes the key useful rather than noisy: a bar over
     * every plant and zombie would be a second lawn drawn on top of the first one, and the reading
     * a player wants from it is "which of these is about to fall". The ceiling is the packet's
     * {@code maxHealth} rather than the content definition's number, because a wave may grow a
     * zombie's health and the world's difficulty tier multiplies it - against the definition, a
     * hell-tier buckethead would read as untouched.
     *
     * <p>Drawn in world cells through {@code drawSolid}, the same way the underground mound is: it
     * has to follow the entity, and a bar is geometry rather than art.
     */
    private void drawHealthBar(ClientEntity entity) {
        if (!client.healthBarsEnabled() || entity.health() >= entity.maxHealth()) {
            return;
        }
        boolean zombie = com.pvzce.api.entity.EntityKind.ZOMBIE.equals(entity.kind());
        if (!zombie && !com.pvzce.api.entity.EntityKind.PLANT.equals(entity.kind())) {
            return;
        }
        float width = HEALTH_BAR_CELLS;
        float height = HEALTH_BAR_CELLS * 0.16F;
        float x = entity.visualCellX() - width * 0.5F;
        float y = entity.visualCellY() + entity.visualHeight() + HEALTH_BAR_GAP;
        float ratio = MathUtil.clamp01(entity.health() / (float) Math.max(1, entity.maxHealth()));
        client.drawSolid(x, y, width, height, HEALTH_BAR_Z, 0F, 0F, 0F, 0.75F);
        // Green to red as it empties, so the bar can be read at a glance without comparing lengths.
        client.drawSolid(x, y, width * ratio, height, HEALTH_BAR_Z, 1F - ratio,
                0.1F + 0.9F * ratio, 0.15F, 0.95F);
    }

    /**
     * The ice a held zombie stands in.
     *
     * <p>At the ground contact point, the same one its shadow is centred on, so the spikes sit
     * on the lawn rather than floating at whatever height the zombie's art happens to reach.
     */
    private void drawFrozenSpikes(ClientEntity entity) {
        float contact = com.pvzce.client.renderer.EntityVisuals.anchorLift(entity.kind());
        float width = com.pvzce.client.renderer.EntityVisuals.FROZEN_SPIKES_WIDTH;
        float height = com.pvzce.client.renderer.EntityVisuals.FROZEN_SPIKES_HEIGHT;
        float drawX = entity.visualCellX();
        float drawY = entity.visualCellY() - contact + liquidDrop(entity);
        client.drawTexture(com.pvzce.client.renderer.EntityVisuals.FROZEN_SPIKES_TEXTURE,
                drawX - width * 0.5F, drawY - height * 0.5F, width, height,
                com.pvzce.client.renderer.EntityVisuals.FROZEN_SPIKES_Z, 1F, 1F, 1F, 0.95F);
    }

    /**
     * How far the entity's ground contact point moves for standing on a liquid.
     *
     * <p>Delegated to the animation manager, which is where the anchor that moves for the same
     * reason is computed: the shadow, the frozen spikes and the art have to shift together, or a
     * shadow stays on the cell grid while the thing casting it floats down into the pool.
     */
    private float liquidDrop(ClientEntity entity) {
        return client.animations() == null ? 0F : client.animations().liquidDrop(entity);
    }

    /**
     * A seed packet lying on the lawn: the card a broken container handed over.
     *
     * <p>Drawn as the card itself - the same chrome and icon the bar would draw - because that is
     * what it is, and because "a plant is lying there" is what the player has to see to know the
     * pot gave them something. Nothing about it is an animation, so it goes through
     * {@code SeedCardRenderer} rather than the entity art path; its price footer prints nothing,
     * which is the truth (the container was the payment).
     *
     * <p>While it is in the player's hand it is not drawn here at all: the cursor carries it (see
     * {@link #renderCarriedPlant}), and drawing both would show the same plant twice.
     *
     * <p>The flash is the server's clock rather than a local one: the entity's health is the ticks
     * it has left, so a packet restored from a save flashes when <em>it</em> is nearly out of time,
     * not when this client happened to hear about it.
     */
    private void drawCardDrop(ClientEntity entity) {
        if (entity.id() == client.level().heldCardEntityId()) {
            return;
        }
        com.pvzce.client.renderer.EntityVisuals.Visuals visuals =
                com.pvzce.client.renderer.EntityVisuals.of(entity.kind());
        float width = visuals.spriteWidth();
        float height = visuals.spriteHeight();
        float brightness = 1F;
        if (entity.health() <= com.pvzce.common.PvzceConstants.CARD_DROP_FLASH_TICKS) {
            // A square wave rather than a fade: "this is about to go" is a warning, and one that
            // dims smoothly reads as a packet that is merely in shadow.
            double phase = (System.nanoTime() / 1_000_000_000.0) * 6.0;
            brightness = Math.sin(phase) > 0 ? 1F : 0.45F;
        }
        com.pvzce.client.gui.SeedCardRenderer.CardModel model =
                new com.pvzce.client.gui.SeedCardRenderer.CardModel(
                        com.pvzce.client.gui.hud.cardbar.CardPainter.icon(entity.defIdString()),
                        com.pvzce.client.gui.SeedCardRenderer.CardKind.PLANT,
                        com.pvzce.common.network.packet.SlotInfo.NO_PRICE,
                        brightness, 1F, true, 0F, false, null, false);
        com.pvzce.client.gui.SeedCardRenderer.draw(client, model,
                entity.visualCellX() - width / 2F,
                entity.visualCellY() - visuals.spriteOffsetY(),
                width, height);
    }

    /**
     * Lights up the container under the cursor: a vase or a scary pot, when a click would do
     * something with it.
     *
     * <p>The user asked for it ("鼠标放到花瓶上后，应该让花瓶高亮显示"), and the reason is the same one
     * the plantable-cell tint exists for: a container is a thing the *click* acts on, and without a
     * mark the only way to find out that a pot is clickable is to click it. The rule is deliberately
     * the click's own rule rather than "the cell holds a pot": a packet in hand plants rather than
     * smashes, and a pot refuses a plant card while a vase takes one - so the highlight appears
     * exactly when the click would land, and never when it would be refused.
     */
    private void renderContainerHover(PvzceCamera camera) {
        double cursorX = client.window().cursorX();
        double cursorY = client.window().cursorY();
        if (!camera.inBoard(cursorX, cursorY)) {
            return;
        }
        int x = camera.cellX(cursorX, cursorY);
        int y = camera.cellY(cursorX, cursorY);
        if (x < 0 || x >= client.level().width() || y < 0 || y >= client.level().height()) {
            return;
        }
        String scene = client.level().sceneAt(x, y);
        if (!com.pvzce.common.PvzceIds.isSmashableContainer(Identifier.tryParse(scene))) {
            return;
        }
        if (client.level().holdingCard()) {
            // A packet in hand plants: a container is in the way, not a target.
            return;
        }
        boolean bareHanded = selectedCard < 0 && client.level().carriedPlant().isEmpty();
        boolean cardOntoVase = selectedCard >= 0 && isVaseScene(scene);
        if (!bareHanded && !cardOntoVase) {
            return;
        }
        // A warm wash rather than a green/red tint: this is not a verdict about whether the cell
        // can take a plant, it is "there is something here to hit".
        client.drawSolid(x, y, 1F, 1F, 0.21F, 1F, 0.94F, 0.66F, 0.25F);
    }

    /** True when this cell's scene is one of the player's own garden vases. */
    private static boolean isVaseScene(String scene) {
        Identifier id = Identifier.tryParse(scene);
        return com.pvzce.common.PvzceIds.VASE.equals(id)
                || com.pvzce.common.PvzceIds.VASE_FULL.equals(id);
    }

    /**
     * The seed packet under the cursor, or {@code null}.
     *
     * <p>Tested as a box rather than by a radius: a packet is a card lying on the lawn, and the
     * thing the player aims at is the picture of it. The nearest centre wins, so two packets in one
     * cell (two containers broken a moment apart) do not flicker between frames.
     */
    private ClientEntity cardDropAt(double rawMouseX, double rawMouseY) {
        PvzceCamera camera = client.camera();
        float worldX = camera.worldX(rawMouseX, rawMouseY);
        float worldY = camera.worldY(rawMouseX, rawMouseY);
        com.pvzce.client.renderer.EntityVisuals.Visuals visuals =
                com.pvzce.client.renderer.EntityVisuals.of(
                        com.pvzce.api.entity.EntityKind.CARD_DROP);
        ClientEntity best = null;
        float bestDistance = Float.MAX_VALUE;
        for (ClientEntity entity : client.level().entities().values()) {
            if (!com.pvzce.api.entity.EntityKind.CARD_DROP.equals(entity.kind())
                    || entity.id() == client.level().heldCardEntityId()) {
                continue;
            }
            float dx = entity.cellX() - worldX;
            // The box the packet is drawn in, plus a margin: the picture is deliberately small
            // (see EntityVisuals.CARD_DROP), and a card the player has to hit pixel-perfect is a
            // card they lose to the clock.
            float halfWidth = visuals.spriteWidth() * CARD_DROP_PICKUP_MARGIN / 2F;
            float halfHeight = visuals.spriteHeight() * CARD_DROP_PICKUP_MARGIN / 2F;
            float dy = entity.cellY() - worldY;
            if (Math.abs(dx) > halfWidth || Math.abs(dy) > halfHeight) {
                continue;
            }
            float distance = dx * dx + dy * dy;
            if (distance < bestDistance) {
                best = entity;
                bestDistance = distance;
            }
        }
        return best;
    }

    /**
     * The plant the seed packet in hand would plant: what the card grants.
     *
     * <p>Asked through the resolver, because a packet's card id and the plant it grants need not
     * be the same string (a vase field may hold a slot id) - and the server resolves it the same
     * way when the plant is actually placed.
     */
    private String heldCardPlantId() {
        String card = client.level().heldCard();
        if (card.isEmpty()) {
            return "";
        }
        Identifier id = Identifier.tryParse(card);
        if (id == null) {
            return card;
        }
        return com.pvzce.common.core.SlotResolver.resolve(id)
                .map(resolved -> resolved.content().toString())
                .orElse(card);
    }

    /**
     * The entity's own art: its animation when it has one, otherwise its sprite.
     *
     * <p>Split out of {@code renderEntity} so the placement preview draws the very same
     * thing - a plant ghost drawn by a second code path would be the second answer to "what
     * does this plant look like" that this renderer spent a refactor removing.
     */
    private void drawEntityArt(ClientEntity entity) {
        if (com.pvzce.api.entity.EntityKind.CARD_DROP.equals(entity.kind())) {
            drawCardDrop(entity);
            return;
        }
        Identifier texture = entityTexture(entity);
        if (client.animations() != null && client.animations().render(entity)) {
            return;
        }

        // No animation resource: show the first frame. The old _2.png toggle
        // is intentionally gone; content opts in through animation JSON.
        // Same two factors the animation path uses (see AnimationManager#scalesFor): the
        // board's aspect correction for everything but a drop, and the definition's own
        // render_scale for everything, applied to both axes so it never changes the shape.
        boolean drop = entity.kind().equals(com.pvzce.api.entity.EntityKind.RESOURCE);
        // Two factors, the same two the animation path uses (see AnimationManager#scalesFor):
        // the definition's own render_scale, and the entity's per-drop multiplier on top of
        // it - a small sun-shroom's sun is the same resource drawn smaller.
        float renderScale = com.pvzce.common.core.EntityArt.renderScale(entity.defId())
                * entity.renderScale();
        float xScale = (drop ? 1F : client.spriteXScale()) * renderScale;
        float yScale = renderScale;
        EntityVisuals.Visuals visuals = EntityVisuals.of(entity.kind());
        // Drawn at the interpolated position, not the packet's: see ClientEntity.visualCellX.
        // One sample per 20 Hz packet is a staircase at any frame rate above 20.
        float drawX = entity.visualCellX();
        float drawY = entity.visualCellY();
        float drawHeight = entity.visualHeight();
        drawY += terrainHeight(drawX, entity.gridY());
        if (entity.layer() == com.pvzce.api.entity.EntityLayers.UNDERGROUND) {
            // Burrowing zombies are shown as a mound instead of a sprite.
            client.drawSolid(drawX - 0.3F, drawY - 0.2F, 0.6F, 0.4F, 0.05F,
                    0.4F, 0.28F, 0.16F, 0.9F);
            return;
        }
        // The same look the animated path wears: the night lift and, for a slowed zombie,
        // the frozen tint. A sprite fallback that ignored them would be a second answer to
        // "how is this entity lit" - visible the moment a content pack ships no animation.
        if (drop) {
            float[] dropTint = EntityVisuals.dropTint(entity.defIdString());
            client.pushEntityTint(dropTint[0], dropTint[1], dropTint[2]);
        } else {
            client.pushEntityLook(entity);
        }
        // Mirroring, for the one entity state that faces the other way (a charmed zombie). The
        // animated path does the same thing inside its own quads; this is the flat-sprite
        // fallback a content pack without a controller file lands on.
        boolean flip = "zombie".equals(entity.kind()) && (entity.charmed() || entity.animation().endsWith("_right"));
        float left = flip
                ? drawX - (visuals.spriteWidth() - visuals.spriteOffsetX()) * xScale
                : drawX - visuals.spriteOffsetX() * xScale;
        try {
            client.drawTextureRegion(texture, flip ? 1F : 0F, 0F, flip ? 0F : 1F, 1F, left,
                    drawY - visuals.spriteOffsetY() * yScale + drawHeight,
                    visuals.spriteWidth() * xScale, visuals.spriteHeight() * yScale, visuals.baseZ(),
                    1, 1, 1, 1);
        } finally {
            client.popEntityTint();
        }
    }

    /**
     * Projected entity shadow rendered through the world shader. Direction,
     * length and tint follow the interpolated sun/moon position.
     */
    private void drawShadow(PvzceClient client, ClientEntity entity, Identifier texture) {
        // Where the sprite touches the ground: the same feet-to-anchor lift the animation path
        // draws with, so the shadow starts under the feet. A fixed 0.46 - which is what this
        // used to use - put the contact point a hand's width below the art, and on the flat
        // tiled lawn that read as a soft smudge while on the original's painted lawn it reads
        // as what it is: a second, grey object the plant is hovering above.
        float contact = com.pvzce.client.renderer.EntityVisuals.anchorLift(entity.kind());
        float[] visual = client.animations() == null ? null : client.animations().visualSize(entity);
        float spriteXScale = client.spriteXScale();
        // A definition can ask to be drawn bigger or smaller than its art, and a drop can
        // ask for a size of its own on top of that; the shadow has to follow both, or a
        // scaled entity slides around on a shadow that belongs to the size it no longer is.
        float renderScale = com.pvzce.common.core.EntityArt.renderScale(entity.defId())
                * entity.renderScale();
        // Same interpolated position as the art: a shadow that arrives a packet early or
        // late slides out from under the thing casting it.
        float drawX = entity.visualCellX();
        float drawY = entity.visualCellY();
        float drawHeight = entity.visualHeight();
        if (entity.kind().equals("plant")) {
            float width = (visual == null ? 0.68F : Math.max(0.20F, visual[0] * 0.85F)) * spriteXScale;
            float height = visual == null ? 0.76F : Math.max(0.20F, visual[1]);
            client.drawEntityShadow(texture, drawX, drawY - contact + liquidDrop(entity),
                    width * renderScale, height * renderScale, 0.4F);
        } else if (entity.kind().equals("zombie") && entity.layer() != -1) {
            float lift = Math.max(0F, drawHeight - terrainHeight(drawX, entity.gridY()));
            float alpha = Math.max(0.14F, 0.34F - lift * 0.14F);
            float width = (visual == null
                    ? Math.max(0.46F, 0.62F - lift * 0.06F)
                    : Math.max(0.20F, visual[0] * 0.85F)) * spriteXScale;
            float height = visual == null ? 0.95F : Math.max(0.20F, visual[1]);
            client.drawEntityShadow(texture, drawX, drawY - contact,
                    width * renderScale, height * renderScale, alpha);
        }
    }

    private void renderHud() {
        int height = client.guiHeight();
        // The original PvZ-style sun bank appears when the player picked the SunBank card
        // in the seed chooser: collecting sun is what that card buys.
        if (hasSunBank()) {
            int bankY = CardBarLayout.bankY(height);
            client.drawTexture(SUN_BANK, CardBarLayout.bankX(), bankY,
                    CardBarLayout.BANK_WIDTH, CardBarLayout.BANK_HEIGHT, 0.1F, 1F, 1F, 1F, 1F);
            String sunText = String.valueOf(client.level().sun());
            // The count is drawn in the display face, like the rest of the game's numbers in
            // this HUD: the sun bank is the original's own piece of chrome, and its digits are
            // what a player reads at a glance while planting.
            client.fonts().button().draw(sunText,
                    CardBarLayout.bankX()
                            + (CardBarLayout.BANK_WIDTH - client.fonts().button().width(sunText, 1F)) / 2F,
                    bankY + CardBarLayout.BANK_HEIGHT * 0.08F, 1F, 0.12F, 0.07F, 0.03F, 1F);
        }
        renderCoinBank();

        String team = client.level().controlledTeamName().isEmpty()
                ? (client.level().controlledTeam().contains("zombie") ? "僵尸方" : "植物方")
                : client.level().controlledTeamName();
        client.fonts().body().draw("当前队伍：" + team, 16, 72, 0.8F, 0.85F, 0.85F, 0.9F, 1F);

        int y = 96;
        for (String message : client.level().messages()) {
            client.fonts().body().draw(message, 16, y, 0.9F, 0.1F, 0.1F, 0.1F, 1F);
            y += 22;
        }

        // The level's own rules, in the corner the rest of the HUD leaves free: a buff changes
        // how the board is played, and the player chose it several screens ago.
        com.pvzce.client.gui.hud.BuffIconRow.render(client);
        renderCardHover();

        renderWaveBar();
        renderWaveWarning();
        renderPreparation();
    }

    /**
     * Names the seed packet or tool under the cursor.
     *
     * <p>The card bar has always been pictures only. A player learns what a packet is by planting
     * it, which is a fine way to learn and a poor way to choose; the name is already in the
     * language file for the almanac, so this only had to be drawn. Only the bar is hovered - not
     * the board, where the cursor belongs to the camera and a tip would follow every mouse move.
     */
    private void renderCardHover() {
        if (draggingCard >= 0 || modalityBlocksHover()) {
            return;
        }
        double guiX = client.guiMouseX(client.window().cursorX());
        double guiY = client.guiMouseY(client.window().cursorY());
        int index = cardBar().slotAt(guiX, guiY);
        if (index < 0) {
            return;
        }
        SlotInfo slot = slotInfo(index);
        if (slot == null) {
            return;
        }
        com.pvzce.client.gui.HoverTip.draw(client,
                com.pvzce.client.gui.HoverTip.nameOf(slot.defId(), slot.kind()),
                (float) guiX, (float) guiY, 1F);
    }

    /**
     * True while something is on top of the board that a card tip would talk over.
     *
     * <p>The end-of-level overlay, the reward packet and the defeat banner all sit above the
     * HUD, and a card name floating over them would be naming a card the player can no longer
     * use - the level is over.
     */
    private boolean modalityBlocksHover() {
        return !client.level().gameState().equals("running") || defeatNanos != 0L;
    }

    /**
     * The original's "Ready... Set... Plant!" banner when a level begins.
     *
     * <p>The server already plays {@code pvzce:sfx/ambient/readysetplant} as part of
     * the level's full state, so this is the visual half of the same beat: without
     * it the sound had nothing on screen and the level simply started. The three words
     * and their motion come from the game's own art and reanim
     * ({@link com.pvzce.client.gui.components.BannerAnimation}); the banner runs on the
     * wall clock and never blocks input.
     */
    private void renderEntryBanner() {
        if (entryNanos == 0L || !client.level().gameState().equals("running")) {
            return;
        }
        float seconds = (System.nanoTime() - entryNanos) / 1_000_000_000F;
        drawBanner(ENTRY_BANNER, seconds);
    }

    /**
     * The "FINAL WAVE" banner, on the same beat as the original: it arrives with the wave
     * it announces and leaves about two seconds later.
     */
    private void renderFinalWaveBanner() {
        if (finalWaveNanos == 0L) {
            return;
        }
        float seconds = (System.nanoTime() - finalWaveNanos) / 1_000_000_000F;
        if (seconds >= FINAL_WAVE_BANNER.duration()) {
            return;
        }
        drawBanner(FINAL_WAVE_BANNER, seconds);
    }

    /**
     * Draws one banner centred in the window.
     *
     * <p>Screen space, not world space: the words are the game talking to the player, and
     * they keep their size when the window changes (a banner pinned to the board would grow
     * and shrink with it).
     */
    private void drawBanner(com.pvzce.client.gui.components.BannerAnimation banner, float seconds) {
        float width = Math.min(client.guiWidth() * 0.8F,
                Math.min(client.guiWidth(), client.guiHeight() * 1.6F) * BANNER_WIDTH_FRACTION);
        banner.render(client, seconds, client.guiWidth() / 2F, client.guiHeight() * BANNER_CENTER_Y,
                width);
    }

    /**
     * Tracks the huge-wave warning's own window so it can fade rather than blink.
     *
     * <p>The server publishes the flag; the start and end times only exist here. A warning
     * that ends is forgotten, so the next one fades in again instead of appearing
     * mid-pulse.
     */
    private void tickWaveWarning() {
        boolean active = client.level().waveWarningActive()
                && client.level().gameState().equals("running");
        if (active && !waveWarningShown) {
            waveWarningNanos = System.nanoTime();
            waveWarningEndedNanos = 0L;
        } else if (!active && waveWarningShown) {
            // The flag is the server's; how it left is worth remembering for one fade, so
            // the message disappears instead of vanishing between two frames.
            waveWarningEndedNanos = System.nanoTime();
        }
        waveWarningShown = active;
    }

    /**
     * True when the sun bank is drawn.
     *
     * <p>The SunBank card is one reason to draw it - collecting sun is what that card buys - but
     * not the only one: a level that hands the player a budget and then takes the sky away (谁笑到最后,
     * and every rhythm level) has no SunBank card and still has sun to spend, and without this the
     * player would be arranging a defence against a number they cannot see. So: the card, or a
     * balance that is not zero. A belt level has neither (its sun is always 0), which is why it
     * still draws no bank.
     */
    public boolean hasSunBank() {
        return hasSunBank(client.level().slots()) || client.level().sun() > 0;
    }

    /**
     * The level's card bar, built on first use.
     *
     * <p>Which bar a level gets is a mechanic's answer now, not a {@code conveyor()} flag:
     * {@code ClientMechanics.cardBar} asks the level's mechanics in turn and the ordinary
     * seed row is what is left when none of them offers one.
     */
    CardBar cardBar() {
        com.pvzce.common.network.packet.MutationStateS2C mutations = client.level().mutations();
        String wanted = mutations == null ? "" : mutations.cardBarKind();
        if (cardBar != null && !wanted.equals(cardBarKind)) {
            // The bar changed under the player - a mutation took it over or gave it back - so the
            // old bar goes. Whatever card was in hand is looked up again in the new bar by its
            // content id rather than dropped: a belt replaces the *plant* cards, and the shovel or
            // the watering can the player was holding is still there, at a different index.
            String heldId = selectedCardId();
            cardBar = null;
            selectedCard = -1;
            pendingSelection = heldId;
        }
        if (cardBar == null) {
            CardBar fromMechanic = com.pvzce.client.mechanic.ClientMechanics.cardBar(client.level(), this);
            cardBar = fromMechanic != null ? fromMechanic : new com.pvzce.client.gui.hud.cardbar.SeedCardBar(this);
            cardBarKind = wanted;
            reselectPending();
        }
        return cardBar;
    }

    /** The content id of the card in hand, or {@code null} when nothing is selected. */
    private String selectedCardId() {
        if (selectedCard < 0) {
            return null;
        }
        SlotInfo info = slotInfo(selectedCard);
        return info == null ? null : info.defId();
    }

    /**
     * Puts the player's hand back on the same card after the bar was rebuilt.
     *
     * <p>Matched by content id, not by index: a belt allocates its own slot indices and appends
     * the cards it did not take over at the end, so the shovel that was card 3 is card 7 now. If
     * the card really is gone - a rewrite replaced it, or the belt took the plant it named - the
     * hand is left empty, which is the honest answer and what the old code did for every case.
     */
    private void reselectPending() {
        String heldId = pendingSelection;
        pendingSelection = null;
        if (heldId == null) {
            return;
        }
        for (SlotInfo info : client.level().slots()) {
            if (heldId.equals(info.defId())) {
                selectedCard = info.index();
                return;
            }
        }
    }

    /**
     * Reacts to a new mutation state: the banner and the card bar.
     *
     * <p>Identity comparison rather than a revision number in the packet, because the server sends
     * a fresh record for every update - the same trick {@code mutationRevision} plays for the
     * banner, and one fewer field on the wire.
     */
    private void applyMutationState() {
        com.pvzce.common.network.packet.MutationStateS2C mutations = client.level().mutations();
        if (mutations == mutationRevision) {
            return;
        }
        int before = mutationRevision == null ? 0 : mutationRevision.effects();
        mutationRevision = mutations;
        mutationHud.apply(mutations);
        // A mutation may have changed what the board looks like in a way the client builds once per
        // level - the fog it rolls in is a world overlay, and overlays are made when the level
        // starts. Rebuilt rather than patched: the overlays are cheap to construct and each one
        // reads the level's current state anyway.
        if (mutations != null
                && com.pvzce.common.level.mutation.MutationEffects.FOG
                        .isSet(mutations.effects() ^ before)) {
            overlays = null;
        }
    }

    /** World-space overlays the level's mechanics asked for; built once per level. */
    private java.util.List<com.pvzce.client.mechanic.ClientMechanic.WorldOverlay> worldOverlays() {
        if (overlays == null) {
            overlays = com.pvzce.client.mechanic.ClientMechanics.worldOverlays(client.level());
        }
        return overlays;
    }

    @Override
    public float rightBound() {
        // The left edge of the *pair* of HUD buttons, not of the pause button: the speed button
        // sits immediately to its left now, so a bar that ran up to the pause button's edge would
        // run under the speed button.
        int leftmost = speedButton != null ? speedButton.x()
                : pauseButton != null ? pauseButton.x() : client.guiWidth() - 12;
        return leftmost - 8F;
    }

    @Override
    public int selectedCardIndex() {
        return selectedCard;
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
        int bankX = CardBarLayout.MARGIN;
        int bankY = CardBarLayout.MARGIN;
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
        client.fonts().body().draw(coins, bankX + COIN_BANK_WIDTH * 0.32F,
                bankY + (COIN_BANK_HEIGHT - client.fonts().body().lineHeight(textScale)) / 2F,
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
        return total + claimedMowerCoins + claimedRewardCoins;
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
            return new float[]{CardBarLayout.MARGIN + COIN_BANK_WIDTH * 0.15F,
                    CardBarLayout.MARGIN + COIN_BANK_HEIGHT / 2F};
        }
        return CardBarLayout.bankCentre(client.guiHeight());
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
    /**
     * The most flags one meter is allowed to draw.
     *
     * <p>A hundred-odd is already a texture of dots at this size; the cap is what keeps an endless
     * level's meter costing the same as an ordinary one's, whatever its table holds.
     */
    private static final int MAX_WAVE_FLAGS = 128;

    /**
     * The preparation phase's button and hint, drawn while the phase is running.
     *
     * <p>Centred over the board rather than tucked in with the pause and speed buttons: it is not
     * one of the things a player touches mid-wave, it is the one thing to press before the level
     * has begun, and the original puts it in the middle of the lawn for the same reason.
     *
     * <p>Drawn *and* hit-tested from {@link #startWavesRect()} - the same two numbers - because a
     * button whose picture and whose target are computed apart is the failure this HUD has had
     * before (see the tray's own note in {@code TitleScreen}).
     */
    private void renderPreparation() {
        if (!client.level().preparing()) {
            if (startWavesButton != null) {
                // The phase is over: the button goes away rather than staying on a running board,
                // where pressing it would mean nothing.
                startWavesButton.setVisible(false);
            }
            return;
        }
        if (startWavesButton == null) {
            // Built on the first frame the phase is seen rather than in `init()`: the state is
            // streamed, so it arrives *after* the screen was built, and a widget created in init()
            // would never exist. `onResize` rebuilds the screen and re-creates it with the new
            // geometry.
            float[] rect = startWavesRect();
            startWavesButton = new Button(Math.round(rect[0]), Math.round(rect[1]),
                    Math.round(rect[2]), Math.round(rect[3]),
                    GuiLang.raw("pvzce.preparation.start", "开始"),
                    () -> client.connection().send(
                            new com.pvzce.common.network.packet.StartWavesC2S()));
            addWidget(startWavesButton);
        }
        startWavesButton.setVisible(true);
        int guiW = client.guiWidth();
        float[] rect = startWavesRect();
        String hint = GuiLang.raw("pvzce.preparation.hint", "准备阶段：只能用初始阳光，摆好再开始");
        client.fonts().body().draw(hint,
                (guiW - client.fonts().body().width(hint, 0.9F)) / 2F,
                rect[1] + rect[3] + 6F, 0.9F, 0.95F, 0.95F, 0.8F, 1F);
        String sun = GuiLang.raw("pvzce.preparation.sun", "可用阳光") + "：" + client.level().sun();
        client.fonts().body().draw(sun,
                (guiW - client.fonts().body().width(sun, 0.8F)) / 2F,
                rect[1] - client.fonts().body().lineHeight(0.8F) - 4F, 0.8F, 1F, 0.95F, 0.55F, 1F);
    }

    /**
     * Where the preparation phase's start button is, as {@code {x, y, width, height}}.
     *
     * <p>Above the card bar and centred: the bar owns the bottom of the screen, and the middle of
     * the board is where the player's eyes already are while they arrange the defence.
     */
    private float[] startWavesRect() {
        float width = Math.min(240F, client.guiWidth() * 0.4F);
        float height = Math.max(28F, Math.min(52F, client.guiHeight() / 12F));
        float x = (client.guiWidth() - width) / 2F;
        float y = cardBar().cardHeight() + 46F;
        return new float[] {x, y, width, height};
    }

    private void renderWaveBar() {
        int total = client.level().totalWaves();
        if (total <= 0) {
            return;
        }
        // Rounds are drawn on their own line rather than folded into the meter: the meter's
        // numbers are already the wave inside the current round (see WaveProgressS2C), so the
        // round is the one thing it cannot say for itself.
        renderRoundLabel();
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
        // The flags come from a sampled, bounded list rather than from walking every wave: a
        // level's table used to be a handful of entries and walking it cost nothing, but an endless
        // level's is thousands and this runs every frame (see ClientLevel.waveFlagCandidates for
        // the measurement that found it).
        int perFlag = (int) Math.max(6F, 10F * scale);
        int capacity = Math.min(MAX_WAVE_FLAGS, Math.max(1, meterWidth / perFlag));
        for (int i : com.pvzce.client.ClientLevel.waveFlagCandidates(total, current, capacity)) {
            if (!client.level().isHugeWave(i)) {
                continue;
            }
            // The meter runs right to left, so the first wave's flag is the rightmost one.
            drawWaveFlag(trackX + trackWidth * (1F - (i + 0.5F) / total), meterY + meterHeight - 2,
                    scale * (client.level().isFinalWave(i) ? 1.15F : 1F), i < current ? 1F : 0F);
        }
        // The head last, so it reads as standing in front of the flags it has reached.
        drawMeterHead(trackX + trackWidth * (1F - progress), meterY, meterHeight, scale);
        drawMeterLabel(trackX, trackWidth, meterY, meterHeight, plateY, plateHeight);
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

        // On a level played in rounds the round line takes this gap instead of the level name:
        // both are one short line to the left of the meter, and "第 3 轮 · 7/13 波" is the one a
        // player in a two-hour run needs. Drawing both would overlap them, since neither the name
        // nor the label is measured against the other.
        String name = client.level().runsInRounds() ? "" : client.currentLevelName();
        if (name.isEmpty()) {
            return;
        }
        // Capped so the line box stays inside the meter's band; the meter sits close to the
        // bottom edge, and a taller line would be clipped off-screen.
        float nameScale = Math.max(0.75F, Math.min(0.95F, 1.2F * (meterHeight / METER_NATIVE_HEIGHT)));
        float nameWidth = client.fonts().body().width(name, nameScale);
        float nameX = trackX - nameWidth - 8F;
        if (nameX < 4F) {
            return;
        }
        float nameY = meterY + (meterHeight - client.fonts().body().lineHeight(nameScale)) / 2F + 4F;
        // Drawn twice: the meter sits on whatever the level's background art happens to be
        // there, and a drop shadow is what keeps a level name readable on a pale sidewalk.
        client.fonts().body().draw(name, nameX + 1F, nameY - 1F, nameScale, 0.05F, 0.05F, 0.05F, 0.8F);
        client.fonts().body().draw(name, nameX, nameY, nameScale, 1F, 0.96F, 0.72F, 1F);
    }

    /**
     * "第 3 轮 · 7/13 波" above the meter, on a level that runs in rounds.
     *
     * <p>Shown only on an endless level: an ordinary level's meter is already the whole run, and
     * a "round 1 of 1" line would be noise. The round's wave count comes from the server rather
     * than from the meter width, because on these levels the round gets longer as the run goes
     * on and the meter has no way to know that on its own.
     */
    private void renderRoundLabel() {
        if (!client.level().runsInRounds()) {
            return;
        }
        int total = client.level().totalWaves();
        int current = Math.max(0, Math.min(total, client.level().currentWave()));
        String text = "第 " + Math.max(1, client.level().round()) + " 轮 · " + current + "/" + total + " 波";
        // On the meter's own line, left of it like the level name and above it like nothing:
        // the space above the meter is the pause button's, and a label drawn there is behind it.
        float scale = Math.max(0.75F, Math.min(0.95F, 1.2F * (METER_SCALE)));
        float meterHeight = Math.round(METER_NATIVE_HEIGHT * METER_SCALE);
        float meterY = 6F + Math.round(11F * METER_SCALE);
        float textWidth = client.fonts().body().width(text, scale);
        float x = client.guiWidth() - 14F - Math.round(METER_NATIVE_WIDTH * METER_SCALE)
                - textWidth - 8F;
        if (x < 4F) {
            // Not enough room beside the meter (a narrow window): the wave count is the more
            // useful half, so the round line gives way rather than overlapping the level name.
            return;
        }
        float y = meterY + (meterHeight - client.fonts().body().lineHeight(scale)) / 2F + 4F;
        client.fonts().body().draw(text, x + 1F, y - 1F, scale, 0.05F, 0.05F, 0.05F, 0.8F);
        client.fonts().body().draw(text, x, y, scale, 1F, 0.9F, 0.55F, 1F);
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

    /**
     * "A huge wave of zombies is approaching!" - the original's red warning.
     *
     * <p>It used to blink at 3 Hz over the wave meter; the original lets it fade up over the
     * middle of the lawn and grow slightly while it is up, which reads as a warning rather
     * than as a broken gauge. The words are still text (the original draws them with its own
     * font and the assets contain no image for them); the <em>motion</em> is the original's.
     *
     * <p>The final wave is not special-cased here: its own banner ("FINAL WAVE") arrives
     * with the wave itself, so this message announcing that a big one is coming is the same
     * words either way.
     */
    private void renderWaveWarning() {
        if (waveWarningNanos == 0L || !client.level().gameState().equals("running")) {
            return;
        }
        long now = System.nanoTime();
        float elapsed = (now - waveWarningNanos) / 1_000_000_000F;
        // Fades in while the server says a warning is up, and out over the same short beat
        // once it is not: the alternative is a line of red text appearing and disappearing
        // between two frames, which reads as a glitch rather than as an announcement.
        float alpha;
        if (waveWarningShown) {
            alpha = Math.min(1F, elapsed / WAVE_WARNING_FADE_SECONDS);
        } else {
            float sinceEnd = (now - waveWarningEndedNanos) / 1_000_000_000F;
            if (waveWarningEndedNanos == 0L || sinceEnd >= WAVE_WARNING_FADE_SECONDS) {
                return;
            }
            alpha = 1F - sinceEnd / WAVE_WARNING_FADE_SECONDS;
        }
        // The huge-wave warning is text like every other line, and reads its words from the lang
        // pack: it used to be a hardcoded string that no locale or resource pack could reach.
        String warning = com.pvzce.client.gui.GuiLang.raw("pvzce.announce.huge_wave", "一大波僵尸即将来袭");
        // Fitted to the window rather than to a fixed scale: the line is eleven full-width
        // glyphs, so a scale picked from the window height alone ran off both edges at
        // 16:9 - the message was wider than the screen it was warning about.
        float unit = Math.max(1F, client.fonts().body().width(warning, 1F));
        float scale = Math.max(1.1F, Math.min(3.2F, client.guiWidth() * 0.86F / unit));
        // Grows while it stays up, so the message is never fully static; the shape of the
        // curve does not matter much, only that it never jumps.
        float textScale = scale * (1F + WAVE_WARNING_GROWTH * Math.min(1F, elapsed / 2.5F));
        float x = (client.guiWidth() - client.fonts().body().width(warning, textScale)) / 2F;
        float y = client.guiHeight() * 0.66F;
        // A black copy behind the red one, which is how the original's message stays legible
        // over grass: the UI font has no outline of its own.
        float shadow = Math.max(1.5F, textScale * 1.6F);
        client.fonts().body().draw(warning, x + shadow, y + shadow, textScale, 0.05F, 0.02F, 0.02F, 0.75F * alpha);
        client.fonts().body().draw(warning, x, y, textScale, 1F, 0.25F, 0.2F, alpha);
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

    /** Draws the level's card bar (see {@link CardBar}). */
    private void renderCardBar() {
        cardBar().render();
    }

    /**
     * The card the player is carrying, drawn under the cursor.
     *
     * <p>Only the icon: a full second copy of the packet would hide the cell it is about to
     * be planted in, and the hover tint underneath already says which cell that is.
     */
    private void renderDraggedCard() {
        if (draggingCard < 0) {
            return;
        }
        SlotInfo dragged = slotInfo(draggingCard);
        if (dragged == null) {
            return;
        }
        float guiX = (float) client.guiMouseX(client.window().cursorX());
        float guiY = (float) client.guiMouseY(client.window().cursorY());
        float size = Math.max(28F, cardBar().cardHeight() * 0.7F);
        client.drawTexture(com.pvzce.client.gui.hud.cardbar.CardPainter.icon(dragged),
                guiX - size / 2F, guiY - size / 2F, size, size, 0.45F, 1F, 1F, 1F, 0.85F);
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
        if (animations.isEmpty()) {
            return;
        }
        PvzceCamera camera = client.camera();
        float guiScale = Math.max(1, client.guiScale());
        int guiH = client.guiHeight();

        for (ResourceCollectAnimation animation : animations) {
            if (animation.finished(now)) {
                continue;
            }
            boolean coin = com.pvzce.common.PvzceIds.isCoin(animation.resourceId());
            // Each drop flies to its own bank, so each needs its own bank to fly to: sun goes
            // to the sun bank, which only exists on a level whose bar has the sun card. This
            // used to be one `!hasSunBank() -> return` for the whole list, which silently
            // killed the coin animation too - in a conveyor level (no sun card, so no sun
            // bank) coins were collected and simply vanished instead of flying to the bag.
            if (!coin && !hasSunBank()) {
                continue;
            }
            float progress = animation.progress(now);
            if (progress >= 1F) {
                continue;
            }
            float[] target = bankTarget(animation.resourceId());
            float targetX = target[0];
            float targetY = target[1];
            float startX = camera.screenX(animation.worldX()) / guiScale;
            float startY = camera.screenY(animation.worldY() + Math.max(0F, animation.height()) - 0.22F) / guiScale;
            float eased = MathUtil.easeOutCubic(progress);
            float distance = Math.max(0F, (float) Math.hypot(targetX - startX, targetY - startY));
            float arc = Math.min(90F, 18F + distance * 0.16F) * (float) Math.sin(Math.PI * progress);
            float x = MathUtil.lerp(startX, targetX, eased);
            float y = MathUtil.lerp(startY, targetY, eased) + arc;

            float baseSize = Math.max(22F, Math.min(40F, guiH / 15F));
            float size = baseSize * (1F - 0.62F * progress);
            float alpha = progress < 0.86F ? 1F : Math.max(0F, (1F - progress) / 0.14F);
            client.drawTexture(animation.icon(), x - size / 2F, y - size / 2F,
                    size, size, 0.6F, 1F, 1F, 1F, alpha);
        }
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
        // A defeat is the original's own screen, and it arrives after the camera has
        // shown the zombie that caused it. Until then the board is left alone: no dimming,
        // no words, because the sequence is the message.
        if (defeatNanos != 0L) {
            if (!defeatScreenVisible()) {
                return;
            }
            float seconds = (System.nanoTime() - defeatNanos - DEFEAT_PAN_NANOS
                    - DEFEAT_CHEW_NANOS) / 1_000_000_000F;
            com.pvzce.client.gui.components.DefeatScreen.render(client, seconds);
            if (com.pvzce.client.gui.components.DefeatScreen.settled(seconds)) {
                // What the run amounted to, over the screen the original plays for a loss: on an
                // endless level this is the only score there is, on an ordinary one it is the same
                // three numbers, and on a rhythm level it is the song's own report - drawn upwards
                // from the same baseline so a two-line report does not run into the hint below it.
                java.util.List<String> summary = runSummaryLines();
                float summaryScale = 1.4F;
                for (int line = 0; line < summary.size(); line++) {
                    String text = summary.get(line);
                    client.fonts().body().draw(text,
                            (width - client.fonts().body().width(text, summaryScale)) / 2F,
                            height * 0.16F + (summary.size() - 1 - line)
                                    * client.fonts().body().lineHeight(summaryScale),
                            summaryScale, 1F, 0.95F, 0.8F, 1F);
                }
                String hint = "点击任意处返回";
                float hintScale = 1.2F;
                client.fonts().body().draw(hint,
                        (width - client.fonts().body().width(hint, hintScale)) / 2F,
                        height * 0.08F, hintScale, 1F, 1F, 1F, 1F);
            }
            return;
        }
        client.drawSolid(0, 0, width, height, 0.5F, 0F, 0F, 0F, 0.45F);
        // The player's own outcome, not the plant side's: see isDefeat.
        boolean plantWin = client.level().gameState().equals("won");
        String text = plantWin ? "胜利！" : "失败！";
        float scale = 4F;
        client.fonts().body().draw(text, (width - client.fonts().body().width(text, scale)) / 2F, height / 2F + 40, scale,
                plantWin ? 1F : 0.9F, plantWin ? 0.85F : 0.1F, plantWin ? 0.1F : 0.1F, 1F);
        String sub = "点击任意处返回世界选择";
        float subScale = 1.2F;
        client.fonts().body().draw(sub, (width - client.fonts().body().width(sub, subScale)) / 2F, height / 2F, subScale, 1, 1, 1, 1);
    }

    @Override
    protected void onMouseClicked(double guiX, double guiY, int button) {
        // The mowers stand half a cell off the left edge of the board, where no cell
        // exists - so this is the one press that has to be tested before the board bounds
        // reject it, and before the drop sweep claims it.
        if (button == 0 && client.level().gameState().equals("running")
                && beginMowerHold(rawMouseX(guiX), rawMouseY(guiY))) {
            return;
        }
        if (!client.level().gameState().equals("running")) {
            // The reward drop is the only clickable thing on a finished board; a click
            // that misses it is not "leave the level", it is a miss.
            if (hasRewardDrop()) {
                if (button == 0 && !isRising() && inside(guiX, guiY, rewardRect())) {
                    claimReward();
                }
                return;
            }
            // A defeat is still showing the zombie that caused it: the clicks that would
            // leave are the ones the player is about to make out of impatience, and taking
            // them would skip the only part of losing that explains what happened.
            if (defeatSequenceRunning()) {
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
        if (startWavesButton != null && startWavesButton.isVisible()
                && startWavesButton.isMouseOver(guiX, guiY)) {
            startWavesButton.mouseClicked(guiX, guiY, button);
            return;
        }

        int slot = slotAt(guiX, guiY);
        if (slot >= 0) {
            if (button == 0) {
                SlotInfo info = slotInfo(slot);
                // Zombie cards are selectable on the same terms: the click that places them is a
                // board click like any other, and the bar's own readiness rules (price, cooldown,
                // uses) are the same three numbers.
                if (info != null && (info.kind().equals("plant") || info.kind().equals("tool")
                        || info.kind().equals("zombie"))) {
                    if (!cardUsable(info) && !gloveCard(info)) {
                        // Cooling down, too expensive, out of uses. The server would refuse
                        // the placement too, but by then the player has picked a cell and
                        // is waiting for something to happen; saying no at the click is
                        // both earlier and clearer. Nothing is selected - a refused card
                        // must not become the thing the next board click spends.
                        refuseCard(slot);
                    } else if (selectedCard == slot) {
                        // Clicking the card that is already selected puts it back down. The
                        // selected card is what the next board click spends, so picking one is
                        // a mode the player has to be able to leave without spending it -
                        // right-click and a click outside the board already cancel, and the
                        // card itself is the most obvious place to try. Putting a card down
                        // is the same gesture as picking it up, so it is the same sound.
                        selectedCard = -1;
                        playCardSound(info);
                    } else {
                        // Picking a card is the player changing their mind about the next click,
                        // and a cannon they armed a moment ago is the previous answer to that
                        // question. Two pending modes at once would make the next click ambiguous.
                        cancelAiming();
                        selectedCard = slot;
                        client.connection().send(new PickCardC2S(slot));
                        playCardSound(info);
                    }
                    // The same press also picks the card up, so the player can drag it
                    // straight onto a cell and let go. Both gestures end in the same place:
                    // see onMouseReleased.
                    draggingCard = selectedCard;
                } else {
                    cancelSelection();
                }
            }
            return;
        }
        if (cardBar().contains(guiX, guiY)) {
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
            // Holding the button down and dragging over the lawn picks up everything the
            // cursor touches (see onMouseDragged); the press itself is just the first
            // sample of that sweep.
            sweeping = true;
            ClientEntity drop = resourceDropAt(rawX, rawY);
            if (drop != null) {
                collectDrop(drop);
                return;
            }
            // A seed packet is picked up one click at a time rather than swept over: it is a
            // plant the player is choosing, and a sweep across a lawn of packets would pick one
            // up by accident and then refuse the next (a hand holds one plant).
            ClientEntity packet = cardDropAt(rawX, rawY);
            if (packet != null) {
                client.connection().send(
                        new com.pvzce.common.network.packet.PickUpCardC2S(packet.id()));
                return;
            }
        }

        if (!camera.inBoard(rawX, rawY)) {
            if (button == 1) {
                cancelSelection();
            }
            return;
        }
        int cellX = camera.cellX(rawX, rawY);
        int cellY = camera.cellY(rawX, rawY);
        if (cellX < 0 || cellX >= client.level().width() || cellY < 0 || cellY >= client.level().height()) {
            return;
        }

        if (button == 1) {
            // A packet in hand is put back where it fell, rather than the click being ignored:
            // without it, a packet picked up by accident could only be spent somewhere.
            if (client.level().holdingCard()) {
                client.connection().send(
                        new com.pvzce.common.network.packet.ReleaseHeldCardC2S());
                return;
            }
            // Right-click gives up an aim before it gives up a card: while aiming, the card bar is
            // not what the player is thinking about. (The original cancels a cob shot the same way.)
            if (aimingPlantId >= 0) {
                cancelAiming();
                return;
            }
            cancelSelection();
            return;
        }
        // Aiming: this click is the target. Sent before everything else on purpose - a player who
        // armed a cannon and then clicked a cell meant that cell, whatever is selected in the bar.
        if (aimingPlantId >= 0) {
            client.connection().send(new com.pvzce.common.network.packet.FireAtC2S(
                    aimingPlantId, cellX, cellY));
            cancelAiming();
            return;
        }
        // The packet in hand is planted by the next click on a cell, before any bar card is
        // consulted: the player is holding one plant and the click is where it goes. The server
        // answers whether the cell can take it (a refused cell keeps it in hand).
        if (client.level().holdingCard()) {
            selectedCard = -1;
            client.connection().send(
                    new com.pvzce.common.network.packet.PlantHeldCardC2S(cellX, cellY));
            return;
        }
        // A plant in hand is a move in progress: the click puts it down, whichever card is
        // selected now (none, usually). Checked before the selection because the drop is the
        // other half of a click that already happened.
        //
        // "A plant is in hand" is the server's own state, and the card that sends the drop is
        // looked up in the bar (the glove, the only tool that carries). Neither half of that is
        // remembered locally: a remembered slot was only right for the client that started the
        // move, and the fallback for when it was lost resolved the carried *plant's* card - which
        // the server refuses as "不是工具卡", leaving the plant in hand with no way to put it
        // down.
        if (!client.level().carriedPlant().isEmpty()) {
            SlotInfo glove = gloveInBar();
            // The move is over as far as the player is concerned; the card goes back with it.
            // Left selected, the glove stayed lit after the drop and the next click lifted
            // whatever the player clicked on.
            selectedCard = -1;
            if (glove != null) {
                client.connection().send(new UseToolC2S(glove.index(), cellX, cellY));
            }
            return;
        }
        if (selectedCard >= 0) {
            spendSelectedCard(cellX, cellY);
            return;
        }
        // Nothing in hand: in a level that grants a default tool (Whack-a-Zombie's mallet) the
        // click *is* the tool. It is not a selected card, so there is nothing to cancel
        // afterwards - every click swings again until the player picks a seed packet up.
        com.pvzce.api.content.ToolData granted =
                com.pvzce.client.mechanic.ClientMechanics.defaultTool(client.level());
        if (granted != null && granted.tool() != null) {
            // The gesture is the client's and goes first: the cursor has to answer the click in
            // the frame the player made it, not one round trip later.
            swingDefaultToolCursor();
            client.connection().send(new com.pvzce.common.network.packet.UseGrantedToolC2S(
                    granted.tool(), cellX, cellY));
            return;
        }
        // Nothing in hand and no tool to swing: a container under the pointer is what a bare click
        // breaks. The mallet is called up over that cell (see {@link #summonMallet}) - a gesture
        // and a request, not a tool, so this works in every level rather than only where a level
        // remembered to grant one.
        if (containerAt(cellX, cellY)) {
            summonMallet(cellX, cellY);
            return;
        }
        // A plant that is aimed by hand takes the bare click. Last of all because it is the
        // narrowest case - one plant in the game answers to it - and a click that is already
        // spoken for (a card, a carried plant, a granted tool, a container) must not be stolen.
        ClientEntity targetable = targetablePlantAt(cellX, cellY);
        if (targetable != null) {
            swingDefaultToolCursor();
            beginAiming(targetable.id());
        }
    }

    /**
     * The player's own plant in this cell that is fired by hand, or {@code null}.
     *
     * <p>Asked of the plant's own definition rather than of a list of ids kept here: whether a plant
     * is aimed by hand is a fact about what it does, and it is already written down once, in the
     * capability list of its content file. A client-side set of ids would be a second copy that a
     * mod's cannon could never join.
     */
    private ClientEntity targetablePlantAt(int cellX, int cellY) {
        for (ClientEntity entity : client.level().entities().values()) {
            if (!com.pvzce.api.entity.EntityKind.PLANT.equals(entity.kind())
                    || entity.gridX() != cellX || entity.gridY() != cellY) {
                continue;
            }
            Identifier defId = Identifier.tryParse(entity.defIdString());
            com.pvzce.api.content.PlantDef def =
                    defId == null ? null : com.pvzce.common.core.BuiltInRegistries.PLANTS.get(defId);
            if (def == null) {
                continue;
            }
            for (var capability : def.capabilities()) {
                if (com.pvzce.common.PvzceIds.id("cob_cannon").equals(capability.type())) {
                    return entity;
                }
            }
        }
        return null;
    }

    /**
     * Starts aiming a plant, and says so in the hint box.
     *
     * <p>The message is not decoration: a mode with no visible change is a mode the player will not
     * notice they are in, and the next click then goes somewhere they did not mean. The reticle
     * (see {@link #renderAimReticle}) is the other half of the same sentence.
     */
    private void beginAiming(int entityId) {
        aimingPlantId = entityId;
        // The card is put down *without* going through cancelSelection: that method ends an aim as
        // well as a selection - it is the shared "put that down" gesture - so calling it here
        // cleared the mode this method had just entered, and the reticle never appeared.
        if (selectedCard >= 0) {
            SlotInfo info = slotInfo(selectedCard);
            selectedCard = -1;
            if (info != null) {
                playCardSound(info);
            }
        }
        // Not a level hint (those are content), just the box's own refusal channel: the line is
        // about the mode the player is in, not about the level, and every level with a cannon
        // should say it without the author having to write it down.
        hints.refuse("瞄准一个格子开炮，右键取消");
    }

    /** Leaves the aiming mode. Called by the shot, by a cancel, and by anything that takes the click. */
    private void cancelAiming() {
        aimingPlantId = -1;
    }

    /**
     * Draws the reticle over the cell a shot would land in, plus the cell itself.
     *
     * <p>A crosshair rather than the green placement square: the two mean different things (this
     * one is "a blast lands here", not "a plant may go here"), and the blast covers nine cells -
     * so the arms are drawn one cell beyond the target, which is the area the cob actually covers.
     */
    private void renderAimReticle() {
        if (aimingPlantId < 0) {
            return;
        }
        int hoverX = client.camera().cellX(client.window().cursorX(), client.window().cursorY());
        int hoverY = client.camera().cellY(client.window().cursorX(), client.window().cursorY());
        if (hoverX < 0 || hoverY < 0
                || hoverX >= client.level().width() || hoverY >= client.level().height()) {
            return;
        }
        client.drawSolid(hoverX, hoverY, 1F, 1F, 0.22F, 1F, 0.55F, 0.1F, 0.3F);
        float z = 0.23F;
        float thickness = 0.08F;
        // The cross's arms: the whole 3x3 the cob covers, not just the cell under the pointer.
        client.drawSolid(hoverX - 1F, hoverY + 0.5F - thickness / 2F, 3F, thickness, z, 1F, 0.6F, 0.15F, 0.85F);
        client.drawSolid(hoverX + 0.5F - thickness / 2F, hoverY - 1F, thickness, 3F, z, 1F, 0.6F, 0.15F, 0.85F);
    }

    /**
     * How much bigger than the picture a seed packet's click target is.
     *
     * <p>1.5: the packet is half a cell wide, so the picture alone is a small target for a thing
     * that has twenty seconds to live. The picture stays the size it is - what grows is only the
     * area that counts as "the player clicked it".
     */
    private static final float CARD_DROP_PICKUP_MARGIN = 1.5F;

    /** True when this cell holds something a bare click breaks open: a vase, or a scary pot. */
    private boolean containerAt(int cellX, int cellY) {
        return com.pvzce.common.PvzceIds.isSmashableContainer(
                Identifier.tryParse(client.level().sceneAt(cellX, cellY)));
    }

    /**
     * Calls the mallet down on a cell: the gesture now, the answer when it comes.
     *
     * <p>Played on the click rather than on the server's reply, exactly like the cursor's own
     * swing - the reply is a round trip away, and a mallet that waits for it feels broken on the
     * pots that were already broken. The client knows there is something in the cell (it draws the
     * board), so the gesture is not a guess; whether the pot was really there to break is the
     * server's answer, and a swing at a pot that is already gone costs nothing.
     */
    private void summonMallet(int cellX, int cellY) {
        malletSwings.add(new MalletSwing(cellX, cellY));
        client.connection().send(
                new com.pvzce.common.network.packet.SmashContainerC2S(cellX, cellY));
    }

    /**
     * Draws the summoned mallets, and forgets the ones whose swing is over.
     *
     * <p>In world space at the cell rather than as a cursor: this mallet is a thing happening on
     * the lawn, and it is the same animation the cursor uses - the hammer's own file, its
     * {@code attack} clip - so the two read as the same object being swung.
     */
    private void renderMalletSwings() {
        if (malletSwings.isEmpty()) {
            return;
        }
        com.pvzce.client.animation.AnimationManager animations = client.animations();
        if (animations == null) {
            malletSwings.clear();
            return;
        }
        long now = System.nanoTime();
        // A dropped target has to be stopped as well as forgotten: playbacks are keyed by target,
        // so one that is simply dropped leaves its playback behind in the manager.
        malletSwings.removeIf(swing -> {
            if (now - swing.startNanos < MALLET_SWING_NANOS) {
                return false;
            }
            if (swing.target != null) {
                swing.target.stopAnimation();
            }
            return true;
        });
        for (MalletSwing swing : malletSwings) {
            if (swing.target == null) {
                Identifier file = com.pvzce.common.core.EntityArt.animationFile(HAMMER_ID);
                if (file == null) {
                    continue;
                }
                swing.target = new com.pvzce.client.animation.ArtTarget(file);
                swing.target.attach(animations);
                // Asked once: the file's own `on_end: idle` is what hands the clip back, and
                // re-asking every frame is what would restart the swing it is in the middle of.
                swing.target.play(ATTACK_CLIP);
            }
            com.pvzce.client.animation.AnimationPlayback playback = animations.playback(swing.target);
            if (playback == null) {
                continue;
            }
            playback.render(client, swing.cellX + 0.5F,
                    swing.cellY + 0.5F
                            - com.pvzce.client.renderer.EntityVisuals.anchorLift(
                                    com.pvzce.api.entity.EntityKind.PLANT),
                    com.pvzce.client.renderer.EntityVisuals.baseZ(
                            com.pvzce.api.entity.EntityKind.PLANT),
                    client.spriteXScale(), 1F);
        }
    }

    /**
     * Starts timing a long press on a parked mower; true when there is one under the cursor.
     *
     * <p>Consumes the press: a hold that turned into a plant placement because the button
     * came up on the board would be a mower and a plant for one gesture.
     */
    private boolean beginMowerHold(double rawX, double rawY) {
        PvzceCamera camera = client.camera();
        float worldX = camera.worldX(rawX, rawY);
        float worldY = camera.worldY(rawX, rawY);
        com.pvzce.common.level.mechanic.MowerMechanic.Row mower =
                com.pvzce.client.mechanic.ClientMechanics.parkedMowerAt(client.level(), worldX, worldY);
        if (mower == null) {
            return false;
        }
        mowerHoldRow = mower.row();
        mowerHoldNanos = System.nanoTime();
        // The mower is the thing being acted on, so a card in hand is put back: otherwise the
        // same press would leave a plant armed and the next click would spend it.
        cancelSelection();
        return true;
    }

    /** Forget a hold in progress. Called on release, and whenever the level stops running. */
    private void cancelMowerHold() {
        mowerHoldRow = -1;
    }

    /**
     * Fires the mower once the hold has been long enough.
     *
     * <p>From {@code tick} rather than from the release: the point of releasing a mower is
     * that it goes <em>now</em>, and making the player hold and then let go would add a beat
     * between the decision and the launch for no reason. The release only cancels.
     */
    private void tickMowerHold() {
        if (mowerHoldRow < 0) {
            return;
        }
        if (!client.level().gameState().equals("running")) {
            cancelMowerHold();
            return;
        }
        if (System.nanoTime() - mowerHoldNanos < MOWER_HOLD_NANOS) {
            return;
        }
        client.connection().send(new com.pvzce.common.network.packet.ReleaseMowerC2S(mowerHoldRow));
        cancelMowerHold();
    }

    /**
     * The bar that fills while a mower is held.
     *
     * <p>Drawn in GUI space and lifted clear of the mower: the mower is a prop on the lawn
     * with a tall handle, so a ring around it would cross the handle and the grass both. A
     * short bar above it is the same gesture with nothing in the way, and it is the only
     * thing on screen that says the hold is being counted.
     */
    private void renderMowerHold() {
        if (mowerHoldRow < 0) {
            return;
        }
        float progress = MathUtil.clamp01(
                (System.nanoTime() - mowerHoldNanos) / (float) MOWER_HOLD_NANOS);
        PvzceCamera camera = client.camera();
        float guiScale = Math.max(1, client.guiScale());
        com.pvzce.common.level.mechanic.MowerMechanic.Row mower =
                com.pvzce.client.mechanic.ClientMechanics.parkedMowerAt(
                        client.level(),
                        com.pvzce.common.level.mechanic.MowerMechanic.IDLE_X, mowerHoldRow + 0.5F);
        float mowerX = mower == null
                ? com.pvzce.common.level.mechanic.MowerMechanic.IDLE_X : mower.x();
        float centerX = camera.screenX(mowerX) / guiScale;
        float centerY = camera.screenY(mowerHoldRow + MOWER_HOLD_BAR_LIFT_CELLS) / guiScale;
        float left = centerX - MOWER_HOLD_BAR_WIDTH / 2F;
        // An empty trough and a filled bar: the player has to be able to see how much of the
        // hold is left, which a bar that simply grows from nothing does not say.
        client.drawSolid(left - 1F, centerY - 1F, MOWER_HOLD_BAR_WIDTH + 2F, MOWER_HOLD_BAR_HEIGHT + 2F,
                0.7F, 0.05F, 0.05F, 0.06F, 0.72F);
        client.drawSolid(left, centerY, MOWER_HOLD_BAR_WIDTH * progress, MOWER_HOLD_BAR_HEIGHT,
                0.71F, 1F, 0.84F, 0.25F, 0.95F);
    }

    /** One card of the bar by its index, or {@code null}. */
    private SlotInfo slotInfo(int index) {
        return client.level().slots().stream().filter(s -> s.index() == index).findFirst().orElse(null);
    }

    /**
     * The sound of handling a card: taking it off the bar, or putting it back.
     *
     * <p>The seed chooser has always played {@code seedlift} on the same gesture; the
     * in-game bar, where a card is picked up far more often, was silent. The shovel is the
     * one card that is not a seed packet, and the original gives it its own cue. Putting a
     * card down is the same object and the same gesture reversed, so it is the same sound -
     * the alternative, silence, reads as "that click did not register".
     */
    private void playCardSound(SlotInfo card) {
        if (client.sound() == null) {
            return;
        }
        boolean shovel = SHOVEL_CARD_ID.equals(card.defId());
        client.sound().play((shovel ? com.pvzce.common.PvzceSounds.EFFECT_SHOVEL
                : com.pvzce.common.PvzceSounds.UI_SEEDLIFT).toString(), 1F, 1F);
    }

    /**
     * Whether a card can be played right now.
     *
     * <p>{@code SlotInfo.available} is the server's own answer - cooldown, remaining uses
     * and price all folded together - so the client never has to re-derive the rules and
     * cannot disagree with the one that will refuse the placement.
     */
    private static boolean cardUsable(SlotInfo card) {
        return card.available() && card.cooldownLeft() <= 0 && card.hasUsesLeft();
    }

    /**
     * True when this card is a glove, which stays clickable while it recharges.
     *
     * <p>The glove is a two-click move, and a card that has started one is usable
     * <em>because</em> of that: the server answers its second click as the other half of the
     * move it already charged for (see {@code LevelServer.useTool}). Greying it out on cooldown
     * is what broke it - the cooldown starts the moment it picks a plant up, so the click that
     * would have put the plant down was refused, and the player was left holding a plant they
     * could not place. The server still refuses a glove click with nothing in hand while it
     * recharges, so this is a click the player is allowed to make, not one that cannot fail.
     */
    private static boolean gloveCard(SlotInfo card) {
        return card.kind().equals("tool") && GLOVE_ID.equals(card.defId()) && card.hasUsesLeft();
    }

    /**
     * The index of the bar's glove card, or {@code -1} when it has none.
     *
     * <p>The one lookup behind both halves of a move: the lift and the drop are the same click
     * sent with the same slot, and they are sent with the glove's rather than with a slot
     * remembered from the other half. That distinction is the whole bug: the drop used to fall
     * back to the card that grants the <em>carried plant</em> - a plant card - and the server
     * refused it as "不是工具卡", leaving the plant in hand.
     */
    static int gloveSlotIn(List<SlotInfo> bar) {
        if (bar == null) {
            return -1;
        }
        for (SlotInfo card : bar) {
            if (card != null && card.kind().equals("tool") && GLOVE_ID.equals(card.defId())) {
                return card.index();
            }
        }
        return -1;
    }

    /**
     * Refuses a card that cannot be played: the original's buzzer, a shake, and the grey
     * box saying which of the three reasons it was.
     *
     * <p>The shake is drawn by the card bar through {@link #cardShake}, which is why the
     * refused card is remembered by index rather than by identity. The line is what the
     * buzzer cannot say: the original's own two answers are "still recharging" and "not
     * enough sun", and a card that is merely spent says nothing extra.
     */
    private void refuseCard(int slot) {
        if (client.sound() != null) {
            client.sound().play(com.pvzce.common.PvzceSounds.UI_BUZZER.toString(), 1F, 1F);
        }
        refusedCard = slot;
        refusedCardNanos = System.nanoTime();
        if (levelHints != null) {
            SlotInfo card = slotInfo(slot);
            // Cooldown first: a card that is both recharging and unaffordable is one the
            // player has to wait for, and waiting is not something more sun can fix.
            boolean cooling = card != null && card.cooldownLeft() > 0;
            levelHints.onCardRefused(cooling
                    ? com.pvzce.client.gui.hud.HintBox.Refusal.cooldown()
                    : com.pvzce.client.gui.hud.HintBox.Refusal.notEnough());
        }
    }

    /** Drops the current selection, with the sound of putting the card back. */
    private void cancelSelection() {
        // An aim is a pending mode like a selected card, and every "put that down" gesture ends
        // both: right-click, a click outside the board, and the mower hold all come through here.
        cancelAiming();
        if (selectedCard < 0) {
            return;
        }
        SlotInfo info = slotInfo(selectedCard);
        selectedCard = -1;
        if (info != null) {
            playCardSound(info);
        }
    }

    /**
     * How far a refused card is drawn off its place, in GUI pixels.
     *
     * <p>A damped shake that starts and ends at zero, so the card never jumps into or out
     * of the wobble: three half-cycles is enough to read as "no" without looking broken.
     */
    /**
     * The cards a mutation has locked, straight from the state packet.
     *
     * <p>The screen already holds the latest {@code MutationStateS2C} (the panel draws from it), so
     * the bar asks it here rather than keeping a second copy of "which slots are shut" that could
     * arrive a frame late.
     */
    @Override
    public java.util.List<Integer> lockedSlots() {
        com.pvzce.common.network.packet.MutationStateS2C mutations = client.level().mutations();
        return mutations == null ? java.util.List.of() : mutations.lockedSlots();
    }

    @Override
    public float cardShake(int slotIndex) {
        if (slotIndex != refusedCard) {
            return 0F;
        }
        long elapsed = System.nanoTime() - refusedCardNanos;
        if (elapsed >= CARD_REFUSED_SHAKE_NANOS) {
            return 0F;
        }
        float progress = elapsed / (float) CARD_REFUSED_SHAKE_NANOS;
        return (float) Math.sin(progress * Math.PI * 6D)
                * CARD_REFUSED_SHAKE_PIXELS * (1F - progress);
    }

    /**
     * Spends the selected card on a cell: the one implementation behind both ways of
     * placing - clicking the cell, and dragging the card there and letting go.
     */
    private void spendSelectedCard(int cellX, int cellY) {
        SlotInfo selected = slotInfo(selectedCard);
        if (selected == null) {
            selectedCard = -1;
            return;
        }
        if (selected.kind().equals("tool")) {
            // One click, one action, and then the card is put back - the glove included. It
            // used to stay selected across its second click (lift, then drop), which read as
            // an infinite cooldown: the glove is on cooldown the moment it picks something up,
            // the bar greys a selected card that is not ready, and the second click was refused
            // by cardUsable() with the buzzer. The server still answers a drop that follows a
            // lift without charging the card again, so a two-click move still costs one use.
            // The gesture is played on the click and the answer is not waited for, the same
            // rule the level's own tool follows (see swingDefaultToolCursor).
            swingDefaultToolCursor();
            client.connection().send(new UseToolC2S(selectedCard, cellX, cellY));
            selectedCard = -1;
            return;
        }
        // Which placement message a card sends is the card's kind, not the level's: a zombie card
        // is placed on the same lawn by the same click, and only the registry the id is looked up
        // in differs (see PlaceZombieC2S).
        if (selected.kind().equals("zombie")) {
            client.connection().send(
                    new com.pvzce.common.network.packet.PlaceZombieC2S(selectedCard, cellX, cellY));
        } else {
            client.connection().send(new PlacePlantC2S(selectedCard, cellX, cellY));
        }
        selectedCard = -1;
    }

    /** Sends one pickup request, once per drop, so a sweep does not spam the server. */
    private void collectDrop(ClientEntity drop) {
        if (drop.id() == lastSweptDropId) {
            return;
        }
        lastSweptDropId = drop.id();
        if (levelHints != null) {
            if (!clickedAnyDrop) {
                // The first click anywhere on a pickup is the gesture the opening lesson
                // asked for, so that lesson has done its job whether or not the drop was
                // the one it named.
                clickedAnyDrop = true;
                hints.hide();
            }
            levelHints.onResourceCollected(drop.defIdString());
        }
        client.connection().send(new CollectResourceC2S(drop.id()));
    }

    /**
     * The cursor moving with the button held: sweep up pickups, or carry a card.
     *
     * <p>Collecting used to be one click = one drop, which is the wrong shape for a board
     * that can hold five suns at once - the player knows what they want, and making them
     * click each one is busywork. Holding the button down and passing over them is the same
     * request per drop, just without the aiming.
     */
    @Override
    protected void onMouseDragged(double guiX, double guiY, int button) {
        if (button != 0 || !client.level().gameState().equals("running")) {
            return;
        }
        if (draggingCard >= 0) {
            return; // Carrying a card: the release decides where it lands.
        }
        if (!sweeping) {
            return;
        }
        ClientEntity drop = resourceDropAt(rawMouseX(guiX), rawMouseY(guiY));
        if (drop != null) {
            collectDrop(drop);
        }
    }

    /**
     * The button coming back up: drop a carried card onto the cell under the cursor.
     *
     * <p>A release that is not over a plantable cell keeps the card selected instead of
     * cancelling it, so the drag gesture degrades into the click-then-click one rather than
     * quietly losing the player's choice.
     */
    @Override
    protected void onMouseReleased(double guiX, double guiY, int button) {
        cancelMowerHold();
        sweeping = false;
        lastSweptDropId = -1;
        int carried = draggingCard;
        draggingCard = -1;
        if (button != 0 || carried < 0 || !client.level().gameState().equals("running")) {
            return;
        }
        if (selectedCard != carried || slotInfo(carried) == null) {
            return;
        }
        PvzceCamera camera = client.camera();
        double rawX = rawMouseX(guiX);
        double rawY = rawMouseY(guiY);
        if (!camera.inBoard(rawX, rawY)) {
            return;
        }
        int cellX = camera.cellX(rawX, rawY);
        int cellY = camera.cellY(rawX, rawY);
        if (cellX < 0 || cellX >= client.level().width() || cellY < 0 || cellY >= client.level().height()) {
            return;
        }
        spendSelectedCard(cellX, cellY);
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
        return cardBar().slotAt(mouseX, guiY);
    }

    @Override
    protected void onMouseScrolled(double guiX, double guiY, double amount) {
        // No modal check here: Screen.mouseScrolled already routed a modal dialog
        // before calling this hook.
        if (cardBarScrollsAt(guiX, guiY)) {
            cardBar().scroll(guiX, guiY, amount);
        }
    }

    /**
     * True when the card row is the thing under this point, and there is more of it than fits.
     *
     * <p>One predicate for two callers - the wheel and the touch gesture layer - because they have to
     * agree: a row that the wheel scrolls must be a row a swipe scrolls, and a row that fits (or a
     * conveyor, which deals everything onto the screen) must stay an ordinary click target.
     */
    private boolean cardBarScrollsAt(double guiX, double guiY) {
        return client.level().gameState().equals("running")
                && cardBar().scrollStep() > 0
                && cardBar().contains(guiX, guiY);
    }

    @Override
    protected ScrollRegion onScrollRegionAt(double guiX, double guiY) {
        if (!cardBarScrollsAt(guiX, guiY)) {
            return null;
        }
        // The row runs left to right, so dragging it right and wheeling up both mean "show the cards
        // before these" - the two readings coincide on a horizontal region.
        return ScrollRegion.dragsContent(ScrollRegion.Axis.HORIZONTAL, cardBar().scrollStep());
    }

    /**
     * A bound key, offered to the level before the screen's ordinary key handling.
     *
     * <p>Only the level can answer these: a tool hotkey means "use this tool on the cell under the
     * cursor", which needs the board, and the chat line is the level's own overlay. Everything else
     * returns false and carries on to {@link #keyPressed}.
     *
     * @return true when the level acted on the key
     */
    public boolean keyAction(com.pvzce.client.input.KeyBindings.Action action, int code) {
        if (action == null) {
            return false;
        }
        if (action.isRhythmLane()) {
            return playRhythmLane(action.rhythmLaneKind(), action.rhythmLaneIndex());
        }
        if (action.isTool()) {
            return useToolHotkey(action.toolId());
        }
        if (action == com.pvzce.client.input.KeyBindings.Action.CHAT) {
            client.openChat();
            return true;
        }
        return false;
    }

    /**
     * A tool hotkey: use that tool on the cell under the cursor, right now.
     *
     * <p><b>The key is the tool's, not the bar's.</b> "1 is the shovel" in every level, whether the
     * shovel is the first card, the fifth or absent - a key that meant a different tool in every
     * level would be worse than no key at all. The three refusals are the click path's own: no such
     * card in this level's bar, the card is not usable, or the cursor is not over the board. The
     * first two buzz and shake the card exactly as a refused click does, because "I pressed it and
     * nothing happened" is the report that follows silence.
     *
     * <p>A tool that is not in this level's bar at all is consumed quietly: the key belongs to the
     * tool, the tool is not here, and buzzing at a card that is not on screen would have nothing to
     * shake.
     */
    private boolean useToolHotkey(String toolId) {
        // Development diagnostic, the same one the click path uses (`-Dpvzce.traceInput=true`):
        // every refusal below is silent by design, so without this "I pressed it and nothing
        // happened" has four indistinguishable causes - the wrong screen, a bar without that tool,
        // a card that is not ready, and a cursor that is not over the board.
        boolean trace = Boolean.getBoolean("pvzce.traceInput");
        if (toolId == null || !client.level().gameState().equals("running") || paused) {
            if (trace) {
                System.out.println("[SMOKE] tool " + toolId + " ignored: state="
                        + client.level().gameState() + " paused=" + paused);
            }
            return true;
        }
        int slot = -1;
        // The bar's *tool* cards, which on a belt are the ones it keeps beside the tray: asking
        // `slots()` would answer "no such card" exactly where clicking the drawn shovel works.
        for (SlotInfo info : cardBar().toolSlots()) {
            if (toolId.equals(info.defId())) {
                slot = info.index();
                break;
            }
        }
        if (slot < 0) {
            if (trace) {
                System.out.println("[SMOKE] tool " + toolId + " is not in this bar: "
                        + cardBar().toolSlots().stream().map(SlotInfo::defId).toList());
            }
            return true;
        }
        SlotInfo card = slotInfo(slot);
        if (card == null || (!cardUsable(card) && !gloveCard(card))) {
            if (trace) {
                System.out.println("[SMOKE] tool " + toolId + " slot " + slot
                        + " is not usable: " + card);
            }
            refuseCard(slot);
            return true;
        }
        int[] cell = cursorCell();
        if (trace) {
            System.out.println("[SMOKE] tool " + toolId + " slot=" + slot + " cell="
                    + (cell == null ? "off-board" : cell[0] + "," + cell[1]));
        }
        if (cell == null) {
            // Off the board: the tool stays in hand, exactly as a click outside the lawn leaves
            // the selected card alone. The key is still consumed - it named a tool, not a card.
            return true;
        }
        selectedCard = slot;
        spendSelectedCard(cell[0], cell[1]);
        return true;
    }

    /** The cell under the cursor, or {@code null} when the cursor is not over the board. */
    private int[] cursorCell() {
        PvzceCamera camera = client.camera();
        // The window's cursor is already in framebuffer pixels, which is the space the camera
        // hit-tests in - the same two numbers `onMouseReleased` converts a GUI click into.
        double rawX = client.window().cursorX();
        double rawY = client.window().cursorY();
        if (!camera.inBoard(rawX, rawY)) {
            return null;
        }
        int cellX = camera.cellX(rawX, rawY);
        int cellY = camera.cellY(rawX, rawY);
        if (cellX < 0 || cellX >= client.level().width()
                || cellY < 0 || cellY >= client.level().height()) {
            return null;
        }
        return new int[] {cellX, cellY};
    }

    @Override
    public void keyPressed(int key) {
        Dialog modal = modalDialog();
        if (modal != null && modal != pauseDialog) {
            modal.keyPressed(key);
            return;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (!client.level().gameState().equals("running")) {
                // ESC leaves a finished level from anywhere in the end sequence: the clicks
                // are held back so the defeat can be watched, but a key that means "get me
                // out" must not be.
                client.leaveLevel();
            } else if (pauseDialog != null && pauseDialog.isVisible()) {
                closePause();
            } else {
                openPause();
            }
            return;
        }
        super.keyPressed(key);
    }

    /**
     * The run's numbers as the server last sent them, or {@code null} before the first message.
     *
     * <p>The message carries the chart's anchor - the level tick its first note counts from - and
     * it is the server's own tick, so the client's notes and the server's judgement are counting
     * from the same place. Deriving the moment here instead (the build phase ending) was out by the
     * whole build phase: the client's mirror of that phase is a packet behind, and false before the
     * first packet arrives.
     *
     * <p>Beside the anchor ride the energy bar, the PERFECT streak and the jalapeno count, for the
     * reason a mechanic streams under one shape at all (see {@code RhythmMechanic.Status}).
     */
    private com.pvzce.common.level.mechanic.RhythmMechanic.Status rhythmStatus() {
        return client.level().mechanicStateOrNull(PvzceIds.MECHANIC_RHYTHM,
                com.pvzce.common.level.mechanic.RhythmMechanic.Status.class);
    }

    /**
     * The rhythm levels' ten lanes: one key, one note, one attack.
     *
     * <p>The judgement happens here and the score happens on the server; this method's whole job is
     * to turn a key into "which note was that, and how far off was it" and to say so. A press that
     * is nowhere near a note sends nothing at all - a stray key is not a mistake, and a client that
     * reported every press would be filling the server's miss counter with the player's typing.
     *
     * <p>The lane is lit <em>before</em> that judgement, and whether or not there is a note under
     * it: the flash answers "did my key register", which is a question a stray press asks just as
     * loudly as a hit does (see {@code RhythmPlay#pressed}).
     */
    private boolean playRhythmLane(String laneKind, int laneIndex) {
        if (rhythm == null || !client.level().gameState().equals("running") || paused) {
            // The keys are always bound, so they arrive in every level; only a running rhythm
            // level has anything to do with them.
            return rhythm != null;
        }
        rhythm.tick(client.level().smoothLevelTicks(), rhythmStatus());
        rhythm.pressed(laneKind, laneIndex);
        var packet = rhythm.press(laneKind, laneIndex, client.level().smoothLevelTicks());
        if (packet != null) {
            client.connection().send(packet);
        }
        return true;
    }

    /**
     * Draws the chart's own HUD: the tally, and - for a row chart - the verdict.
     *
     * <p>A column chart's verdict belongs on the judgement line, so it is drawn there instead (see
     * {@link #renderRhythmHighway}); what is left here is how the run is going.
     */
    /** How far apart the rhythm HUD's lines sit, in GUI units. */
    private static final float RHYTHM_HUD_LINE = 14F;
    /** The verdict's resting size; the pop-in and the fade are its own curves (see `RhythmPlay`). */
    private static final float RHYTHM_VERDICT_SCALE = 1.5F;
    /** The energy bar's width and the tallest it may grow, in GUI units. */
    private static final float RHYTHM_ENERGY_BAR_WIDTH = 18F;
    private static final float RHYTHM_ENERGY_BAR_MAX_HEIGHT = 200F;
    /**
     * Where the energy bar hangs from, as a share of the window's height, and how tall it is.
     *
     * <p>Anchored from the top rather than stacked under the streak line: the right edge of this
     * HUD holds the pause button at the very top and the level-progress badge at the very bottom,
     * and a gauge that is pinned to the text above it grows into whichever of the two is in the way.
     * 0.78 leaves the button its corner and 0.34 of the height is a bar long enough to read a
     * three-thousand-point gate off without reaching the wave meter.
     */
    private static final float RHYTHM_ENERGY_BAR_TOP = 0.78F;
    private static final float RHYTHM_ENERGY_BAR_HEIGHT = 0.34F;
    /** What the streak's reward is called, on screen. */
    private static final String JALAPENO_BANNER_TEXT = "火爆辣椒！";
    /** How long the reward's banner stays up, in nanoseconds. */
    private static final long JALAPENO_BANNER_NANOS = 1_200_000_000L;
    /**
     * Where the row keys sit, in world cells: inside the board's left edge.
     *
     * <p>Not <em>outside</em> it, which is where the eye looks first and where the first attempt at
     * this put them. The lawn's left edge is the house, and the 1400x600 backdrop is scaled to
     * <em>cover</em> the window - at 16:9 that crops the house away almost entirely, so a badge at
     * world x {@code -1.35} was drawn thirty pixels off the left of the screen and the smoke
     * screenshot showed an empty lawn. The parked mower ({@code MowerMechanic.IDLE_X}) takes the
     * half cell that is left, so the row's own left end is the only place a letter can sit and be
     * read at every window size - which is also the thing the badge is supposed to say: this key,
     * this row.
     */
    private static final float RHYTHM_KEY_ROW_X = 0.30F;
    /**
     * Where the judgement line sits, in world cells: inside the board's bottom row.
     *
     * <p><b>Inside</b> it, and that is the whole report this answers. The keys used to hang in the
     * strip below the board, which is where they belong in a rhythm game - and the strip is not
     * there: the 1400x600 backdrop is scaled to <em>cover</em> the window, so at 1920x1080 the
     * lawn's own bottom edge lands 36 pixels above the bottom of the screen (a fifth of a cell),
     * and the hint box owns most of that. A line plus a key capsule needs forty pixels. So the
     * judgement line goes on the lawn's bottom row and the keys go under it, which is also why the
     * old row-mode hints were inside the board's left edge for the same reason (see
     * {@link #RHYTHM_KEY_ROW_X}).
     */
    private static final float RHYTHM_JUDGE_Y = 0.68F;
    /**
     * Where the column keys sit, in world cells: under the judgement line, in the bottom row.
     *
     * <p>The half of the report that was about <em>place</em>: the keys used to be a column of
     * letters down the lawn's left edge, and the notes come <em>down</em> - so the thing that
     * answers them has to be under them, at the bottom of their own column.
     */
    private static final float RHYTHM_KEY_COL_Y = 0.36F;
    /**
     * How far above the board's top row a note appears, in cells.
     *
     * <p>Above the lawn rather than on it: a note that popped into existence inside the top row
     * would have no visible start, and the flight is what tells the player how long they have.
     */
    private static final float RHYTHM_FLIGHT_HEADROOM = 0.25F;
    /** Capsule size as a fraction of the lane's own thickness, clamped to something readable. */
    private static final float RHYTHM_KEY_LANE_FRACTION = 0.42F;
    /** The flying note is a little smaller than the key it is aimed at, so the cap stays readable. */
    private static final float RHYTHM_NOTE_LANE_FRACTION = 0.34F;
    private static final float RHYTHM_KEY_MIN = 16F;
    private static final float RHYTHM_KEY_MAX = 34F;

    /**
     * The lane keys, the judgement line and the notes in the air.
     *
     * <p>A letter under each column is the whole of what tells the player which key plays which
     * lane: the mode's keys are bindings away in a settings page nobody opens mid-level. Read from
     * the live table, so a player who rebound one reads their own letter, and an unbound lane draws
     * nothing at all - the honest answer to "which key plays this".
     *
     * <p><b>The highway is the answer to "when and where do I press".</b> A note is drawn as the
     * capsule of the key that answers it, flying down its own column from just above the lawn to
     * the judgement line, and it takes the chart's {@code approach_ticks} to get there - which is
     * the same number the windows are fractions of (see {@code RhythmChartData}). So the picture
     * and the judgement are the same fact twice: when the note is on the line, the press is a
     * PERFECT, and the player can see that without knowing a tick from a millisecond.
     *
     * <p>Only the lanes the chart actually uses, and only the notes still unplayed: a letter for a
     * lane with no notes would be a key the player learns and never needs, and a note that has been
     * played or missed is gone from the air.
     */
    private void renderRhythmHighway(com.pvzce.client.renderer.PvzceCamera camera, float guiScale) {
        if (rhythm == null) {
            return;
        }
        boolean columnChart = false;
        for (com.pvzce.api.content.RhythmChartData.Lane lane : rhythm.chart().lanes()) {
            if (lane.kind() == com.pvzce.api.content.RhythmChartData.LaneKind.COL) {
                columnChart = true;
                break;
            }
        }
        float judgeY = camera.screenY(RHYTHM_JUDGE_Y) / guiScale;
        for (com.pvzce.api.content.RhythmChartData.Lane lane : rhythm.chart().lanes()) {
            if (lane.notes().isEmpty()) {
                continue;
            }
            com.pvzce.client.input.KeyBindings.Action action =
                    com.pvzce.client.input.KeyBindings.Action.forLane(lane.kind().json(), lane.index());
            if (action == null) {
                continue;
            }
            String key = com.pvzce.client.input.KeyBindings.keyName(client.keyBindings().code(action));
            if (key.isEmpty()) {
                continue;
            }
            boolean row = lane.kind() == com.pvzce.api.content.RhythmChartData.LaneKind.ROW;
            float laneSize = (row ? camera.unitY() : camera.unitX()) / guiScale;
            float height = MathUtil.clamp(laneSize * RHYTHM_KEY_LANE_FRACTION,
                    RHYTHM_KEY_MIN, RHYTHM_KEY_MAX);
            float scale = height / RHYTHM_HUD_LINE;
            float textWidth = client.fonts().body().width(key, scale);
            float width = Math.max(height, textWidth + height * 0.6F);
            boolean hot = rhythm.justPlayed(lane.kind().json(), lane.index());
            // A key that was just pressed, whether or not it answered anything, and the light that
            // says so (see `RhythmPlay#pressed`). Read separately from `hot`: `hot` is the answer to
            // "did that note land", and this is "did my key register".
            float flash = rhythm.pressFlash(lane.kind().json(), lane.index());
            com.pvzce.api.content.RhythmChartData.Grade flashGrade =
                    rhythm.pressedGrade(lane.kind().json(), lane.index());
            if (row) {
                // A row chart keeps the letters on the left edge of its rows. No shipped tier plays
                // this way any more; the mechanic still supports it, and a hand-written chart should
                // not be told its keys are somewhere else.
                float centerX = camera.screenX(RHYTHM_KEY_ROW_X) / guiScale;
                float centerY = camera.screenY(lane.index() + 0.5F) / guiScale;
                drawRhythmCapsule(centerX, centerY, width, height, key, scale, hot, false,
                        flash, flashGrade);
                continue;
            }
            float centerX = camera.screenX(lane.index() + 0.5F) / guiScale;
            // The lane itself: a faint thread from the line to where the notes appear, so the eye
            // can follow a capsule down without the lawn being painted over.
            client.drawSolid(centerX - 1F, judgeY, 2F,
                    camera.screenY(client.level().height() + RHYTHM_FLIGHT_HEADROOM) / guiScale - judgeY,
                    0.51F, 0.9F, 0.85F, 0.45F, 0.10F);
            // The judgement marker: the line the note has to be on. Bright while this lane is the
            // one that was just played, so the answer and the question are drawn in one place -
            // and it is the key press, not the hit, that lights it: a press that answered nothing
            // is the one the player is squinting at.
            float[] lineColour = rhythmLaneColour(flashGrade, hot, flash);
            client.drawSolid(centerX - laneSize / 2F, judgeY - 2F, laneSize, 4F, 0.52F,
                    lineColour[0], lineColour[1], lineColour[2], lineColour[3]);
            // The lane's own glow, under the marker and wider than it, for the moment after a
            // press: a four-pixel line is easy to miss while the eye is chasing a note.
            if (flash > 0F) {
                client.drawSolid(centerX - laneSize / 2F, judgeY - 6F, laneSize, 12F, 0.515F,
                        lineColour[0], lineColour[1], lineColour[2], 0.30F * flash);
            }
            drawRhythmCapsule(centerX, camera.screenY(RHYTHM_KEY_COL_Y) / guiScale, width, height, key,
                    scale, hot, false, flash, flashGrade);
        }
        // The notes, over everything: they are the thing the player is reading.
        double topY = client.level().height() + RHYTHM_FLIGHT_HEADROOM;
        for (com.pvzce.client.RhythmPlay.Flight flight : rhythm.inFlight()) {
            if (!"col".equals(flight.laneKind())) {
                continue;
            }
            com.pvzce.client.input.KeyBindings.Action action =
                    com.pvzce.client.input.KeyBindings.Action.forLane(flight.laneKind(),
                            flight.laneIndex());
            if (action == null) {
                continue;
            }
            String key = com.pvzce.client.input.KeyBindings.keyName(client.keyBindings().code(action));
            if (key.isEmpty()) {
                continue;
            }
            float centerX = camera.screenX(flight.laneIndex() + 0.5F) / guiScale;
            // Down to the line, not up from it: the flight's own arithmetic, so that the picture
            // and the judgement cannot disagree about which way time runs (`RhythmPlay.Flight`).
            float worldY = (float) flight.worldY(rhythm.chart().approachTicks(), topY, RHYTHM_JUDGE_Y);
            float centerY = camera.screenY(worldY) / guiScale;
            float laneSize = camera.unitX() / guiScale;
            float height = MathUtil.clamp(laneSize * RHYTHM_NOTE_LANE_FRACTION,
                    RHYTHM_KEY_MIN * 0.8F, RHYTHM_KEY_MAX * 0.9F);
            float scale = height / RHYTHM_HUD_LINE;
            float textWidth = client.fonts().body().width(key, scale);
            float width = Math.max(height, textWidth + height * 0.6F);
            // On the line is the moment that counts, so the note whitens as it arrives: a player
            // who presses when it is white is inside the perfect window by construction.
            boolean onLine = Math.abs(flight.ticksAhead()) <= rhythm.chart().perfectTicks();
            drawRhythmCapsule(centerX, centerY, width, height, key, scale, onLine, true, 0F, null);
        }
        if (columnChart && !rhythm.visibleVerdict().isEmpty()) {
            // Just above the line, where the player is looking. The tally is off to the right
            // (see `renderRhythmHud`); this is the answer to the press they just made.
            drawRhythmVerdict(rhythm.visibleVerdict(), rhythm.visibleGrade(),
                    client.guiWidth() / 2F, judgeY + RHYTHM_HUD_LINE * 2F, RHYTHM_VERDICT_SCALE);
        }
    }

    /**
     * The verdict word, in the display face and with its own weight behind it.
     *
     * <p>{@link #drawRhythmCapsule}'s twin for the one string in this HUD that is a reaction rather
     * than a readout, and it is drawn differently on purpose: 站酷快乐体 instead of 思源黑体
     * ({@code client.fonts().button()}), lit at {@link RhythmPlay#verdictScale()} and drifting on
     * {@link RhythmPlay#verdictRise()}. The face is the house's display one - the same one the sun
     * counter's numbers and the wave banners use - so "PERFECT" landing on the judgement line looks
     * like the game's own voice rather than like a debug label; the size and the drift are the
     * impact.
     *
     * <p>An outline rather than a shadow, because the word lands on the lawn: a black halo keeps a
     * gold PERFECT legible over grass, a zombie and a flame at once, which a drop shadow does not.
     */
    private void drawRhythmVerdict(String verdict, com.pvzce.api.content.RhythmChartData.Grade grade,
                                   float centerX, float baseline, float baseScale) {
        float[] colour = verdictColour(grade);
        float scale = baseScale * rhythm.verdictScale();
        float alpha = rhythm.verdictAlpha();
        float y = baseline + rhythm.verdictRise();
        com.pvzce.client.renderer.font.TextStyle outline =
                com.pvzce.client.renderer.font.TextStyle.outline(0.03F, 0.02F, 0.02F, 0.9F * alpha, 1.6F);
        client.fonts().button().drawCentered(verdict, centerX, y, scale,
                colour[0], colour[1], colour[2], alpha, outline);
        // How many PERFECTs in a row this one makes, to the word's right - the number the whole mode
        // is played for, written where the player is already looking rather than only in the corner
        // of the screen. From two: the first one is not a streak, and a "×1" that appears and
        // disappears on every other note is noise where the eye is chasing a moving note.
        //
        // LEFT-aligned off the word's right edge rather than centred on some fraction of the line,
        // so it rides the pop-in with the word instead of sliding under it: the word grows about its
        // own centre, and an anchor that ignored that would have the badge drift into it on the
        // frame that matters most.
        if (grade == com.pvzce.api.content.RhythmChartData.Grade.PERFECT && rhythm.streak() >= 2) {
            String badge = "×" + rhythm.streak();
            float badgeScale = scale * 0.62F;
            float wordWidth = client.fonts().button().width(verdict, scale);
            float baselineShift = (scale - badgeScale) * RHYTHM_HUD_LINE * 0.5F;
            client.fonts().button().draw(badge,
                    centerX + wordWidth / 2F + RHYTHM_HUD_LINE * 0.35F,
                    y + baselineShift, badgeScale, colour[0], colour[1], colour[2],
                    alpha * 0.95F, outline);
        }
    }

    /**
     * The colour the lane's judgement marker and key cap are lit in.
     *
     * <p>Three states, in order of what the player most needs to know: a note that was just played
     * takes its verdict's colour (gold, blue, grey - see {@link #verdictColour}); a key that was
     * just pressed and answered nothing flashes a neutral white, which is the whole of "I saw your
     * key, there was nothing there"; and a lane nobody has touched is the dim amber it always was.
     *
     * <p>{@code flash} is the press's own fade, used as the alpha so the light goes out rather than
     * switching off.
     */
    private static float[] rhythmLaneColour(com.pvzce.api.content.RhythmChartData.Grade flashGrade,
                                            boolean hot, float flash) {
        if (hot) {
            float[] colour = verdictColour(flashGrade == null ? rhythmGradeFallback() : flashGrade);
            return new float[] {colour[0], colour[1], colour[2], 0.95F};
        }
        if (flash > 0F) {
            return new float[] {0.92F, 0.95F, 1F, 0.35F + 0.55F * flash};
        }
        return new float[] {0.55F, 0.5F, 0.2F, 0.9F};
    }

    /**
     * The verdict a lit lane falls back to when it was a hit whose grade the client no longer has.
     *
     * <p>Unreachable in practice - {@code justPlayed} and {@code pressedGrade} are both cleared by
     * the same timer - but the alternative is a null check at the call site, and a lane lit in the
     * wrong colour for one frame beats a crash in the render loop.
     */
    private static com.pvzce.api.content.RhythmChartData.Grade rhythmGradeFallback() {
        return com.pvzce.api.content.RhythmChartData.Grade.PERFECT;
    }

    /** One capsule: a dark rim, a filled cap and the letter on it. */
    private void drawRhythmCapsule(float centerX, float centerY, float width, float height, String key,
                                   float scale, boolean hot, boolean note, float flash,
                                   com.pvzce.api.content.RhythmChartData.Grade flashGrade) {
        // The white letters were all that showed in the first version of this, because the fifth
        // argument of `drawSolid` is z, not a corner radius: passing a radius-sized number there
        // draws the cap at a depth nothing else uses, which is to say nowhere.
        float[] pressedColour = rhythmLaneColour(flashGrade, false, flash);
        float fillR = hot ? 1F : note ? 0.95F : 0.17F;
        float fillG = hot ? 0.87F : note ? 0.78F : 0.17F;
        float fillB = hot ? 0.2F : note ? 0.18F : 0.23F;
        if (flash > 0F && !hot) {
            // A pressed lane's cap takes the flash's own colour, so the cap and the judgement line
            // above it are one light. Mixed toward the flash by how bright it still is, so the cap
            // settles back to its resting grey as the flash fades instead of snapping.
            fillR = fillR + (pressedColour[0] - fillR) * flash;
            fillG = fillG + (pressedColour[1] - fillG) * flash;
            fillB = fillB + (pressedColour[2] - fillB) * flash;
        }
        float rimR = note ? 0.12F : 0.05F;
        float rimG = note ? 0.1F : 0.05F;
        float rimB = note ? 0.04F : 0.06F;
        client.drawSolid(centerX - width / 2F - 2F, centerY - height / 2F - 2F,
                width + 4F, height + 4F, 0.52F, rimR, rimG, rimB, 0.85F);
        client.drawSolid(centerX - width / 2F, centerY - height / 2F, width, height,
                0.53F, fillR, fillG, fillB, 0.95F);
        client.fonts().body().drawCentered(key, centerX,
                centerY - client.fonts().body().ascent(scale) / 2F, scale,
                hot || note ? 0.12F : 0.97F, hot || note ? 0.1F : 0.96F,
                hot || note ? 0.08F : 0.9F, 1F);
    }

    /** How a verdict is drawn: gold, blue, grey and red for the four of them. */
    private static float[] verdictColour(com.pvzce.api.content.RhythmChartData.Grade grade) {
        return switch (grade) {
            case PERFECT -> new float[] {1F, 0.9F, 0.25F};
            case GOOD -> new float[] {0.55F, 0.9F, 1F};
            case FAIR -> new float[] {0.85F, 0.85F, 0.85F};
            case MISS -> new float[] {1F, 0.35F, 0.3F};
        };
    }

    private void renderRhythmHud() {
        if (rhythm == null) {
            return;
        }
        float width = client.guiWidth();
        float height = client.guiHeight();
        // The lane keys and the flying notes are drawn by `render`, not here: they belong over the
        // dragged card's placement preview (see the call site).
        boolean columnChart = !rhythm.chart().lanes().isEmpty()
                && rhythm.chart().lanes().get(0).kind()
                        == com.pvzce.api.content.RhythmChartData.LaneKind.COL;
        float right = width - 16F;
        if (!rhythm.visibleVerdict().isEmpty() && !columnChart) {
            // A row chart has no judgement line to hang the verdict on, so it keeps the middle of
            // the screen; a column chart's is drawn on its own line (see `renderRhythmHighway`).
            drawRhythmVerdict(rhythm.visibleVerdict(), rhythm.visibleGrade(),
                    width / 2F, client.guiHeight() * 0.62F, RHYTHM_VERDICT_SCALE);
        }
        // The tally, down the right-hand side: every verdict the run has had, misses included -
        // the mode tells the player what they failed to press, not only what they hit. The server's
        // counts and not this side's: the two sides grade a press slightly differently by design,
        // and a line drawn from one of them beside a streak drawn from the other reads as a
        // contradiction rather than as a lag (see `RhythmPlay`).
        com.pvzce.client.RhythmPlay.Tally tally = rhythm.tally();
        String tallyLine = "PERFECT " + tally.perfect() + " · GOOD " + tally.good()
                + " · FAIR " + tally.fair() + " · MISS " + tally.missed();
        client.fonts().body().draw(tallyLine, right - client.fonts().body().width(tallyLine, 0.9F),
                height * 0.38F, 0.9F, 1F, 1F, 1F, 1F);
        String combo = "连击 " + tally.combo() + " · 最高 " + tally.bestCombo();
        client.fonts().body().draw(combo, right - client.fonts().body().width(combo, 0.9F),
                height * 0.38F - RHYTHM_HUD_LINE, 0.9F, 1F, 0.9F, 0.45F, 1F);
        // The streak, in the display face and larger than the two lines above it: this is the
        // number the rewards are named after, and it is the one the player is counting.
        String streak = "连续 PERFECT ×" + rhythm.streak();
        client.fonts().button().draw(streak,
                right - client.fonts().button().width(streak, 1.3F),
                height * 0.38F - RHYTHM_HUD_LINE * 2.4F, 1.3F,
                rhythm.streak() > 0 ? 1F : 0.75F, rhythm.streak() > 0 ? 0.9F : 0.75F,
                rhythm.streak() > 0 ? 0.3F : 0.75F, 1F);
        renderEnergyBar(right);
    }

    /**
     * The energy bar: a vertical gauge down the right edge, with the two gates drawn on it.
     *
     * <p>Vertical because of where it lives: the right-hand side of this HUD is already a column of
     * lines (the tally, the combo, the streak), and a bar under them that ran the other way would
     * be the one horizontal thing in a vertical list. Downward because it drains - the level falls,
     * which is what the player is watching.
     *
     * <p>The gates are drawn as ticks at their own heights rather than only as a colour change,
     * because the number that matters is not "how full" but "am I above the line that doubles my
     * fire" - a player defending 3000 needs to see 3000. The fill's colour is the firepower it is
     * buying, so the bar answers the question it exists for at a glance.
     *
     * <p>It hangs from {@link #RHYTHM_ENERGY_BAR_TOP} rather than standing on the text below it, and
     * that is the whole report the first film produced: anchored to the streak line's underside it
     * grew <em>downward off the bottom of the screen</em>, and the only thing the screenshot showed
     * was the top two inches of track behind the level-progress badge. A gauge is read against the
     * top of what it is next to, so the top is the number that is fixed and the height is what gives.
     *
     * @param right the right-hand margin the HUD's lines are aligned to
     */
    private void renderEnergyBar(float right) {
        float barWidth = RHYTHM_ENERGY_BAR_WIDTH;
        float top = client.guiHeight() * RHYTHM_ENERGY_BAR_TOP;
        float barHeight = Math.max(40F, Math.min(RHYTHM_ENERGY_BAR_MAX_HEIGHT,
                client.guiHeight() * RHYTHM_ENERGY_BAR_HEIGHT));
        float x = right - barWidth;
        // `y` is the rect's lower edge: the bar hangs from `top` down to `top - barHeight`.
        float y = top - barHeight;
        int energy = rhythm.energy();
        float fraction = Math.max(0F, Math.min(1F, energy / (float) PvzceConstants.ENERGY_MAX));
        int multiplier = rhythm.multiplier();
        // The firepower the bar is paying for: amber at 1x, cold blue at 2x, hot red at 3x. A
        // colour rather than a word because it has to be readable while the player is watching the
        // judgement line, not the corner of the screen.
        float[] fill = multiplier >= 3 ? new float[] {1F, 0.35F, 0.3F}
                : multiplier == 2 ? new float[] {0.45F, 0.85F, 1F}
                : new float[] {1F, 0.78F, 0.25F};
        // The track, a shade darker than the plate the level list uses so it reads as a gauge.
        client.drawSolid(x - 2F, y - 2F, barWidth + 4F, barHeight + 4F, 0.14F, 0F, 0F, 0F, 0.55F);
        client.drawSolid(x, y, barWidth, barHeight, 0.15F, 0.16F, 0.17F, 0.2F, 0.9F);
        client.drawSolid(x, y, barWidth, barHeight * fraction, 0.16F, fill[0], fill[1], fill[2], 0.95F);
        // The two gates. `ENERGY_DOUBLE_AT` first, so the higher line is drawn over the lower one
        // where they would touch at a very short bar.
        drawEnergyGate(x, y, barWidth, barHeight, PvzceConstants.ENERGY_DOUBLE_AT, "×2");
        drawEnergyGate(x, y, barWidth, barHeight, PvzceConstants.ENERGY_TRIPLE_AT, "×3");
        String value = Integer.toString(energy);
        client.fonts().body().draw(value, right - client.fonts().body().width(value, 0.8F),
                top + 6F, 0.8F, 1F, 1F, 1F, 1F);
        if (multiplier > 1) {
            // Beside the bar's middle, on the lawn side, in the display face: it is the one number
            // on this HUD that is a statement about the plants rather than about the playing.
            String badge = "×" + multiplier;
            float badgeScale = 1.4F;
            client.fonts().button().draw(badge,
                    x - 8F - client.fonts().button().width(badge, badgeScale),
                    y + barHeight * 0.5F + client.fonts().button().ascent(badgeScale) / 2F,
                    badgeScale, fill[0], fill[1], fill[2], 1F);
        }
    }

    /** One gate's tick across the bar, with its own label on the far side. */
    private void drawEnergyGate(float x, float y, float barWidth, float barHeight, int gate,
                                String label) {
        float at = y + barHeight * (1F - gate / (float) PvzceConstants.ENERGY_MAX);
        boolean passed = rhythm.energy() >= gate;
        client.drawSolid(x, at - 1F, barWidth, 2F, 0.17F,
                1F, 1F, 1F, passed ? 0.95F : 0.5F);
        client.fonts().body().draw(label, x - 6F - client.fonts().body().width(label, 0.7F),
                at + client.fonts().body().ascent(0.7F) / 2F, 0.7F,
                passed ? 1F : 0.6F, passed ? 1F : 0.6F, passed ? 1F : 0.6F, 1F);
    }

    /**
     * Announces a jalapeno volley, once per reward the streak has paid.
     *
     * <p>Read off the running total rather than off a flag in the packet, so a message that arrived
     * late is still an announcement and one that was lost is caught by the next one (see
     * {@code RhythmMechanic.Status}). The banner is the mode's own voice, in the display face like
     * the verdict and the wave banners: five rows of fire is a thing that happened <em>to</em> the
     * player, not a number they were meant to read.
     */
    private void renderRhythmBanner() {
        if (rhythm == null) {
            return;
        }
        int paid = rhythm.jalapenos();
        if (paid != lastJalapenos) {
            if (paid > lastJalapenos) {
                jalapenoBannerNanos = System.nanoTime();
            }
            lastJalapenos = paid;
        }
        if (jalapenoBannerNanos == 0L) {
            return;
        }
        long age = System.nanoTime() - jalapenoBannerNanos;
        if (age >= JALAPENO_BANNER_NANOS) {
            return;
        }
        float progress = age / (float) JALAPENO_BANNER_NANOS;
        // The verdict's own curve, one size up: it lands large and settles, then fades where it
        // stands. A banner that drifted would be competing with the notes for the same attention.
        float scale = (progress < 0.14F ? 2.6F + (1.6F - 2.6F) * (progress / 0.14F) : 1.6F);
        float alpha = progress <= 0.55F ? 1F : Math.max(0F, (1F - progress) / 0.45F);
        client.fonts().button().drawCentered(JALAPENO_BANNER_TEXT, client.guiWidth() / 2F,
                client.guiHeight() * 0.72F, scale, 1F, 0.55F, 0.15F, alpha,
                com.pvzce.client.renderer.font.TextStyle.outline(0.05F, 0.01F, 0F, 0.9F * alpha, 1.8F));
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
        // dialog is open. The music is held with it - a paused game whose song plays on is the
        // one thing a player listens for and concludes is broken.
        client.connection().send(new PauseGameC2S(true));
        client.music().setPaused(true);
    }

    private void handlePauseDialogClosed() {
        if (!paused) {
            return;
        }
        paused = false;
        client.connection().send(new PauseGameC2S(false));
        client.music().setPaused(false);
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
