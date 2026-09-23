package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.util.Identifier;

import java.util.List;
import java.util.Optional;

/**
 * What a level pays out, declared in its own JSON under {@code rewards}.
 *
 * <p>Three separate things used to be one concept ("the level ended"), and none
 * of them existed at all: a first clear unlocks a card, a repeat clear pays a
 * coin stipend, and a zombie can drop a coin while the level runs. They are kept
 * apart here because each answers a different question - what the backpack gains,
 * what the wallet gains on a replay, and what a run can pick up.
 *
 * <p>The defaults are the standard stipend: any level pays
 * {@value #DEFAULT_REPEAT_COINS} coins when replayed (declare {@code "repeat": []}
 * to opt out), and every zombie drops a silver coin with a
 * {@value #DEFAULT_COIN_DROP_CHANCE} chance. First clears pay nothing extra until
 * a level declares an {@code unlock}, a {@code coins} bounty or a {@code resource}.
 */
public record LevelRewards(
        List<Reward> firstClear,
        List<Reward> repeat,
        float coinDropChance,
        Identifier coinDrop,
        int coinDropAmount
) {
    /** Replay stipend used when a level does not declare {@code repeat}. */
    public static final int DEFAULT_REPEAT_COINS = 100;
    /** Chance that a dying zombie leaves a coin, unless the level overrides it. */
    public static final float DEFAULT_COIN_DROP_CHANCE = 0.25F;
    /**
     * Which denomination a zombie drops by default: one silver coin, worth 10.
     *
     * <p>A resource id rather than an enum so a pack can drop its own currency, and so
     * the worth comes from the resource's own {@code default_value} instead of being
     * written a second time here (the original's ladder is silver 10, gold 50,
     * diamond 1000, money bag 250).
     */
    public static final Identifier DEFAULT_COIN_DROP = Identifier.withDefaultNamespace("coin_silver");
    /** Coins in one zombie drop, unless the level overrides it. */
    public static final int DEFAULT_COIN_DROP_AMOUNT = 1;

    public static final List<Reward> DEFAULT_REPEAT = List.of(Reward.coins(DEFAULT_REPEAT_COINS));

    /** What a level that declares no {@code rewards} block gets. */
    public static final LevelRewards DEFAULT = new LevelRewards(List.of(), DEFAULT_REPEAT,
            DEFAULT_COIN_DROP_CHANCE, DEFAULT_COIN_DROP, DEFAULT_COIN_DROP_AMOUNT);

    /** A level that pays nothing and drops nothing; used by tests and by opt-outs. */
    public static final LevelRewards NONE =
            new LevelRewards(List.of(), List.of(), 0F, DEFAULT_COIN_DROP, 0);

    public LevelRewards {
        firstClear = List.copyOf(firstClear);
        repeat = List.copyOf(repeat);
        coinDropChance = Math.max(0F, Math.min(1F, coinDropChance));
        coinDrop = coinDrop == null ? DEFAULT_COIN_DROP : coinDrop;
        coinDropAmount = Math.max(0, coinDropAmount);
    }

    /** True when a dying zombie can leave a coin at all. */
    public boolean hasCoinDrops() {
        return coinDropChance > 0F && coinDropAmount > 0;
    }

    /**
     * One payout entry.
     *
     * <p>{@code type} is the discriminator: {@code "unlock"} carries {@code id},
     * {@code "coins"} carries {@code amount}, {@code "resource"} carries both - {@code amount}
     * of the resource {@code id}. An unknown type is not an error the codec can see, so
     * {@link com.pvzce.server.level.LevelValidator} reports it.
     *
     * <p>{@code buff} hands over a level buff: {@code id} names one from
     * {@code PvzceRegistries.LEVEL_BUFFS}, and the player may then switch it on in any level that
     * offers a choice. It is a separate type rather than an {@code unlock} because the two are
     * different bags - a card id and a buff id can look alike, and a level that meant to hand over
     * a plant must not quietly hand over a rule instead. Like {@code unlock} it is idempotent and
     * is paid on <em>any</em> clear that finds the buff still missing, so a world that cleared the
     * level before the reward existed still gets it.
     *
     * <p>{@code resource} is the third shape because a wallet can only hold one kind of
     * thing: a level that hands over a diamond hands over a coin worth 1000, and the two
     * halves are not interchangeable - "1000 coins" is a number, "a diamond" is an object
     * the award page draws. The generic form keeps the worth in the resource definition
     * ({@code default_value}), where the whole denomination ladder already lives, instead of
     * writing 1000 a second time in a level file.
     */
    public record Reward(String type, Optional<Identifier> id, int amount) {
        public static final String TYPE_UNLOCK = "unlock";
        public static final String TYPE_COINS = "coins";
        public static final String TYPE_RESOURCE = "resource";
        public static final String TYPE_BUFF = "buff";

        public static final Codec<Reward> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("type").forGetter(Reward::type),
                Identifier.CODEC.optionalFieldOf("id").forGetter(Reward::id),
                Codec.INT.optionalFieldOf("amount", 0).forGetter(Reward::amount)
        ).apply(i, Reward::new));

        public static Reward unlock(Identifier id) {
            return new Reward(TYPE_UNLOCK, Optional.of(id), 0);
        }

        public static Reward coins(int amount) {
            return new Reward(TYPE_COINS, Optional.empty(), amount);
        }

        /** {@code amount} of one resource; the wallet is credited what it is worth. */
        public static Reward resource(Identifier id, int amount) {
            return new Reward(TYPE_RESOURCE, Optional.of(id), amount);
        }

        /** One level buff the player may now switch on. */
        public static Reward buff(Identifier id) {
            return new Reward(TYPE_BUFF, Optional.of(id), 0);
        }

        public boolean isUnlock() {
            return TYPE_UNLOCK.equals(type);
        }

        public boolean isCoins() {
            return TYPE_COINS.equals(type);
        }

        public boolean isResource() {
            return TYPE_RESOURCE.equals(type);
        }

        public boolean isBuff() {
            return TYPE_BUFF.equals(type);
        }

        /** True when this entry names something the codec could not carry out. */
        public boolean isMalformed() {
            if (isUnlock() || isBuff()) {
                return id.isEmpty();
            }
            if (isCoins()) {
                return amount <= 0;
            }
            if (isResource()) {
                return id.isEmpty() || amount <= 0;
            }
            return true;
        }
    }

    public static final Codec<LevelRewards> CODEC = RecordCodecBuilder.create(i -> i.group(
            Reward.CODEC.listOf().optionalFieldOf("first_clear", List.of()).forGetter(LevelRewards::firstClear),
            Reward.CODEC.listOf().optionalFieldOf("repeat", DEFAULT_REPEAT).forGetter(LevelRewards::repeat),
            Codec.FLOAT.optionalFieldOf("coin_drop_chance", DEFAULT_COIN_DROP_CHANCE)
                    .forGetter(LevelRewards::coinDropChance),
            Identifier.CODEC.optionalFieldOf("coin_drop", DEFAULT_COIN_DROP)
                    .forGetter(LevelRewards::coinDrop),
            Codec.INT.optionalFieldOf("coin_drop_amount", DEFAULT_COIN_DROP_AMOUNT)
                    .forGetter(LevelRewards::coinDropAmount)
    ).apply(i, LevelRewards::new));
}
