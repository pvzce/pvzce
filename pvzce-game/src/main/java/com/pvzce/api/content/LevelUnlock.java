package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.util.Identifier;

import java.util.List;
import java.util.Optional;

/**
 * What a level requires before it can be played, declared in its own JSON under
 * {@code unlock}.
 *
 * <p>Nothing gated a level before this: the server sent every registered level to
 * the client and every row was playable, so "progression" was whatever order the
 * player happened to pick. A level that declares no {@code unlock} block stays
 * exactly that way - always playable - which is what keeps existing custom levels
 * working after this was introduced.
 *
 * <p>Three shapes are supported, and all of the declared {@code requires} entries
 * must hold:
 *
 * <pre>{@code
 * "unlock": {
 *   "requires": [
 *     { "type": "level", "id": "pvzce:yard/adventure/1_1" },
 *     { "type": "card",  "id": "pvzce:sunflower" },
 *     { "type": "coins", "amount": 500 }
 *   ],
 *   "cost": 1000,
 *   "hidden": false
 * }
 * }</pre>
 *
 * <p>{@code cost} is a one-off purchase: a locked level with a cost and a high
 * enough wallet can be bought, and the purchase is recorded in the world's profile
 * so it never has to be paid again. {@code hidden} keeps the level out of the list
 * entirely until it is unlocked, for levels meant to be discovered.
 *
 * <p>Like {@link LevelRewards}, {@code type} is a string discriminator that the
 * codec cannot validate - {@code "lvel"} decodes happily and then means nothing -
 * so {@link com.pvzce.server.level.LevelValidator} reports unknown types and
 * malformed entries.
 */
public record LevelUnlock(List<Requirement> requires, Optional<Integer> cost, boolean hidden) {
    /** A level with no unlock block: playable immediately. */
    public static final LevelUnlock NONE = new LevelUnlock(List.of(), Optional.empty(), false);

    public static final Codec<LevelUnlock> CODEC = RecordCodecBuilder.create(i -> i.group(
            Requirement.CODEC.listOf().optionalFieldOf("requires", List.of())
                    .forGetter(LevelUnlock::requires),
            // Zero and negative costs mean the same as no cost, so they are normalised
            // away rather than left as "buyable for free".
            Codec.INT.optionalFieldOf("cost").forGetter(LevelUnlock::cost),
            Codec.BOOL.optionalFieldOf("hidden", false).forGetter(LevelUnlock::hidden)
    ).apply(i, LevelUnlock::new));

    public LevelUnlock {
        requires = List.copyOf(requires);
        cost = cost.filter(amount -> amount > 0);
    }

    /** True when this level is playable without checking anything. */
    public boolean isOpen() {
        return requires.isEmpty() && cost.isEmpty();
    }

    /** True when a locked level can be bought outright. */
    public boolean isBuyable() {
        return cost.isPresent();
    }

    /**
     * One unlock requirement.
     *
     * @param type   {@code "level"}, {@code "card"} or {@code "coins"}
     * @param id     the level or card this names; empty for {@code coins}
     * @param amount how many coins {@code coins} needs, or how many times a
     *               {@code level} must have been cleared (always 1 in practice, but
     *               keeping it in the format means "clear it three times" does not
     *               need a new type)
     */
    public record Requirement(String type, Optional<Identifier> id, int amount) {
        /** Another level must have been cleared. */
        public static final String TYPE_LEVEL = "level";
        /** A card must be in the player's backpack. */
        public static final String TYPE_CARD = "card";
        /** The wallet must hold at least this many coins. */
        public static final String TYPE_COINS = "coins";

        public static final Codec<Requirement> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("type").forGetter(Requirement::type),
                Identifier.CODEC.optionalFieldOf("id").forGetter(Requirement::id),
                Codec.INT.optionalFieldOf("amount", 1).forGetter(Requirement::amount)
        ).apply(i, Requirement::new));

        /** A prerequisite level. */
        public static Requirement level(Identifier levelId) {
            return new Requirement(TYPE_LEVEL, Optional.of(levelId), 1);
        }

        /** A required card. */
        public static Requirement card(Identifier cardId) {
            return new Requirement(TYPE_CARD, Optional.of(cardId), 1);
        }

        /** A coin threshold. */
        public static Requirement coins(int amount) {
            return new Requirement(TYPE_COINS, Optional.empty(), amount);
        }

        public boolean isLevel() {
            return TYPE_LEVEL.equals(type);
        }

        public boolean isCard() {
            return TYPE_CARD.equals(type);
        }

        public boolean isCoins() {
            return TYPE_COINS.equals(type);
        }

        /** Every type this build understands; the validator reports anything else. */
        public static List<String> types() {
            return List.of(TYPE_LEVEL, TYPE_CARD, TYPE_COINS);
        }
    }
}
