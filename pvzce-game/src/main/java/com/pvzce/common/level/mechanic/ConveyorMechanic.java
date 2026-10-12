package com.pvzce.common.level.mechanic;

import com.pvzce.api.content.LevelBelt;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.mechanic.FieldSpec;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.core.SlotResolver;
import com.mojang.serialization.MapCodec;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A conveyor belt, the card source Wall-nut Bowling is built on.
 *
 * <p>The belt hands out free cards at a fixed rate and stops while it is full, so a level
 * with this mechanic has no seed chooser, no sun and no prices. The block is
 * {@link LevelBelt}, which was a top-level {@code "conveyor"} key before mechanics
 * existed.
 */
public final class ConveyorMechanic implements LevelMechanic<LevelBelt> {
    /** Expand at level creation, after built-in and mod plants have all been registered. */
    public static LevelBelt expandPlants(LevelBelt data) {
        if (!data.allPlants()) return data;
        List<LevelBelt.BeltCard> cards = new ArrayList<>(data.cards());
        Set<Identifier> included = new HashSet<>();
        for (var card : cards) {
            SlotResolver.resolve(card.card()).ifPresent(slot -> included.add(slot.content()));
        }
        BuiltInRegistries.PLANTS.keySet().stream().sorted(Comparator.comparing(Identifier::toString))
                .filter(id -> !included.contains(id))
                .forEach(id -> cards.add(new LevelBelt.BeltCard(id, 1, LevelBelt.BeltCard.UNLIMITED)));
        return new LevelBelt(data.intervalTicks(), data.capacity(), data.initialCards(), cards);
    }

    @Override
    public MapCodec<LevelBelt> codec() {
        return LevelBelt.MAP_CODEC;
    }

    @Override
    public boolean cardSource() {
        return true;
    }

    @Override
    public boolean dealsItsOwnCards() {
        return true;
    }

    @Override
    public com.pvzce.server.level.cardsource.CardSource createCardSource(
            com.pvzce.server.level.cardsource.CardSource.Context context, LevelBelt data) {
        LevelBelt belt = context.level() == null ? RandomPlantsMechanic.belt(context.def(), data)
                : RandomPlantsMechanic.belt(context.level(), data);
        return new com.pvzce.server.level.cardsource.BeltCardSource(context, belt);
    }

    /**
     * What the belt streams as it changes: the whole card bar, by card id.
     *
     * <p>Declared once here and used by both sides - the server encodes with it, the client
     * decodes with it - so a field added to the bar cannot arrive on one side only. It is a
     * whole-table replacement rather than a per-card upsert because an upsert cannot say
     * "this card is gone": a spent belt card would sit in the client's bar looking usable.
     */
    public record BarState(List<com.pvzce.common.network.packet.SlotInfo> cards) {
        public static final com.pvzce.common.network.PacketStruct.Codec<BarState> CODEC =
                com.pvzce.common.network.PacketStruct.<BarState>builder()
                        .list(BarState::cards,
                                com.pvzce.common.network.packet.SlotInfo::encode,
                                com.pvzce.common.network.packet.SlotInfo::decode)
                        .build(values -> new BarState(castCards(values.get(0))));

        @SuppressWarnings("unchecked")
        private static List<com.pvzce.common.network.packet.SlotInfo> castCards(Object value) {
            return (List<com.pvzce.common.network.packet.SlotInfo>) value;
        }
    }

    @Override
    public List<String> validate(LevelDef def, LevelBelt data) {
        data = expandPlants(data);
        List<String> errors = new ArrayList<>(data.validate());
        // Two answers to "what is in the card bar" is one answer too many: a *plant* card the level
        // lists is never granted on a belt level - the belt deals the plants - so listing one is a
        // data mistake the author should hear about rather than a rule that quietly ignores half
        // the file.
        //
        // Tools and resources are the other half of that, and they are deliberately allowed: the
        // belt only ever deals plants, and `BeltCardSource` keeps everything else on the bar beside
        // it, exactly as it does on a deck level. The shovel is what the rule is for in practice -
        // a belt level with no shovel has no way to fix a misplaced plant - so refusing every card
        // would refuse the one card a belt level actually needs. See `BeltCardSource#rebuildBar`.
        List<com.pvzce.api.util.Identifier> plantCards = new ArrayList<>();
        for (com.pvzce.api.util.Identifier slot : def.slots()) {
            if (slot == null) {
                continue;
            }
            var resolved = com.pvzce.common.core.SlotResolver.resolve(slot);
            if (resolved.isEmpty() || resolved.get().kind() == com.pvzce.common.core.Slot.Kind.PLANT) {
                plantCards.add(slot);
            }
        }
        if (!plantCards.isEmpty()) {
            errors.add("This level has a conveyor belt and also lists " + plantCards.size()
                    + " plant cards: the belt deals the plants, so the listed ones are never granted"
                    + " (tools and resources are kept on the bar)");
        }
        List<com.pvzce.api.util.Identifier> cards = new ArrayList<>();
        for (LevelBelt.BeltCard card : data.cards()) {
            cards.add(card.card());
        }
        errors.addAll(LevelMechanics.unknownCards(cards, "conveyor card"));
        return errors;
    }

    /**
     * The belt's own fields, described as data.
     *
     * <p>Declared here rather than in a hand-written editor page: this is what "register a
     * mechanic and get an editor page" means in practice, and it is the fix for the belt
     * having had no UI at all since it was added.
     */
    @Override
    public List<FieldSpec> editorFields() {
        return List.of(
                FieldSpec.integer("interval_ticks", "pvzce.mechanic.conveyor.field.interval_ticks", 1, 6000),
                FieldSpec.integer("capacity", "pvzce.mechanic.conveyor.field.capacity",
                        1, LevelBelt.MAX_CAPACITY),
                FieldSpec.integer("initial_cards", "pvzce.mechanic.conveyor.field.initial_cards",
                        0, LevelBelt.MAX_CAPACITY),
                FieldSpec.bool("all_plants", "pvzce.mechanic.conveyor.field.all_plants"),
                // The pool is edited as one line of card ids. Per-card weights are part of the
                // format and are honoured on load, but the editor has no weighted-list widget
                // yet, so a hand-written weight survives an edit rather than being shown wrong.
                new FieldSpec.Ref("cards", "pvzce.mechanic.conveyor.field.cards", "slot", true));
    }
}
