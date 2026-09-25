package com.pvzce.common.level.mutation;

import com.mojang.serialization.MapCodec;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.mechanic.MechanicData;

/**
 * The mutation system, declared: "this level rewrites itself as it runs".
 *
 * <p>A marker mechanic with no block, exactly like {@code pvzce:deck}: the catalogue is code and
 * the schedule is the difficulty tier's, so there is nothing for a level file to fill in beyond
 * saying that it wants any of this. What it does is what every marker does - it makes the client's
 * level payload and the editor's page list answer a question they could not otherwise answer, and
 * it is how {@code LevelServer} knows to build a {@code MutationManager} at all.
 *
 * <p>The mutation timer lives in the manager rather than here: a registered mechanic is one shared
 * instance across every level, so anything per-run belongs to the level (see {@code MutationManager}).
 */
public final class MutationMechanic implements com.pvzce.common.level.mechanic.LevelMechanic<MechanicData.Empty> {
    /** A marker block: {@code {"type": "pvzce:mutation"}} and nothing else. */
    public static final MapCodec<MechanicData.Empty> CODEC = MapCodec.unit(MechanicData.Empty.INSTANCE);

    @Override
    public MapCodec<MechanicData.Empty> codec() {
        return CODEC;
    }

    @Override
    public java.util.List<String> validate(LevelDef def, MechanicData.Empty data) {
        java.util.List<String> errors = new java.util.ArrayList<>();
        String difficulty = null;
        com.google.gson.JsonElement configured = def.rules().get(
                com.pvzce.common.PvzceIds.RULE_MUTATION_DIFFICULTY);
        if (configured != null && configured.isJsonPrimitive()) {
            difficulty = configured.getAsString();
            if (!MutationDifficulty.isKnown(difficulty)) {
                errors.add("mutation_difficulty '" + difficulty
                        + "' is not one of easy / normal / hard / hell; the middle tier is used");
            }
        }
        if (MutationRegistry.all().isEmpty()) {
            errors.add("this level mutates but no mutation is registered, so nothing would ever"
                    + " happen to it");
        }
        return errors;
    }
}
