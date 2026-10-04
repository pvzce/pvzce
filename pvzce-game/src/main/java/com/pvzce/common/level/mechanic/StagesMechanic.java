package com.pvzce.common.level.mechanic;

import com.mojang.serialization.MapCodec;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.PreparationData;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceConstants;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PacketStruct;
import com.pvzce.common.network.packet.MechanicSyncS2C;
import com.pvzce.common.network.packet.RoundClearS2C;
import com.pvzce.common.network.packet.GameStateS2C;
import com.pvzce.server.level.LevelServer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Finite wave groups pause for a new deck and replaceable buffs while preserving the world. */
public final class StagesMechanic implements LevelMechanic<StagePlan> {
    public record Status(int phase, int battleTicks, boolean choosing, boolean ready, List<String> buffs) {
        public static final PacketStruct.Codec<Status> CODEC = PacketStruct.<Status>builder()
                .field(Status::phase, PacketByteBuf::writeInt, PacketByteBuf::readInt)
                .field(Status::battleTicks, PacketByteBuf::writeInt, PacketByteBuf::readInt)
                .field(Status::choosing, PacketByteBuf::writeBoolean, PacketByteBuf::readBoolean)
                .field(Status::ready, PacketByteBuf::writeBoolean, PacketByteBuf::readBoolean)
                .stringList(Status::buffs)
                .build(v -> new Status((Integer) v.get(0), (Integer) v.get(1), (Boolean) v.get(2),
                        (Boolean) v.get(3), castBuffs(v.get(4))));

        @SuppressWarnings("unchecked")
        private static List<String> castBuffs(Object buffs) {
            return (List<String>) buffs;
        }
    }

    private static final class Progress {
        int phase;
        int battleTicks;
        boolean choosing;
        boolean ready;
        boolean musicSent;
        boolean summarySent;
        final Map<Identifier, Integer> cooldowns = new HashMap<>();
    }

    private static Progress progress(LevelServer level) {
        return level.mechanicState(PvzceIds.MECHANIC_STAGES, Progress::new);
    }

    public static StagePlan plan(LevelServer level) {
        return LevelMechanics.data(level.def(), PvzceIds.MECHANIC_STAGES, StagePlan.class);
    }

    public static Status status(LevelServer level) {
        Progress p = progress(level);
        return new Status(p.phase, p.battleTicks, p.choosing, p.ready,
                level.activeBuffs().stream().map(buff -> buff.id().toString()).toList());
    }

    public static boolean isChoosing(LevelServer level) {
        return plan(level) != null && progress(level).choosing;
    }

    public static boolean isReady(LevelServer level) {
        return plan(level) == null || progress(level).ready;
    }

    public static boolean holdsNextWave(LevelServer level) {
        StagePlan plan = plan(level);
        if (plan == null || plan.phases().isEmpty()) {
            return false;
        }
        Progress p = progress(level);
        return p.choosing || p.phase + 1 < plan.phases().size()
                && level.currentWave() >= plan.phases().get(p.phase + 1).fromWave() - 1;
    }

    @Override
    public MapCodec<StagePlan> codec() {
        return StagePlan.CODEC;
    }

    @Override
    public void tick(LevelServer level, StagePlan data) {
        if (data.phases().isEmpty()) {
            return;
        }
        Progress p = progress(level);
        if (!p.musicSent) {
            level.playStageMusic(data.phases().get(p.phase).music());
            p.musicSent = true;
        }
        p.cooldowns.replaceAll((id, ticks) -> Math.max(0, ticks - 1));
        if (!level.isPreparing() && !p.ready) {
            p.battleTicks++;
            int endWave = p.phase + 1 < data.phases().size()
                    ? data.phases().get(p.phase + 1).fromWave() - 1 : level.def().waves().size();
            if (level.currentWave() >= endWave && level.currentWaveFullyReleased()
                    && level.hostileZombieCount() == 0) {
                if (p.phase + 1 < data.phases().size()) {
                    p.choosing = true;
                } else {
                    p.ready = true;
                }
                sync(level);
            }
        }
        if (level.tickCount() % PvzceConstants.TICKS_PER_SECOND == 0) {
            sync(level);
        }
        if (p.choosing) {
            sendChoice(level, p);
        }
    }

    private static void sendChoice(LevelServer level, Progress p) {
        if (!p.summarySent) {
            p.summarySent = true;
            level.send(new RoundClearS2C(p.phase + 1, level.currentWave(), level.zombieKills(), p.battleTicks));
        }
    }

    /** Replays the choice after a paused save was loaded, without advancing its clock. */
    public static void tickChoice(LevelServer level, LevelServer.ServerBridge bridge) {
        Progress p = progress(level);
        if (!p.summarySent && bridge != null) {
            p.summarySent = true;
            bridge.send(new RoundClearS2C(p.phase + 1, level.currentWave(), level.zombieKills(), p.battleTicks));
        }
    }

    public static boolean resume(LevelServer level, int nextPhase, List<Identifier> cards,
                                 List<Identifier> buffs, LevelServer.ServerBridge bridge) {
        StagePlan plan = plan(level);
        Progress p = progress(level);
        if (plan == null || !p.choosing || p.phase + 1 >= plan.phases().size() || nextPhase != p.phase + 2
                || !GameStateS2C.RUNNING.equals(level.gameState())
                || !PvzceIds.PLANT_TEAM.equals(level.humanTeamId())) {
            return false;
        }
        level.replaceStageSelection(cards, buffs, p.cooldowns, bridge);
        p.phase++;
        p.choosing = false;
        p.summarySent = false;
        p.ready = false;
        p.musicSent = true;
        StagePlan.Phase phase = plan.phases().get(p.phase);
        level.team(PvzceIds.PLANT_TEAM).addResource(PvzceIds.SUN, phase.supplySun());
        OutpostsMechanic.grantStageSupply(level);
        level.playStageMusic(phase.music(), bridge);
        PreparationMechanic.begin(level);
        bridge.send(MechanicSyncS2C.of(PvzceIds.MECHANIC_STAGES, Status.CODEC, status(level)));
        return true;
    }

    private static void sync(LevelServer level) {
        level.send(MechanicSyncS2C.of(PvzceIds.MECHANIC_STAGES, Status.CODEC, status(level)));
    }

    @Override
    public void sendState(LevelServer level, StagePlan data, LevelServer.ServerBridge bridge) {
        Progress p = progress(level);
        bridge.send(MechanicSyncS2C.of(PvzceIds.MECHANIC_STAGES, Status.CODEC, status(level)));
        if (!data.phases().isEmpty()) {
            level.playStageMusic(data.phases().get(p.phase).music(), bridge);
            p.musicSent = true;
        }
        p.summarySent = false;
    }

    @Override
    public void collectSave(LevelServer level, StagePlan data, CompoundTag root) {
        Progress p = progress(level);
        CompoundTag saved = new CompoundTag();
        saved.putInt("Phase", p.phase);
        saved.putInt("BattleTicks", p.battleTicks);
        saved.putByte("Choosing", (byte) (p.choosing ? 1 : 0));
        saved.putByte("Ready", (byte) (p.ready ? 1 : 0));
        CompoundTag cooldowns = new CompoundTag();
        p.cooldowns.forEach((id, ticks) -> cooldowns.putInt(id.toString(), ticks));
        saved.put("Cooldowns", cooldowns);
        root.put("Stages", saved);
    }

    @Override
    public void applySave(LevelServer level, StagePlan data, CompoundTag root) {
        CompoundTag saved = root.getCompound("Stages");
        Progress p = progress(level);
        p.phase = Math.max(0, Math.min(data.phases().size() - 1, saved.getInt("Phase")));
        p.battleTicks = Math.max(0, saved.getInt("BattleTicks"));
        p.choosing = saved.getInt("Choosing") != 0 && p.phase + 1 < data.phases().size();
        p.ready = saved.getInt("Ready") != 0;
        p.musicSent = false;
        p.summarySent = false;
        p.cooldowns.clear();
        saved.getCompound("Cooldowns").entries().forEach((key, value) -> {
            Identifier id = Identifier.tryParse(key);
            if (id != null) {
                p.cooldowns.put(id, Math.max(0, saved.getCompound("Cooldowns").getInt(key)));
            }
        });
    }

    @Override
    public List<String> validate(LevelDef def, StagePlan data) {
        List<String> errors = new ArrayList<>();
        int previous = 0;
        if (data.phases().isEmpty() || data.phases().getFirst().fromWave() != 1
                || LevelMechanics.data(def, PvzceIds.MECHANIC_PREPARATION,
                        PreparationData.class) == null || def.mechanics().stream().anyMatch(m -> m.type().equals(PvzceIds.MECHANIC_ENDLESS))) {
            errors.add("Stages require phases starting at wave 1, preparation and finite waves");
        }
        for (StagePlan.Phase phase : data.phases()) {
            if (phase.fromWave() <= previous || phase.fromWave() > def.waves().size() || phase.supplySun() < 0) {
                errors.add("Invalid stage wave boundary or supply: " + phase.fromWave());
            }
            if (!List.of("day", "dusk", "night").contains(phase.atmosphere())) {
                errors.add("Unknown stage atmosphere: " + phase.atmosphere());
            }
            for (LevelDef.MusicCue cue : phase.music()) {
                if (cue.atTick() != 0 || !Float.isFinite(cue.volume()) || cue.volume() < 0F || cue.volume() > 1F
                        || !Float.isFinite(cue.fadeSeconds()) || cue.fadeSeconds() < 0F) {
                    errors.add("Stage music must start immediately with valid volume and fade: " + phase.name());
                }
            }
            previous = phase.fromWave();
        }
        return errors;
    }
}
