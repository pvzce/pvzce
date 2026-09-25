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
public final class MutationMechanic
        implements com.pvzce.common.level.mechanic.LevelMechanic<com.pvzce.api.content.MutationData> {
    /**
     * The block: whether this level also rolls its mutations, and which ones it stages by hand.
     *
     * <p>It was a bare marker until the tutorial level needed a script; {@code MutationData}
     * documents the two fields and why they are independent.
     */
    public static final MapCodec<com.pvzce.api.content.MutationData> CODEC =
            com.pvzce.api.content.MutationData.MAP_CODEC;

    @Override
    public MapCodec<com.pvzce.api.content.MutationData> codec() {
        return CODEC;
    }

    @Override
    public java.util.List<String> validate(LevelDef def, com.pvzce.api.content.MutationData data) {
        java.util.List<String> errors = new java.util.ArrayList<>(data.validate());
        for (com.pvzce.api.content.MutationData.Planned planned : data.schedule()) {
            if (com.pvzce.common.level.mutation.MutationRegistry.get(planned.id()) == null) {
                errors.add("scheduled mutation '" + planned.id() + "' is not registered, so it"
                        + " would never arrive");
            }
        }
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
