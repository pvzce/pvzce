package com.pvzce.common.level.mechanic;

import com.mojang.serialization.MapCodec;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.PreparationData;
import com.pvzce.api.content.mechanic.FieldSpec;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PacketStruct;
import com.pvzce.common.network.packet.MechanicSyncS2C;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.server.level.LevelServer;

import java.util.ArrayList;
import java.util.List;

/**
 * The preparation phase: build first, then start the waves.
 *
 * <p>What it is for and why it is a mechanic rather than a flag are in {@link PreparationData}.
 * This half is the state machine: the level starts preparing, and stops when the player says so
 * (or when the declared clock runs out).
 *
 * <p><b>The phase is server state, and it is saved.</b> A run resumed in the middle of its
 * preparation must come back *in* preparation: a save written by "the player was still arranging
 * their lawn" and read back as "the waves are running" would drop the first wave on a defence
 * that had not been finished - a bug that only appears after a quit, which is the kind that takes
 * a report to find.
 */
public final class PreparationMechanic implements LevelMechanic<PreparationData> {
    /**
     * What the client is told, and the whole of what it needs: whether the waves are being held.
     *
     * <p>Declared here and used by both sides - the server encodes with it, the client decodes with
     * it - so a field added later cannot arrive on one side only (the same rule the belt's bar
     * state follows).
     */
    public record State(boolean preparing) {
        public static final PacketStruct.Codec<State> CODEC =
                PacketStruct.<State>builder()
                        .field(State::preparing, PacketByteBuf::writeBoolean, PacketByteBuf::readBoolean)
                        .build(values -> new State((Boolean) values.get(0)));
    }

    /** Whether the level is in its preparation phase, and when it has been told the client. */
    private static final class Phase {
        boolean preparing = true;
        boolean synced;
        boolean refund = true;
        int nextStageWave;
        int startTick;
    }

    private static Phase phase(LevelServer level) {
        return level.mechanicState(com.pvzce.common.PvzceIds.MECHANIC_PREPARATION, Phase::new);
    }

    @Override
    public MapCodec<PreparationData> codec() {
        return PreparationData.CODEC;
    }

    @Override
    public void onLevelCreated(LevelServer level, PreparationData data) {
        Phase phase = phase(level);
        // A resumed run keeps whatever the save said; `applySave` runs after this and overwrites it.
        phase.preparing = true;
        phase.refund = data.refund();
        phase.nextStageWave = data.wavesPerStage();
        begin(level);
    }

    @Override
    public void tick(LevelServer level, PreparationData data) {
        Phase phase = phase(level);
        if (!phase.preparing) {
            if (holdsNextWave(level) && level.currentWaveFullyReleased()
                    && level.aliveZombieCount() == 0) {
                phase.nextStageWave += data.wavesPerStage();
                if (data.stageSun() > 0) {
                    level.team(com.pvzce.common.PvzceIds.PLANT_TEAM)
                            .addResource(com.pvzce.common.PvzceIds.SUN, data.stageSun());
                }
                begin(level);
                level.send(new com.pvzce.common.network.packet.ServerMessageS2C(
                        "已守住 " + level.currentWave() / data.wavesPerStage()
                                + " 面旗帜，补给 " + data.stageSun() + " 阳光。修整后按开始继续。"));
            }
            // Nothing left to do but keep the client's copy honest after a resume.
            sync(level, phase);
            return;
        }
        if (!data.manual() && level.tickCount() - phase.startTick >= data.ticks()) {
            level.beginWaves();
        }
        sync(level, phase);
    }

    /** Tells the client where the phase stands, once at the start and again on every change. */
    private static void sync(LevelServer level, Phase phase) {
        if (phase.synced) {
            return;
        }
        phase.synced = true;
        level.send(MechanicSyncS2C.of(com.pvzce.common.PvzceIds.MECHANIC_PREPARATION,
                State.CODEC, new State(phase.preparing)));
    }

    @Override
    public void sendState(LevelServer level, PreparationData data, LevelServer.ServerBridge bridge) {
        bridge.send(MechanicSyncS2C.of(com.pvzce.common.PvzceIds.MECHANIC_PREPARATION,
                State.CODEC, new State(phase(level).preparing)));
    }

    /** Starts (or restarts) the phase. The client hears about it on the next tick. */
    public static void begin(LevelServer level) {
        Phase phase = phase(level);
        phase.preparing = true;
        phase.startTick = level.tickCount();
        phase.synced = false;
    }

    /** Ends the phase: the waves are the level's from here on. */
    public static void end(LevelServer level) {
        Phase phase = phase(level);
        phase.preparing = false;
        phase.synced = false;
    }

    /** True while this level is still holding its waves; false for every other level. */
    public static boolean isPreparing(LevelServer level) {
        Phase phase = level.mechanicStateOrNull(com.pvzce.common.PvzceIds.MECHANIC_PREPARATION,
                Phase.class);
        return phase != null && phase.preparing;
    }

    /** Hold only the next arrival; the flag wave must finish releasing and be killed first. */
    public static boolean holdsNextWave(LevelServer level) {
        Phase phase = level.mechanicStateOrNull(com.pvzce.common.PvzceIds.MECHANIC_PREPARATION, Phase.class);
        return phase != null && phase.nextStageWave > 0
                && level.currentWave() >= phase.nextStageWave
                && level.currentWave() < level.def().waves().size();
    }

    @Override
    public boolean canPlacePlant(LevelServer level, PreparationData data,
                                 com.pvzce.api.content.PlantDef plant, int x, int y) {
        return !data.excludedCards().contains(plant.id());
    }

    /** True when digging a plant up in this phase refunds its whole price. */
    public static boolean refundsFully(LevelServer level) {
        Phase phase = level.mechanicStateOrNull(com.pvzce.common.PvzceIds.MECHANIC_PREPARATION,
                Phase.class);
        return phase != null && phase.preparing && phase.refund;
    }

    @Override
    public List<String> validate(LevelDef def, PreparationData data) {
        List<String> errors = new ArrayList<>();
        if (def.initialSun() <= 0) {
            // The rule of the phase is "build with what you were given". A level that gives nothing
            // is a level where the phase can only be waited out, which is never what an author
            // meant - either the sun is missing or the mechanic is.
            errors.add("This level has a preparation phase and no initial sun: the phase is where the"
                    + " player spends it, and there is nothing to spend");
        }
        if (!data.manual() && data.ticks() <= 0) {
            errors.add("This level's preparation phase has no clock and no start button, so it"
                    + " would never end");
        }
        errors.addAll(LevelMechanics.unknownCards(data.excludedCards(), "preparation excluded card"));
        if (data.wavesPerStage() > 0 && def.waves().size() % data.wavesPerStage() != 0) {
            errors.add("preparation waves_per_stage must divide the level's wave count");
        }
        return errors;
    }

    @Override
    public List<FieldSpec> editorFields() {
        return List.of(
                new FieldSpec.Bool("manual", "pvzce.mechanic.preparation.field.manual"),
                FieldSpec.integer("ticks", "pvzce.mechanic.preparation.field.ticks", 0, 36_000));
    }

    @Override
    public void collectSave(LevelServer level, PreparationData data, CompoundTag root) {
        // One byte: NBT's own boolean is a byte, and the tag class here has no boolean writer
        // (nor a byte reader), so this is the shape both sides of it can hold.
        root.putByte("Preparing", (byte) (phase(level).preparing ? 1 : 0));
        root.putInt("NextPreparationWave", phase(level).nextStageWave);
        root.putInt("PreparationTicksLeft", Math.max(0, data.ticks() - level.tickCount() + phase(level).startTick));
    }

    @Override
    public void applySave(LevelServer level, PreparationData data, CompoundTag root) {
        Phase phase = phase(level);
        // A missing key is the safe reading for a save written before the level had a preparation
        // phase at all: those runs had their waves running.
        phase.preparing = root.contains("Preparing") && root.getInt("Preparing") != 0;
        if (root.contains("NextPreparationWave")) {
            phase.nextStageWave = root.getInt("NextPreparationWave");
        } else if (data.wavesPerStage() > 0) {
            phase.nextStageWave = (level.currentWave() / data.wavesPerStage() + 1) * data.wavesPerStage();
        }
        phase.startTick = root.contains("PreparationTicksLeft")
                ? level.tickCount() - data.ticks() + root.getInt("PreparationTicksLeft") : 0;
        phase.synced = false;
    }
}
