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
        boolean manual = true;
        int ticks;
        boolean refund = true;
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
        phase.manual = data.manual();
        phase.ticks = data.ticks();
        phase.refund = data.refund();
        begin(level);
    }

    @Override
    public void tick(LevelServer level, PreparationData data) {
        Phase phase = phase(level);
        if (!phase.preparing) {
            // Nothing left to do but keep the client's copy honest after a resume.
            sync(level, phase);
            return;
        }
        if (!data.manual() && level.tickCount() >= data.ticks()) {
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

    /** Starts (or restarts) the phase. The client hears about it on the next tick. */
    public static void begin(LevelServer level) {
        Phase phase = phase(level);
        phase.preparing = true;
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
    }

    @Override
    public void applySave(LevelServer level, PreparationData data, CompoundTag root) {
        Phase phase = phase(level);
        // A missing key is the safe reading for a save written before the level had a preparation
        // phase at all: those runs had their waves running.
        phase.preparing = root.contains("Preparing") && root.getInt("Preparing") != 0;
        phase.synced = false;
    }
}
