package com.pvzce.common.level.mechanic;

import com.mojang.serialization.MapCodec;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.VaseFieldData;
import com.pvzce.common.core.SlotResolver;
import com.pvzce.server.level.LevelServer;

import java.util.ArrayList;
import java.util.List;

/**
 * The {@code pvzce:vase_field} mechanic: vases the level stands up at the start, with a card
 * inside each.
 *
 * <p>The scene half of a vase is written through {@code LevelServer.fillVase}, which is the same
 * call the vase tool's own placement path makes - a vase the level put there and a vase the
 * player put there are the same object, and the two must not be able to disagree about what a
 * vase cell looks like. What is inside each one is the level's save's business from then on: the
 * scene block and the vase-contents block are written together, so a resumed run comes back with
 * the vases it had left, and the ones it broke are gone.
 */
public final class VaseFieldMechanic implements LevelMechanic<VaseFieldData> {
    @Override
    public MapCodec<VaseFieldData> codec() {
        return VaseFieldData.MAP_CODEC;
    }

    @Override
    public List<String> validate(LevelDef def, VaseFieldData data) {
        List<String> errors = new ArrayList<>(data.validate(def.width(), def.height()));
        for (VaseFieldData.Vase vase : data.vases()) {
            // A card, not a plant: the bar is what a broken vase hands the player, so this is the
            // same resolution `addCardToBar` will do - and a vase holding a card that resolves to
            // nothing is a vase the player breaks open for no reward.
            if (SlotResolver.resolve(vase.card()).isEmpty()) {
                errors.add("vase at (" + vase.x() + "," + vase.y() + ") holds '" + vase.card()
                        + "', which is not a card (no slot or plant of that id exists), so"
                        + " breaking it would give the player nothing");
            }
        }
        return errors;
    }

    /**
     * Stands the vases up when the level is built.
     *
     * <p>Here rather than on the first tick, exactly as {@link GraveFieldMechanic} does it and for
     * the same reason: the board the client's first snapshot draws, the one the seed chooser
     * previews and the one {@code canPlacePlant} answers about are all the same board, and it is
     * finished before anything can look at it.
     */
    @Override
    public void onLevelCreated(LevelServer level, VaseFieldData data) {
        for (VaseFieldData.Vase vase : data.vases()) {
            level.fillVase(vase.x(), vase.y(), vase.card());
        }
    }
}
