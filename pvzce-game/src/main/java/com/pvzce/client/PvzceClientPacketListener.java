package com.pvzce.client;

import com.pvzce.api.util.Identifier;
import com.pvzce.common.network.PacketListener;
import com.pvzce.common.network.PvzcePacket;
import com.pvzce.common.network.PvzcePackets;
import com.pvzce.common.network.packet.DebugInfoS2C;
import com.pvzce.common.network.packet.EffectEventS2C;
import com.pvzce.common.network.packet.EntityDespawnS2C;
import com.pvzce.common.network.packet.EntitySpawnS2C;
import com.pvzce.common.network.packet.EntityUpdateS2C;
import com.pvzce.common.network.packet.GameSpeedS2C;
import com.pvzce.common.network.packet.GameStateS2C;
import com.pvzce.common.network.packet.BeltSyncS2C;
import com.pvzce.common.network.packet.LevelInitS2C;
import com.pvzce.common.network.packet.LevelListS2C;
import com.pvzce.common.network.packet.LevelRewardS2C;
import com.pvzce.common.network.packet.LevelSavePromptS2C;
import com.pvzce.common.network.packet.LevelTabsS2C;
import com.pvzce.common.network.packet.MusicEventS2C;
import com.pvzce.common.network.packet.OpenEditorS2C;
import com.pvzce.common.network.packet.ProfileS2C;
import com.pvzce.common.network.packet.ResourceCollectS2C;
import com.pvzce.common.network.packet.ResourceDeltaS2C;
import com.pvzce.common.network.packet.SceneSyncS2C;
import com.pvzce.common.network.packet.ServerMessageS2C;
import com.pvzce.common.network.packet.SlotSyncS2C;
import com.pvzce.common.network.packet.SuggestionsS2C;
import com.pvzce.common.network.packet.TimeOfDayS2C;
import com.pvzce.common.network.packet.WaveProgressS2C;
import com.pvzce.common.network.packet.TeamSyncS2C;

public final class PvzceClientPacketListener implements PacketListener {
    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("pvzce-client-packets");

    private final PvzceClient client;
    private final ClientLevel level;

    public PvzceClientPacketListener(PvzceClient client, ClientLevel level) {
        this.client = client;
        this.level = level;
    }

    @Override
    public void handle(PvzcePacket packet) {
        if (packet instanceof LevelInitS2C init) {
            if (init.protocolVersion() != PvzcePackets.PROTOCOL_VERSION) {
                // Refuse to run a mismatched protocol instead of decoding garbage.
                client.onProtocolMismatch(init.protocolVersion(), PvzcePackets.PROTOCOL_VERSION);
                return;
            }
            level.init(init.levelId(), init.width(), init.height(), init.slots(), init.waveTypes(),
                    init.seedPool(), init.maxSeedSlots(), init.previewZombies(), init.sceneCells(),
                    init.controlledTeamId(), init.controlledTeamName(),
                    init.payload().isConveyor(), init.payload().beltCapacity(),
                    init.payload().zoneMinX(), init.payload().zoneMaxX(),
                    init.payload().zoneMinY(), init.payload().zoneMaxY());
            client.onLevelInit();
        } else if (packet instanceof LevelListS2C list) {
            client.setLevelList(list.levels());
        } else if (packet instanceof LevelTabsS2C tabs) {
            client.setLevelTabs(tabs.tabs());
        } else if (packet instanceof LevelSavePromptS2C prompt) {
            client.showLevelSavePrompt(prompt);
        } else if (packet instanceof OpenEditorS2C editor) {
            Identifier editorLevel = Identifier.tryParse(editor.levelId());
            if (editorLevel == null) {
                LOGGER.warn("[editor] server asked to edit an invalid level id '{}'", editor.levelId());
            } else {
                client.openEditor(editorLevel);
            }
        } else if (packet instanceof SceneSyncS2C scene) {
            level.applyScene(scene.cells());
        } else if (packet instanceof EntitySpawnS2C spawn) {
            level.addEntity(ClientEntity.from(spawn));
        } else if (packet instanceof EntityUpdateS2C update) {
            ClientEntity entity = level.entities().get(update.entityId());
            if (entity != null) {
                entity.apply(update);
            }
        } else if (packet instanceof EntityDespawnS2C despawn) {
            level.removeEntity(despawn.entityId());
        } else if (packet instanceof EffectEventS2C effect) {
            level.addEffect(effect);
        } else if (packet instanceof ResourceCollectS2C collect) {
            Identifier icon = Identifier.tryParse(collect.icon());
            if (icon == null) {
                String path = collect.resourceId().contains(":")
                        ? collect.resourceId().substring(collect.resourceId().indexOf(':') + 1)
                        : collect.resourceId();
                icon = Identifier.withDefaultNamespace("textures/resource/" + path);
            }
            level.addCollectAnimation(new ResourceCollectAnimation(collect.entityId(), collect.resourceId(),
                    collect.amount(), icon, collect.x(), collect.y(), collect.height()));
        } else if (packet instanceof ResourceDeltaS2C delta) {
            // Every resource is mirrored, not just sun: the server sends deltas for
            // redstone and energy beans too, and the client used to silently drop
            // anything whose id did not end in ":sun".
            Identifier resourceId = Identifier.tryParse(delta.resourceId());
            Identifier teamId = Identifier.tryParse(delta.teamId());
            level.setResource(teamId, resourceId, delta.totalAmount());
        } else if (packet instanceof SlotSyncS2C sync) {
            level.upsertSlot(sync.slot());
        } else if (packet instanceof BeltSyncS2C belt) {
            // Whole-bar replacement, unlike SlotSync: a spent belt card has to disappear
            // from the client's bar and an upsert can only ever add or update one.
            level.replaceSlots(belt.cards());
        } else if (packet instanceof GameStateS2C state) {
            level.setGameState(state.state(), state.winTeamId());
            client.onGameState(state.state(), state.winTeamId());
        } else if (packet instanceof MusicEventS2C music) {
            client.onMusicEvent(music.track(), music.event(), music.loop(), music.stop(),
                    music.volume(), music.fadeSeconds());
        } else if (packet instanceof TeamSyncS2C team) {
            level.setControlledTeam(team.teamId(), team.teamName());
        } else if (packet instanceof SuggestionsS2C suggestions) {
            level.addSuggestions(suggestions);
        } else if (packet instanceof ServerMessageS2C message
                && Boolean.getBoolean("pvzce.traceMessages")) {
            LOGGER.info("[msg] {}", message.message());
        } else if (packet instanceof WaveProgressS2C wave) {
            level.setWaveProgress(wave.currentWave(), wave.totalWaves(), wave.progress(),
                    wave.warningActive(), wave.finalWarning());
        } else if (packet instanceof TimeOfDayS2C time) {
            level.setTimeOfDay(time);
        } else if (packet instanceof GameSpeedS2C speed) {
            level.setTargetTickRate(speed.tickRate());
        } else if (packet instanceof DebugInfoS2C debug) {
            level.setDebugInfo(debug.tickCount(), debug.frozen(), debug.sprinting());
        } else if (packet instanceof ProfileS2C profile) {
            client.setProfile(profile.coins(), profile.unlocked(), profile.unlockAll());
        } else if (packet instanceof LevelRewardS2C reward) {
            // Arrives right after GameStateS2C; the client is already showing the
            // victory overlay, and this is what turns it into the award screen.
            client.onLevelReward(reward);
        } else if (packet instanceof ServerMessageS2C message) {
            level.addMessage(message.message());
        }
    }

    @Override
    public void onDisconnect(String reason) {
        level.setDisconnected(reason);
    }
}
