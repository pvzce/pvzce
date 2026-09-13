package com.pvzce.common.level.mechanic;

import com.mojang.serialization.MapCodec;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.mechanic.MechanicData;

import java.util.List;

/**
 * The ordinary card bar: cards the level fixes, cards the player picks, sun to pay for
 * them, cooldowns to wait out.
 *
 * <p>This is the default. A level that declares no card source runs with it, so an
 * ordinary level's JSON does not carry a block that only says "the normal rules apply" -
 * the absence <em>is</em> the statement. That asymmetry with
 * {@link ConveyorMechanic the conveyor} is deliberate: it keeps every level written
 * before mechanics existed byte-identical, and it is decided in exactly one place
 * ({@link LevelMechanics#cardSource}).
 *
 * <p>The mechanic carries no data of its own. The cards, the starting sun and the
 * resource unlocks stay where they have always been - {@code slots},
 * {@code max_seed_slots}, {@code initial_sun}, {@code unlock_resources} - because they
 * are the data of the <em>default</em> card source, and moving them inside a block would
 * touch every data pack, the seed chooser and the editor's card page for no gain.
 */
public final class DeckMechanic implements LevelMechanic<MechanicData.Empty> {
    public static final MapCodec<MechanicData.Empty> CODEC = MapCodec.unit(MechanicData.Empty.INSTANCE);

    @Override
    public MapCodec<MechanicData.Empty> codec() {
        return CODEC;
    }

    @Override
    public boolean cardSource() {
        return true;
    }

    @Override
    public com.pvzce.server.level.cardsource.CardSource createCardSource(
            com.pvzce.server.level.cardsource.CardSource.Context context, MechanicData.Empty data) {
        return new com.pvzce.server.level.cardsource.DeckCardSource(context);
    }

    /**
     * The deck's own rules: it must have cards, and they must exist.
     *
     * <p>They live here rather than in one level-wide card check because "what a deck needs"
     * is the deck's business: the belt validates its pool instead, and neither the level
     * record nor the validator has to know what a card source is.
     */
    @Override
    public List<String> validate(LevelDef def, MechanicData.Empty data) {
        if (def.slots().isEmpty()) {
            return List.of("This level has no cards, so the player enters with an empty card bar");
        }
        return LevelMechanics.unknownCards(def.slots(), "card");
    }
}
