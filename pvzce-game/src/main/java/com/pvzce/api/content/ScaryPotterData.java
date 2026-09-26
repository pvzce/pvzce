package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.mechanic.MechanicData;
import com.pvzce.api.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * A lawn of vases, in rounds, with a plant or a zombie inside each.
 *
 * <p>The block of the {@code pvzce:scary_potter} level mechanic, and the original's Scary Potter
 * level - 4-5, the one adventure level with no waves at all. Three rounds, each reaching one
 * column further towards the house than the last, and the round is over when every pot is broken
 * and the zombies that came out of them are dead.
 *
 * <pre>
 * { "type": "pvzce:scary_potter",
 *   "rounds": [
 *     { "from_column": 6, "leaf_count": 0,
 *       "pots": [ { "kind": "plant", "id": "pvzce:pea_shooter", "count": 5 },
 *                 { "kind": "zombie", "id": "pvzce:basic_zombie", "count": 4 } ] } ] }
 * </pre>
 *
 * <p>What a pot holds is written as a {@code kind} rather than inferred from the id, because they
 * do different things when the pot breaks: a plant pot hands the player a card, a zombie pot puts
 * a zombie on the lawn in that cell, and a sun pot drops that resource where the pot stood.
 *
 * <p>{@code leaf_count} is the original's {@code ScaryPotterChangePotType}: that many of the
 * round's plant pots are drawn as the green leaf vase, which tells the player they hold a plant.
 * Every other pot wears the question mark, which is the whole point of the level - breaking the
 * wrong one is how a zombie gets onto the lawn.
 *
 * @param rounds the rounds, in the order they are played
 */
public record ScaryPotterData(List<Round> rounds) implements MechanicData {
    /** A pot holding a plant: breaking it hands the player that card. */
    public static final String KIND_PLANT = "plant";
    /** A pot holding a zombie: breaking it puts that zombie on the lawn. */
    public static final String KIND_ZOMBIE = "zombie";
    /**
     * A pot holding a resource drop: breaking it drops that resource on the cell.
     *
     * <p>The original's own answer to "how does the player pay for the cherry bomb in a level
     * with no sky and no producers": some of the vases hold sun. A level that wants the player to
     * have nothing to spend writes none of these, which is what the shipped tables did before
     * this kind existed - and a level whose plants all come out of pots is a level where the
     * player otherwise cannot plant anything at all.
     */
    public static final String KIND_SUN = "sun";

    public static final MapCodec<ScaryPotterData> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Round.CODEC.listOf().optionalFieldOf("rounds", List.of()).forGetter(ScaryPotterData::rounds)
    ).apply(i, ScaryPotterData::new));

    public static final Codec<ScaryPotterData> CODEC = MAP_CODEC.codec();

    public ScaryPotterData {
        rounds = rounds == null ? List.of() : List.copyOf(rounds);
    }

    /**
     * One round: where its pots may stand, and what is in them.
     *
     * @param fromColumn the leftmost column a pot may appear in; everything to the right of it
     *                   is fair game, which is how the original widens each round
     * @param leafCount  how many of the plant pots are shown as leaf pots instead of question
     *                   marks; clamped to the number of plant pots the round has
     * @param pots       what the round's pots hold, in the order they are placed
     */
    public record Round(int fromColumn, int leafCount, List<Pot> pots) {
        public static final Codec<Round> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.optionalFieldOf("from_column", 0).forGetter(Round::fromColumn),
                Codec.INT.optionalFieldOf("leaf_count", 0).forGetter(Round::leafCount),
                Pot.CODEC.listOf().optionalFieldOf("pots", List.of()).forGetter(Round::pots)
        ).apply(i, Round::new));

        public Round {
            pots = pots == null ? List.of() : List.copyOf(pots);
        }

        /** How many pots this round stands up. */
        public int potCount() {
            int total = 0;
            for (Pot pot : pots) {
                total += pot.count();
            }
            return total;
        }
    }

    /**
     * One stack of pots with the same contents.
     *
     * @param kind  {@link #KIND_PLANT}, {@link #KIND_ZOMBIE} or {@link #KIND_SUN}
     * @param id    the plant (a card), the zombie, or the resource inside
     * @param count how many pots hold it
     */
    public record Pot(String kind, Identifier id, int count) {
        public static final Codec<Pot> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.optionalFieldOf("kind", KIND_PLANT).forGetter(Pot::kind),
                Identifier.CODEC.fieldOf("id").forGetter(Pot::id),
                Codec.INT.optionalFieldOf("count", 1).forGetter(Pot::count)
        ).apply(i, Pot::new));

        public boolean isPlant() {
            return KIND_PLANT.equals(kind);
        }

        /** True when this pot holds a resource rather than a card or a zombie. */
        public boolean isResource() {
            return KIND_SUN.equals(kind);
        }
    }

    /**
     * Every problem with this block that can be seen from the shape alone.
     *
     * <p>Whether the ids resolve is asked one layer up (the plant ids go through the card
     * resolver and the zombie ids through the zombie registry, neither of which the content
     * records know about).
     */
    public List<String> validate(int width, int height) {
        List<String> errors = new ArrayList<>();
        if (rounds.isEmpty()) {
            errors.add("scary_potter declares no rounds, so there would be nothing to break");
            return errors;
        }
        for (int index = 0; index < rounds.size(); index++) {
            Round round = rounds.get(index);
            String where = "scary_potter round " + (index + 1);
            if (round.fromColumn() < 0 || round.fromColumn() >= width) {
                errors.add(where + " starts at column " + round.fromColumn()
                        + ", which is off a board " + width + " columns wide");
                continue;
            }
            int capacity = (width - round.fromColumn()) * height;
            if (round.potCount() > capacity) {
                errors.add(where + " wants " + round.potCount() + " pots, and columns "
                        + round.fromColumn() + ".." + (width - 1) + " hold " + capacity);
            }
            if (round.potCount() == 0) {
                errors.add(where + " has no pots, so it would be over before it started");
            }
            int plantPots = 0;
            for (Pot pot : round.pots()) {
                if (!KIND_PLANT.equals(pot.kind()) && !KIND_ZOMBIE.equals(pot.kind())
                        && !KIND_SUN.equals(pot.kind())) {
                    errors.add(where + " has a pot of kind '" + pot.kind() + "', and the only"
                            + " kinds are '" + KIND_PLANT + "', '" + KIND_ZOMBIE + "' and '"
                            + KIND_SUN + "'");
                }
                if (pot.count() <= 0) {
                    errors.add(where + " asks for " + pot.count() + " of " + pot.id());
                }
                if (pot.isPlant()) {
                    plantPots += pot.count();
                }
            }
            if (round.leafCount() > plantPots) {
                errors.add(where + " marks " + round.leafCount() + " pots as leaf pots, and only "
                        + plantPots + " of them hold a plant");
            }
        }
        return errors;
    }
}
