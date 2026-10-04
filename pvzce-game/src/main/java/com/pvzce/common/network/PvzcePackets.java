package com.pvzce.common.network;

import com.pvzce.common.network.packet.MechanicSyncS2C;
import com.pvzce.common.network.packet.CarrySyncS2C;
import com.pvzce.common.network.packet.CollectResourceC2S;
import com.pvzce.common.network.packet.CommandC2S;
import com.pvzce.common.network.packet.CreateWorldC2S;
import com.pvzce.common.network.packet.ContinueLevelC2S;
import com.pvzce.common.network.packet.PlayLevelC2S;
import com.pvzce.common.network.packet.RestartLevelC2S;
import com.pvzce.common.network.packet.BuyShopItemC2S;
import com.pvzce.common.network.packet.UnlockLevelC2S;
import com.pvzce.common.network.packet.DebugInfoS2C;
import com.pvzce.common.network.packet.EffectEventS2C;
import com.pvzce.common.network.packet.EntityDespawnS2C;
import com.pvzce.common.network.packet.EntitySpawnS2C;
import com.pvzce.common.network.packet.EntityUpdateS2C;
import com.pvzce.common.network.packet.EchoNetworkS2C;
import com.pvzce.common.network.packet.GameSpeedS2C;
import com.pvzce.common.network.packet.GameStateS2C;
import com.pvzce.common.network.packet.LeaveLevelC2S;
import com.pvzce.common.network.packet.LevelInitS2C;
import com.pvzce.common.network.packet.ReselectCardsC2S;
import com.pvzce.common.network.packet.StageChoiceC2S;
import com.pvzce.common.network.packet.OutpostStrikeC2S;
import com.pvzce.common.network.packet.RoundClearS2C;
import com.pvzce.common.network.packet.RoundSyncS2C;
import com.pvzce.common.network.packet.LevelListS2C;
import com.pvzce.common.network.packet.LevelSavePromptS2C;
import com.pvzce.common.network.packet.LevelRewardS2C;
import com.pvzce.common.network.packet.LevelTabsS2C;
import com.pvzce.common.network.packet.MusicEventS2C;
import com.pvzce.common.network.packet.OpenEditorS2C;
import com.pvzce.common.network.packet.PauseGameC2S;
import com.pvzce.common.network.packet.ProfileS2C;
import com.pvzce.common.network.packet.HeldCardS2C;
import com.pvzce.common.network.packet.PickCardC2S;
import com.pvzce.common.network.packet.PickUpCardC2S;
import com.pvzce.common.network.packet.PlantHeldCardC2S;
import com.pvzce.common.network.packet.ReleaseHeldCardC2S;
import com.pvzce.common.network.packet.PlacePlantC2S;
import com.pvzce.common.network.packet.RequestLevelListC2S;
import com.pvzce.common.network.packet.RequestProfileC2S;
import com.pvzce.common.network.packet.RequestSuggestionsC2S;
import com.pvzce.common.network.packet.ResourceCollectS2C;
import com.pvzce.common.network.packet.ResourceDeltaS2C;
import com.pvzce.common.network.packet.SceneSyncS2C;
import com.pvzce.common.network.packet.ServerMessageS2C;
import com.pvzce.common.network.packet.SetGameSpeedC2S;
import com.pvzce.common.network.packet.ChatC2S;
import com.pvzce.common.network.packet.SetDifficultyC2S;
import com.pvzce.common.network.packet.SlotSyncS2C;
import com.pvzce.common.network.packet.SuggestionsS2C;
import com.pvzce.common.network.packet.TeamSyncS2C;
import com.pvzce.common.network.packet.TimeOfDayS2C;
import com.pvzce.common.network.packet.ReleaseMowerC2S;
import com.pvzce.common.network.packet.ReloadPacksC2S;
import com.pvzce.common.network.packet.DiscardLevelSaveC2S;
import com.pvzce.common.network.packet.MutationStateS2C;
import com.pvzce.common.network.packet.SmashContainerC2S;
import com.pvzce.common.network.packet.UseGrantedToolC2S;
import com.pvzce.common.network.packet.UseToolC2S;
import com.pvzce.common.network.packet.WaveProgressS2C;
import java.util.List;
import java.util.function.Function;

/**
 * The protocol table: one row per packet, holding its id, direction, class and
 * decoder.
 *
 * <p>This is the only place a packet id is decided. Ids are grouped (client to
 * server below {@link #S2C_BASE}, server to client at or above it) and, once
 * shipped, must never be reused or renumbered - add the next free id in the
 * packet's direction instead. Client and server each call {@link #register()}
 * independently, so an id is the entire cross-process contract.
 */
public final class PvzcePackets {
    /**
     * Bumped whenever the wire format or the id table changes incompatibly. The
     * value travels in {@link LevelInitS2C} so a mismatched peer is reported
     * instead of silently misbehaving.
     */
    // 10: the resource entity kind is "resource" rather than "sun" - every drop used to
    // travel as a sun, which the client's board lights read as "light the lawn".
    // 12: entering a level is three packet types (continue / restart / play-with-these-cards)
    // instead of one "enter level" packet with a restart flag the server had to reinterpret.
    // 13: ProfileS2C carries the backpack's card-slot count.
    // 14: entity updates carry remaining armour (a Conehead's cone has three drawings and
    // the client picks one), and LevelRewardS2C carries the lawn mowers that survived the
    // level and what they were worth.
    // 15: ReleaseMowerC2S lets the player send a parked mower by hand instead of waiting for
    // a zombie to reach the house.
    // 16: entity spawn/update carry two more presentation fields: whether a zombie is
    // currently slowed (`chilled`, which the client draws as the frozen look) and a
    // per-entity scale multiplier (the small sun a small sun-shroom produces is the same
    // resource as the big one at a smaller size).
    // 17: the bar's recharge travels with its divisor (SlotInfo.cooldownTotal, so a level
    // that scales card cooldowns draws the sweep over the real recharge), the level list
    // carries whether a level was ever beaten (LevelInfo.cleared, which is what the row's
    // trophy reads), and the reward carries the resource a level handed over when it pays in
    // objects rather than in cards (LevelRewardS2C.rewardItem).
    // 27: endless runs are played in rounds. The wave meter's numbers became round-relative
    // (WaveProgressS2C gained the round), a level that generates its waves has to be told its
    // current round's wave list (RoundSyncS2C), and a finished round pauses the run for a card
    // choice (RoundClearS2C / ReselectCardsC2S).
    // 28: the mutation state carries the run's active level buffs (MutationStateS2C.activeBuffs).
    // A mutation is what rewrites that list mid-level, and the client used to hear it only once,
    // in the level init, so the buff icons kept showing what the run started with.
    // 30: the mutation state carries the bar slots a mutation locked (MutationStateS2C.lockedSlots),
    // so the bar can draw the padlock on exactly the cards the server refuses.
    // 31: the debug heartbeat carries the running level's own tick counter (DebugInfoS2C.levelTick),
    // which is what the tutorial's timed dialogue lines are written against.
    // 32: the client can ask the server to rebuild its side of the pack stack after the player
    // switched a pack off (ReloadPacksC2S), which is the first half of a hot reload.
    // 33: a click with nothing in hand on a vase or one of the vase level's pots is its own
    // request (SmashContainerC2S). The mallet stopped being a tool the level grants, so the
    // swing's target - not the tool - is what travels.
    // 34: a card a broken container held is a seed packet on the lawn rather than a card that
    // appeared in the bar. Picking one up (PickUpCardC2S) puts the plant in the player's hand,
    // which the client is told about (HeldCardS2C) and answers with its own two requests -
    // plant it (PlantHeldCardC2S) or put it back (ReleaseHeldCardC2S).
    // 35: the shop is reachable without opening the level list, and the profile used to travel
    // only with that list - so a fresh session drew a zero wallet until a level was started and
    // left. RequestProfileC2S is the client asking for the profile on its own.
    // 36: the award packet carries every card, buff and object a clear handed over, as a list,
    // rather than one field each - a clear can pay several, and the award page lists them all
    // with their own descriptions instead of describing the first and silently dropping the rest.
    // 37: the world's difficulty tier travels in the profile (for the level list's badge and the
    // settings page's tick) and can be switched with SetDifficultyC2S at any time.
    // 38: a key binding is a setting the player owns (the profile does not carry them: they are a
    // client preference and travel in pvzce-client.toml), and the chat line is its own packet -
    // ChatC2S - rather than an empty console command.
    // 39: the chat line got its own packet (ChatC2S).
    // 40: the preparation phase's start button (StartWavesC2S).
    // 41: a plant can be aimed by hand (FireAtC2S: which cannon, and which cell).
    // 42: the player can be on the zombie side, which places zombies rather than plants
    // (PlaceZombieC2S).
    // 43: the rhythm levels' keyboard (RhythmHitC2S: which lane, which note, how well).
    // 44: how far one card reaches travels with the level (LevelPayload.plantsWholeColumn), so the
    // placement preview can draw the column it is about to fill.
    // 45: a music cue can ask the client to preload instead of play (MusicEventS2C.preload), which
    // is how the level's song is decoded while it loads rather than nine ticks after it was due.
    // 48: authoritative lily network membership, charge and current haste.
    // 49: magnetic equipment transfers carry their origin and pull/recovery clocks.
    // 50: the human's chosen side travels with the level entry (PlayLevelC2S/RestartLevelC2S),
    //     and the client hands the server its Jev credential (AiSettingsC2S).
    // 54: fertilizer and attached ladders travel as PlantCareS2C.
    // 55: separate butter/freeze art and action sequence for exactly one gesture per volley.
    // 56: authoritative surface profiles, entity/effect elevations and selected operation surfaces.
    // 57: the HUD's next-wave button - the request (NextWaveC2S) and the server's answer to "may
    //     the wave be called" (WaveProgressS2C.nextWaveAvailable).
    // 58: a music cue can be a layer of a song rather than a song (MusicEventS2C.manyZombiesLayer):
    //     the night roof's drum track rides with its theme and is faded in when the lawn fills up.
    // 59: staged choices and outpost strikes; wave entries retain their authored surface.
    public static final int PROTOCOL_VERSION = 59;

    /** Server-to-client ids start here; everything below is client-to-server. */
    public static final int S2C_BASE = 64;

    // ---- client -> server ----
    public static final int C2S_STAGE_CHOICE = 36;
    public static final int C2S_OUTPOST_STRIKE = 37;
    public static final int C2S_CONTINUE_LEVEL = 1;
    public static final int C2S_PLAY_LEVEL = 2;
    public static final int C2S_RESTART_LEVEL = 3;
    public static final int C2S_REQUEST_LEVEL_LIST = 4;
    public static final int C2S_REQUEST_SUGGESTIONS = 5;
    public static final int C2S_LEAVE_LEVEL = 6;
    public static final int C2S_PICK_CARD = 7;
    public static final int C2S_PLACE_PLANT = 8;
    public static final int C2S_USE_TOOL = 9;
    public static final int C2S_COLLECT_RESOURCE = 10;
    public static final int C2S_COMMAND = 11;
    public static final int C2S_SET_GAME_SPEED = 12;
    public static final int C2S_PAUSE_GAME = 13;
    public static final int C2S_CREATE_WORLD = 14;
    public static final int C2S_UNLOCK_LEVEL = 15;
    // 16 was MovePlantC2S. The glove moves a plant through the tool path (UseToolC2S ->
    // LevelServer.applyToolEffect), so the packet had no sender and no handler; the id is
    // retired rather than reused, so an old client cannot land on a different packet.
    public static final int C2S_RELEASE_MOWER = 17;
    public static final int C2S_USE_GRANTED_TOOL = 18;
    public static final int C2S_REQUEST_PROFILE = 27;
    public static final int C2S_SET_DIFFICULTY = 28;
    public static final int C2S_CHAT = 29;
    public static final int C2S_START_WAVES = 30;
    /** A click that aims a loaded plant at a cell: the cob cannon's shot. */
    public static final int C2S_FIRE_AT = 31;
    /** A click that puts a paid-for zombie on the lawn: I, Zombie's placement. */
    public static final int C2S_PLACE_ZOMBIE = 32;
    /** One rhythm note, pressed and judged by the client. */
    public static final int C2S_RHYTHM_HIT = 33;
    /** Where Jev is, for a versus level's opponent to be reached: URL, model and key. */
    public static final int C2S_JEV_SETTINGS = 34;

    // ---- server -> client ----
    public static final int S2C_LEVEL_INIT = S2C_BASE + 1;
    public static final int S2C_LEVEL_LIST = S2C_BASE + 2;
    public static final int S2C_LEVEL_SAVE_PROMPT = S2C_BASE + 3;
    public static final int S2C_OPEN_EDITOR = S2C_BASE + 4;
    public static final int S2C_SCENE_SYNC = S2C_BASE + 5;
    public static final int S2C_ENTITY_SPAWN = S2C_BASE + 6;
    public static final int S2C_ENTITY_UPDATE = S2C_BASE + 7;
    public static final int S2C_ENTITY_DESPAWN = S2C_BASE + 8;
    public static final int S2C_EFFECT_EVENT = S2C_BASE + 9;
    public static final int S2C_RESOURCE_COLLECT = S2C_BASE + 10;
    public static final int S2C_RESOURCE_DELTA = S2C_BASE + 11;
    public static final int S2C_SLOT_SYNC = S2C_BASE + 12;
    public static final int S2C_GAME_STATE = S2C_BASE + 13;
    public static final int S2C_TEAM_SYNC = S2C_BASE + 14;
    public static final int S2C_SUGGESTIONS = S2C_BASE + 15;
    public static final int S2C_SERVER_MESSAGE = S2C_BASE + 16;
    public static final int S2C_WAVE_PROGRESS = S2C_BASE + 17;
    public static final int S2C_TIME_OF_DAY = S2C_BASE + 18;
    public static final int S2C_DEBUG_INFO = S2C_BASE + 19;
    public static final int S2C_GAME_SPEED = S2C_BASE + 20;
    public static final int S2C_MUSIC_EVENT = S2C_BASE + 21;
    public static final int S2C_LEVEL_TABS = S2C_BASE + 22;
    public static final int S2C_PROFILE = S2C_BASE + 23;
    public static final int S2C_LEVEL_REWARD = S2C_BASE + 24;
    public static final int S2C_MECHANIC_SYNC = S2C_BASE + 25;
    public static final int S2C_CARRY_SYNC = S2C_BASE + 26;
    /** The seed packet in the player's hand, or nothing: the card drop's own carry state. */
    public static final int S2C_HELD_CARD = S2C_BASE + 30;
    public static final int S2C_MUTATION_STATE = S2C_BASE + 27;
    /** Where an endless run stands: the round, its length, and that round's wave list. */
    public static final int S2C_ROUND_SYNC = S2C_BASE + 28;
    /** An endless round is over and the run is waiting for the player's next card choice. */
    public static final int S2C_ROUND_CLEAR = S2C_BASE + 29;
    public static final int S2C_PLANT_CARE = S2C_BASE + 33;
    public static final int S2C_ECHO_NETWORK = S2C_BASE + 31;
    public static final int S2C_MAGNET_ITEM = S2C_BASE + 32;
    /**
     * The save prompt's 重新开始.
     *
     * <p>19 rather than 18: 18 is {@link #C2S_USE_GRANTED_TOOL}, and id 16 stays retired.
     */
    public static final int C2S_DISCARD_SAVE = 19;
    /** The endless card chooser's answer: the bar to play the next round with. */
    public static final int C2S_RESELECT_CARDS = 20;
    /** The shop: "buy this item", priced by the server. */
    public static final int C2S_BUY_SHOP_ITEM = 21;
    /** The pack list changed on disk; the server reloads its side before the client reloads its own. */
    public static final int C2S_RELOAD_PACKS = 22;
    /** A click on a container (a vase, or one of the vase level's pots): break it open. */
    public static final int C2S_SMASH_CONTAINER = 23;
    /** A click on a seed packet lying on the lawn: pick it up. */
    public static final int C2S_PICK_UP_CARD = 24;
    /** A click on a cell while a picked-up seed packet is in hand: plant it there. */
    public static final int C2S_PLANT_HELD_CARD = 25;
    /** A right-click while a seed packet is in hand: put it back where it fell. */
    public static final int C2S_RELEASE_HELD_CARD = 26;
    /**
     * The HUD's "next wave" button: stop waiting out the gap and send the next wave.
     *
     * <p>35 rather than 27-34, which the other packets above already spend; the numbers on this
     * list are a wire format, so a gap is cheaper than a renumbering.
     */
    public static final int C2S_NEXT_WAVE = 35;

    private record Definition(int id, ConnectionDirection direction, Class<? extends PvzcePacket> type,
                              Function<PacketByteBuf, ? extends PvzcePacket> decoder) {
    }

    private static final List<Definition> DEFINITIONS = List.of(
            def(C2S_CONTINUE_LEVEL, ConnectionDirection.SERVERBOUND, ContinueLevelC2S.class,
                    ContinueLevelC2S::decode),
            def(C2S_PLAY_LEVEL, ConnectionDirection.SERVERBOUND, PlayLevelC2S.class, PlayLevelC2S::decode),
            def(C2S_RESTART_LEVEL, ConnectionDirection.SERVERBOUND, RestartLevelC2S.class,
                    RestartLevelC2S::decode),
            def(C2S_REQUEST_LEVEL_LIST, ConnectionDirection.SERVERBOUND, RequestLevelListC2S.class,
                    RequestLevelListC2S::decode),
            def(C2S_REQUEST_SUGGESTIONS, ConnectionDirection.SERVERBOUND, RequestSuggestionsC2S.class,
                    RequestSuggestionsC2S::decode),
            def(C2S_REQUEST_PROFILE, ConnectionDirection.SERVERBOUND, RequestProfileC2S.class,
                    RequestProfileC2S::decode),
            def(C2S_LEAVE_LEVEL, ConnectionDirection.SERVERBOUND, LeaveLevelC2S.class, LeaveLevelC2S::decode),
            def(C2S_PICK_CARD, ConnectionDirection.SERVERBOUND, PickCardC2S.class, PickCardC2S::decode),
            def(C2S_PLACE_PLANT, ConnectionDirection.SERVERBOUND, PlacePlantC2S.class, PlacePlantC2S::decode),
            def(C2S_USE_TOOL, ConnectionDirection.SERVERBOUND, UseToolC2S.class, UseToolC2S::decode),
            def(C2S_COLLECT_RESOURCE, ConnectionDirection.SERVERBOUND, CollectResourceC2S.class,
                    CollectResourceC2S::decode),
            def(C2S_COMMAND, ConnectionDirection.SERVERBOUND, CommandC2S.class, CommandC2S::decode),
            def(C2S_SET_GAME_SPEED, ConnectionDirection.SERVERBOUND, SetGameSpeedC2S.class, SetGameSpeedC2S::decode),
            def(C2S_PAUSE_GAME, ConnectionDirection.SERVERBOUND, PauseGameC2S.class, PauseGameC2S::decode),
            def(C2S_CREATE_WORLD, ConnectionDirection.SERVERBOUND, CreateWorldC2S.class, CreateWorldC2S::decode),
            def(C2S_UNLOCK_LEVEL, ConnectionDirection.SERVERBOUND, UnlockLevelC2S.class,
                    UnlockLevelC2S::decode),
            def(C2S_RELEASE_MOWER, ConnectionDirection.SERVERBOUND, ReleaseMowerC2S.class,
                    ReleaseMowerC2S::decode),
            def(C2S_BUY_SHOP_ITEM, ConnectionDirection.SERVERBOUND, BuyShopItemC2S.class,
                    BuyShopItemC2S::decode),
            def(C2S_USE_GRANTED_TOOL, ConnectionDirection.SERVERBOUND, UseGrantedToolC2S.class,
                    UseGrantedToolC2S::decode),
            def(C2S_SET_DIFFICULTY, ConnectionDirection.SERVERBOUND, SetDifficultyC2S.class,
                    SetDifficultyC2S::decode),
            def(C2S_CHAT, ConnectionDirection.SERVERBOUND, ChatC2S.class, ChatC2S::decode),
            def(C2S_START_WAVES, ConnectionDirection.SERVERBOUND,
                    com.pvzce.common.network.packet.StartWavesC2S.class,
                    com.pvzce.common.network.packet.StartWavesC2S::decode),
            def(C2S_NEXT_WAVE, ConnectionDirection.SERVERBOUND,
                    com.pvzce.common.network.packet.NextWaveC2S.class,
                    com.pvzce.common.network.packet.NextWaveC2S::decode),
            def(C2S_FIRE_AT, ConnectionDirection.SERVERBOUND,
                    com.pvzce.common.network.packet.FireAtC2S.class,
                    com.pvzce.common.network.packet.FireAtC2S::decode),
            def(C2S_PLACE_ZOMBIE, ConnectionDirection.SERVERBOUND,
                    com.pvzce.common.network.packet.PlaceZombieC2S.class,
                    com.pvzce.common.network.packet.PlaceZombieC2S::decode),
            def(C2S_RHYTHM_HIT, ConnectionDirection.SERVERBOUND,
                    com.pvzce.common.network.packet.RhythmHitC2S.class,
                    com.pvzce.common.network.packet.RhythmHitC2S::decode),
            def(C2S_JEV_SETTINGS, ConnectionDirection.SERVERBOUND,
                    com.pvzce.common.network.packet.AiSettingsC2S.class,
                    com.pvzce.common.network.packet.AiSettingsC2S::decode),
            def(C2S_RELOAD_PACKS, ConnectionDirection.SERVERBOUND, ReloadPacksC2S.class,
                    ReloadPacksC2S::decode),
            def(C2S_PICK_UP_CARD, ConnectionDirection.SERVERBOUND, PickUpCardC2S.class,
                    PickUpCardC2S::decode),
            def(C2S_PLANT_HELD_CARD, ConnectionDirection.SERVERBOUND, PlantHeldCardC2S.class,
                    PlantHeldCardC2S::decode),
            def(C2S_RELEASE_HELD_CARD, ConnectionDirection.SERVERBOUND, ReleaseHeldCardC2S.class,
                    ReleaseHeldCardC2S::decode),

            def(S2C_LEVEL_INIT, ConnectionDirection.CLIENTBOUND, LevelInitS2C.class, LevelInitS2C::decode),
            def(S2C_LEVEL_LIST, ConnectionDirection.CLIENTBOUND, LevelListS2C.class, LevelListS2C::decode),
            def(S2C_LEVEL_SAVE_PROMPT, ConnectionDirection.CLIENTBOUND, LevelSavePromptS2C.class,
                    LevelSavePromptS2C::decode),
            def(S2C_OPEN_EDITOR, ConnectionDirection.CLIENTBOUND, OpenEditorS2C.class, OpenEditorS2C::decode),
            def(S2C_SCENE_SYNC, ConnectionDirection.CLIENTBOUND, SceneSyncS2C.class, SceneSyncS2C::decode),
            def(S2C_ENTITY_SPAWN, ConnectionDirection.CLIENTBOUND, EntitySpawnS2C.class, EntitySpawnS2C::decode),
            def(S2C_PLANT_CARE, ConnectionDirection.CLIENTBOUND, com.pvzce.common.network.packet.PlantCareS2C.class, com.pvzce.common.network.packet.PlantCareS2C::decode),
            def(S2C_ECHO_NETWORK, ConnectionDirection.CLIENTBOUND, EchoNetworkS2C.class, EchoNetworkS2C::decode),
            def(S2C_ENTITY_UPDATE, ConnectionDirection.CLIENTBOUND, EntityUpdateS2C.class, EntityUpdateS2C::decode),
            def(S2C_ENTITY_DESPAWN, ConnectionDirection.CLIENTBOUND, EntityDespawnS2C.class,
                    EntityDespawnS2C::decode),
            def(S2C_MAGNET_ITEM, ConnectionDirection.CLIENTBOUND,
                    com.pvzce.common.network.packet.MagnetItemS2C.class,
                    com.pvzce.common.network.packet.MagnetItemS2C::decode),
            def(S2C_EFFECT_EVENT, ConnectionDirection.CLIENTBOUND, EffectEventS2C.class, EffectEventS2C::decode),
            def(S2C_CARRY_SYNC, ConnectionDirection.CLIENTBOUND, CarrySyncS2C.class, CarrySyncS2C::decode),
            def(S2C_HELD_CARD, ConnectionDirection.CLIENTBOUND, HeldCardS2C.class, HeldCardS2C::decode),
            def(S2C_RESOURCE_COLLECT, ConnectionDirection.CLIENTBOUND, ResourceCollectS2C.class,
                    ResourceCollectS2C::decode),
            def(S2C_RESOURCE_DELTA, ConnectionDirection.CLIENTBOUND, ResourceDeltaS2C.class,
                    ResourceDeltaS2C::decode),
            def(S2C_SLOT_SYNC, ConnectionDirection.CLIENTBOUND, SlotSyncS2C.class, SlotSyncS2C::decode),
            def(S2C_GAME_STATE, ConnectionDirection.CLIENTBOUND, GameStateS2C.class, GameStateS2C::decode),
            def(S2C_TEAM_SYNC, ConnectionDirection.CLIENTBOUND, TeamSyncS2C.class, TeamSyncS2C::decode),
            def(S2C_SUGGESTIONS, ConnectionDirection.CLIENTBOUND, SuggestionsS2C.class, SuggestionsS2C::decode),
            def(S2C_SERVER_MESSAGE, ConnectionDirection.CLIENTBOUND, ServerMessageS2C.class,
                    ServerMessageS2C::decode),
            def(S2C_WAVE_PROGRESS, ConnectionDirection.CLIENTBOUND, WaveProgressS2C.class, WaveProgressS2C::decode),
            def(S2C_TIME_OF_DAY, ConnectionDirection.CLIENTBOUND, TimeOfDayS2C.class, TimeOfDayS2C::decode),
            def(S2C_DEBUG_INFO, ConnectionDirection.CLIENTBOUND, DebugInfoS2C.class, DebugInfoS2C::decode),
            def(S2C_GAME_SPEED, ConnectionDirection.CLIENTBOUND, GameSpeedS2C.class, GameSpeedS2C::decode),
            def(S2C_MUSIC_EVENT, ConnectionDirection.CLIENTBOUND, MusicEventS2C.class, MusicEventS2C::decode),
            def(S2C_LEVEL_TABS, ConnectionDirection.CLIENTBOUND, LevelTabsS2C.class, LevelTabsS2C::decode),
            def(S2C_PROFILE, ConnectionDirection.CLIENTBOUND, ProfileS2C.class, ProfileS2C::decode),
            def(S2C_LEVEL_REWARD, ConnectionDirection.CLIENTBOUND, LevelRewardS2C.class, LevelRewardS2C::decode),
            def(S2C_MECHANIC_SYNC, ConnectionDirection.CLIENTBOUND, MechanicSyncS2C.class,
                    MechanicSyncS2C::decode),
            def(S2C_MUTATION_STATE, ConnectionDirection.CLIENTBOUND, MutationStateS2C.class,
                    MutationStateS2C::decode),
            def(S2C_ROUND_SYNC, ConnectionDirection.CLIENTBOUND, RoundSyncS2C.class,
                    RoundSyncS2C::decode),
            def(S2C_ROUND_CLEAR, ConnectionDirection.CLIENTBOUND, RoundClearS2C.class,
                    RoundClearS2C::decode),
            def(C2S_STAGE_CHOICE, ConnectionDirection.SERVERBOUND, StageChoiceC2S.class,
                    StageChoiceC2S::decode),
            def(C2S_OUTPOST_STRIKE, ConnectionDirection.SERVERBOUND, OutpostStrikeC2S.class,
                    OutpostStrikeC2S::decode),
            def(C2S_RESELECT_CARDS, ConnectionDirection.SERVERBOUND, ReselectCardsC2S.class,
                    ReselectCardsC2S::decode),
            def(C2S_DISCARD_SAVE, ConnectionDirection.SERVERBOUND, DiscardLevelSaveC2S.class,
                    DiscardLevelSaveC2S::decode),
            def(C2S_SMASH_CONTAINER, ConnectionDirection.SERVERBOUND, SmashContainerC2S.class,
                    SmashContainerC2S::decode));

    private static volatile boolean registered;

    private PvzcePackets() {
    }

    private static Definition def(int id, ConnectionDirection direction, Class<? extends PvzcePacket> type,
                                 Function<PacketByteBuf, ? extends PvzcePacket> decoder) {
        return new Definition(id, direction, type, decoder);
    }

    /** The number of packet types in this protocol version. */
    public static int count() {
        return DEFINITIONS.size();
    }

    public static void register() {
        if (registered) {
            return;
        }
        synchronized (PvzcePackets.class) {
            if (registered) {
                return;
            }
            for (Definition definition : DEFINITIONS) {
                registerOne(definition);
            }
            registered = true;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends PvzcePacket> void registerOne(Definition definition) {
        PacketRegistry.register(definition.id(), (Class<T>) definition.type(), definition.direction(),
                (Function<PacketByteBuf, T>) definition.decoder());
    }
}
