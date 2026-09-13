package com.pvzce.common.network;

import com.pvzce.common.network.packet.MechanicSyncS2C;
import com.pvzce.common.network.packet.CollectResourceC2S;
import com.pvzce.common.network.packet.CommandC2S;
import com.pvzce.common.network.packet.CreateWorldC2S;
import com.pvzce.common.network.packet.MovePlantC2S;
import com.pvzce.common.network.packet.UnlockLevelC2S;
import com.pvzce.common.network.packet.DebugInfoS2C;
import com.pvzce.common.network.packet.EffectEventS2C;
import com.pvzce.common.network.packet.EntityDespawnS2C;
import com.pvzce.common.network.packet.EntitySpawnS2C;
import com.pvzce.common.network.packet.EntityUpdateS2C;
import com.pvzce.common.network.packet.GameSpeedS2C;
import com.pvzce.common.network.packet.GameStateS2C;
import com.pvzce.common.network.packet.LeaveLevelC2S;
import com.pvzce.common.network.packet.LevelInitS2C;
import com.pvzce.common.network.packet.LevelListS2C;
import com.pvzce.common.network.packet.LevelSavePromptS2C;
import com.pvzce.common.network.packet.LevelRewardS2C;
import com.pvzce.common.network.packet.LevelTabsS2C;
import com.pvzce.common.network.packet.MusicEventS2C;
import com.pvzce.common.network.packet.OpenEditorS2C;
import com.pvzce.common.network.packet.PauseGameC2S;
import com.pvzce.common.network.packet.ProfileS2C;
import com.pvzce.common.network.packet.PickCardC2S;
import com.pvzce.common.network.packet.PlacePlantC2S;
import com.pvzce.common.network.packet.RequestLevelC2S;
import com.pvzce.common.network.packet.RequestLevelListC2S;
import com.pvzce.common.network.packet.RequestSuggestionsC2S;
import com.pvzce.common.network.packet.ResourceCollectS2C;
import com.pvzce.common.network.packet.ResourceDeltaS2C;
import com.pvzce.common.network.packet.ResumeLevelC2S;
import com.pvzce.common.network.packet.SceneSyncS2C;
import com.pvzce.common.network.packet.ServerMessageS2C;
import com.pvzce.common.network.packet.SetGameSpeedC2S;
import com.pvzce.common.network.packet.SlotSyncS2C;
import com.pvzce.common.network.packet.StartLevelC2S;
import com.pvzce.common.network.packet.SuggestionsS2C;
import com.pvzce.common.network.packet.TeamSyncS2C;
import com.pvzce.common.network.packet.TimeOfDayS2C;
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
    public static final int PROTOCOL_VERSION = 11;

    /** Server-to-client ids start here; everything below is client-to-server. */
    public static final int S2C_BASE = 64;

    // ---- client -> server ----
    public static final int C2S_REQUEST_LEVEL = 1;
    public static final int C2S_START_LEVEL = 2;
    public static final int C2S_RESUME_LEVEL = 3;
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
    public static final int C2S_MOVE_PLANT = 16;

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

    private record Definition(int id, ConnectionDirection direction, Class<? extends PvzcePacket> type,
                              Function<PacketByteBuf, ? extends PvzcePacket> decoder) {
    }

    private static final List<Definition> DEFINITIONS = List.of(
            def(C2S_REQUEST_LEVEL, ConnectionDirection.SERVERBOUND, RequestLevelC2S.class, RequestLevelC2S::decode),
            def(C2S_START_LEVEL, ConnectionDirection.SERVERBOUND, StartLevelC2S.class, StartLevelC2S::decode),
            def(C2S_RESUME_LEVEL, ConnectionDirection.SERVERBOUND, ResumeLevelC2S.class, ResumeLevelC2S::decode),
            def(C2S_REQUEST_LEVEL_LIST, ConnectionDirection.SERVERBOUND, RequestLevelListC2S.class,
                    RequestLevelListC2S::decode),
            def(C2S_REQUEST_SUGGESTIONS, ConnectionDirection.SERVERBOUND, RequestSuggestionsC2S.class,
                    RequestSuggestionsC2S::decode),
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
            def(C2S_MOVE_PLANT, ConnectionDirection.SERVERBOUND, MovePlantC2S.class,
                    MovePlantC2S::decode),

            def(S2C_LEVEL_INIT, ConnectionDirection.CLIENTBOUND, LevelInitS2C.class, LevelInitS2C::decode),
            def(S2C_LEVEL_LIST, ConnectionDirection.CLIENTBOUND, LevelListS2C.class, LevelListS2C::decode),
            def(S2C_LEVEL_SAVE_PROMPT, ConnectionDirection.CLIENTBOUND, LevelSavePromptS2C.class,
                    LevelSavePromptS2C::decode),
            def(S2C_OPEN_EDITOR, ConnectionDirection.CLIENTBOUND, OpenEditorS2C.class, OpenEditorS2C::decode),
            def(S2C_SCENE_SYNC, ConnectionDirection.CLIENTBOUND, SceneSyncS2C.class, SceneSyncS2C::decode),
            def(S2C_ENTITY_SPAWN, ConnectionDirection.CLIENTBOUND, EntitySpawnS2C.class, EntitySpawnS2C::decode),
            def(S2C_ENTITY_UPDATE, ConnectionDirection.CLIENTBOUND, EntityUpdateS2C.class, EntityUpdateS2C::decode),
            def(S2C_ENTITY_DESPAWN, ConnectionDirection.CLIENTBOUND, EntityDespawnS2C.class,
                    EntityDespawnS2C::decode),
            def(S2C_EFFECT_EVENT, ConnectionDirection.CLIENTBOUND, EffectEventS2C.class, EffectEventS2C::decode),
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
                    MechanicSyncS2C::decode));

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
