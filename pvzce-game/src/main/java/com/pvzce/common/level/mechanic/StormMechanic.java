package com.pvzce.common.level.mechanic;

import com.mojang.serialization.MapCodec;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.StormData;
import com.pvzce.api.content.mechanic.FieldSpec;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.common.network.packet.MechanicSyncS2C;
import com.pvzce.server.level.LevelServer;

import java.util.List;

/**
 * The thunderstorm over 4-10: a stage that goes black and lightning that keeps lighting it up.
 *
 * <p>Almost all of the feature is on the client, exactly like the fog's: what is dark, what is
 * hidden and how a strike flickers are questions about the picture. What lives here is the one
 * thing only the server can answer - <b>where the storm is in its cycle</b> - and the one job of
 * telling the client about it.
 *
 * <p>The state is a counter, and what travels is the brightness that follows from it: the server
 * sends {@code (pulse, exposure)} a tick - which strike this is and how lit the stage is - and
 * both the picture and the hiding test read that same number, so the two cannot disagree about
 * whether the lawn is readable. The shape of a strike stays one table in one place
 * ({@link StormState#PATTERNS}), which is also why a resumed run draws the strike it would have
 * drawn: the table is indexed by the pulse, and the pulse is in the save.
 *
 * <p><b>Nothing here touches a rule, a hit test or a spawn.</b> A storm is a fact about what the
 * player can see; a plant in the dark shoots exactly as far and a zombie in the dark walks exactly
 * as fast. The whole feature is one counter, one packet a tick, and a picture.
 */
public final class StormMechanic implements LevelMechanic<StormData> {
    @Override
    public MapCodec<StormData> codec() {
        return StormData.MAP_CODEC;
    }

    @Override
    public List<String> validate(LevelDef def, StormData data) {
        return data.validate();
    }

    @Override
    public List<FieldSpec> editorFields() {
        return List.of(
                new FieldSpec.Number("interval_ticks", "pvzce.mechanic.storm.field.interval_ticks",
                        StormData.MIN_INTERVAL_TICKS, 7200, true),
                new FieldSpec.Number("flash_ticks", "pvzce.mechanic.storm.field.flash_ticks",
                        StormData.MIN_FLASH_TICKS, 1200, true),
                new FieldSpec.Number("max_alpha", "pvzce.mechanic.storm.field.max_alpha",
                        0F, 1F, false));
    }

    @Override
    public void onLevelCreated(LevelServer level, StormData data) {
        // No publish: the level's first snapshot carries whatever the slot holds, so there is
        // nobody listening yet. Same arrangement as the fog's.
        level.setMechanicState(PvzceIds.MECHANIC_STORM, data.openingState());
    }

    @Override
    public void tick(LevelServer level, StormData data) {
        StormState current = stateOf(level, data);
        StormState next = current.tick() + 1 >= data.intervalTicks()
                ? StormState.at(current.pulse() + 1, 0)
                : current.advanced();
        level.setMechanicState(PvzceIds.MECHANIC_STORM, next);
        // The brightness travels, not the cycle position: the client draws and hides with the
        // number the server decided on, and the shape of a strike stays one table in one place.
        level.send(MechanicSyncS2C.of(PvzceIds.MECHANIC_STORM, StormState.Wire.CODEC,
                StormState.Wire.of(next, data)));
    }

    /**
     * The storm's state as the level holds it, falling back to the opening strike.
     *
     * <p>A level restored from a save written before the block existed reads zero and starts
     * striking from that tick, which is the harmless direction: the storm is presentation, and
     * "the storm restarts" is not a run the player can lose.
     */
    public static StormState stateOf(LevelServer level, StormData data) {
        StormState state = level.mechanicStateOrNull(PvzceIds.MECHANIC_STORM, StormState.class);
        return state == null ? data.openingState() : state;
    }

    @Override
    public void collectSave(LevelServer level, StormData data, CompoundTag root) {
        StormState state = stateOf(level, data);
        root.putLong("StormPulse", state.pulse());
        root.putInt("StormTick", state.tick());
    }

    @Override
    public void applySave(LevelServer level, StormData data, CompoundTag root) {
        level.setMechanicState(PvzceIds.MECHANIC_STORM,
                StormState.at(root.getLong("StormPulse"), root.getInt("StormTick")));
    }
}
