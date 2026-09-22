package com.pvzce.common.level.mechanic;

import com.mojang.serialization.MapCodec;
import com.pvzce.api.content.GraveFieldData;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.mechanic.FieldSpec;
import com.pvzce.api.util.Identifier;
import com.pvzce.server.level.LevelServer;

import java.util.ArrayList;
import java.util.List;

/**
 * The original's night lawns: tombstones standing in the far half when the level starts.
 *
 * <p>Every night level opens with graves, in a layout that is different every time. The file
 * cannot write that down as {@code scene} cells - that would be one fixed layout, and the
 * point is that the player has to read the lawn again each attempt - so the cells are chosen
 * here, from the level's own random source, on the tick the level is built.
 *
 * <p>What they do afterwards is not this mechanic's business. They block planting because
 * that is what a grave's terrain is (the same {@code #c:unplantable} rule as always), and
 * they give up their dead at the final wave because every night level has
 * {@code graves_spawn_night} on - see {@code LevelServer.riseGraveZombies}. This mechanic only
 * puts them there, which is why it has no run state and nothing to save: the board it left
 * behind <em>is</em> the state, and the save writes the board.
 */
public final class GraveFieldMechanic implements LevelMechanic<GraveFieldData> {
    @Override
    public MapCodec<GraveFieldData> codec() {
        return GraveFieldData.MAP_CODEC;
    }

    @Override
    public List<String> validate(LevelDef def, GraveFieldData data) {
        List<String> errors = new ArrayList<>(data.validate(def.width(), def.height()));
        for (Identifier design : data.designs()) {
            if (!GraveScatter.isGraveElement(design)) {
                errors.add("grave_field design '" + design + "' is not a gravestone scene element"
                        + " (or does not exist), so it would never be drawn");
            }
        }
        return errors;
    }

    @Override
    public List<FieldSpec> editorFields() {
        return List.of(
                FieldSpec.integer("count", "pvzce.mechanic.grave_field.field.count", 0, 81),
                FieldSpec.integer("min_x", "pvzce.mechanic.grave_field.field.min_x",
                        GraveFieldData.MIN_X_UNSET, 64),
                FieldSpec.integer("max_x", "pvzce.mechanic.grave_field.field.max_x",
                        GraveFieldData.MAX_X_UNSET, 64));
    }

    /**
     * Scatters the whole field at once, when the level is built.
     *
     * <p>Here rather than on the first tick so the opening board is complete before anything
     * can look at it: the client's first snapshot, the level's own validation and any
     * "is this cell plantable" question all see the finished lawn, and the seed chooser's
     * preview draws the same board the run will start on.
     */
    @Override
    public void onLevelCreated(LevelServer level, GraveFieldData data) {
        GraveScatter.place(level, data.minXFor(level.width()), data.maxXFor(level.width()),
                data.designs(), data.count(), 0);
    }
}
