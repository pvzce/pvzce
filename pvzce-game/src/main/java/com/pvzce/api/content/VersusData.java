package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.mechanic.FieldSpec;
import com.pvzce.api.content.mechanic.MechanicData;
import com.pvzce.api.util.Identifier;

import java.util.List;

/**
 * 对战: everything one level has to say about a human playing against a Jev-driven opponent.
 *
 * <p>The mode is one block on purpose. A versus level is not "an ordinary level with an AI bolted
 * on": both sides are opponents, the plant side wins on a clock that is not the wave director's,
 * the zombie side has an income no wave level has ever needed, and the two decks are chosen by the
 * level rather than by the player. Each of those is a field here, and the mechanic reads them all
 * from one declaration - a level author writes the matchup once.
 *
 * <p><b>The race.</b> The plant side wins by collecting {@link #sunGoal} sun; the zombie side wins
 * by breaking through (the ordinary {@code zombieReachedLeft}). Collection means <em>picked up</em>:
 * sun that arrives on the lawn and is clicked or auto-collected counts, the opening grant does not.
 * That is the literal reading of "collect N sun", and it is also the only reading that cannot be
 * gamed by handing both sides a bigger starting purse.
 *
 * <p><b>Both sides spend sun.</b> The plant side earns it the way it always has (the sky, and
 * sunflowers it plants). The zombie side has no producers, so it is paid twice:
 * {@link #zombieIncomeSun} every {@link #zombieIncomeTicks}, and {@link #eatRefundPercent} percent
 * of the sun cost of every plant its zombies finish eating. The second is what makes aggression
 * self-funding - a zombie side that eats nothing starves, and a plant side that lets a lane be
 * eaten is paying for the next zombie.
 *
 * <p><b>Decks are the level's.</b> {@link #plantCards} and {@link #zombieCards} are each side's
 * bar. The human gets their own side's; the opponent's side is driven from the other list. A versus
 * level therefore shows no card screen (see {@code LevelDef.seedScreen}) - there is nothing to
 * choose, and a chooser over a deck that is about to be replaced would be a lie. The plant list
 * must contain the {@code pvzce:sun} resource card, or the plant side can never pick up the sun it
 * is supposed to be racing for; {@link #validate} says so rather than letting it be discovered
 * twenty minutes into a match.
 *
 * @param sunGoal           the sun the plant side must collect to win, or {@link #NO_GOAL} for a
 *                          level that only ends when somebody breaks through
 * @param plantInitialSun   the sun the plant side starts with, whoever plays it
 * @param zombieInitialSun  the sun the zombie side starts with, whoever plays it
 * @param zombieIncomeSun   sun paid to the zombie side every {@link #zombieIncomeTicks}
 * @param zombieIncomeTicks how often that payment lands
 * @param eatRefundPercent  percent of a eaten plant's sun cost paid to the side that ate it
 * @param decisionTicks     how often the opponent is asked what to do next
 * @param zombieStartTicks  how long the zombie side may not act at all while the plant side builds
 * @param plantCards        the plant side's bar, in card order
 * @param zombieCards       the zombie side's bar, in card order
 */
public record VersusData(
        int sunGoal,
        int plantInitialSun,
        int zombieInitialSun,
        int zombieIncomeSun,
        int zombieIncomeTicks,
        int eatRefundPercent,
        int decisionTicks,
        int zombieStartTicks,
        List<Identifier> plantCards,
        List<Identifier> zombieCards) implements MechanicData {

    /** {@link #sunGoal} for a level whose plant side has no sun total to reach. */
    public static final int NO_GOAL = 0;

    /** Three seconds: the pacing the mode shipped with, and the default for a level that omits it. */
    public static final int DEFAULT_DECISION_TICKS = 180;
    /** Four seconds between the zombie side's payments. */
    public static final int DEFAULT_INCOME_TICKS = 240;
    public static final int DEFAULT_INCOME_SUN = 25;
    public static final int DEFAULT_EAT_REFUND_PERCENT = 50;
    public static final int DEFAULT_PLANT_INITIAL_SUN = 50;
    public static final int DEFAULT_ZOMBIE_INITIAL_SUN = 150;
    /**
     * The build window: twenty seconds of quiet before the zombie side may act.
     *
     * <p>The original never opens a level with a zombie already on the lawn, and a mode where the
     * other side may place from the first tick has no such moment - which the first balance run
     * showed plainly: matches ended inside two minutes with the plant side unable to afford
     * anything. The window is the plant side's whole opening: sun, sunflowers, one defender. The
     * zombie side's income clock starts when it ends, so the pressure ramps from there rather than
     * arriving all at once.
     */
    public static final int DEFAULT_ZOMBIE_START_TICKS = 20 * 60;

    public static final MapCodec<VersusData> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.INT.optionalFieldOf("sun_goal", NO_GOAL).forGetter(VersusData::sunGoal),
            Codec.INT.optionalFieldOf("plant_initial_sun", DEFAULT_PLANT_INITIAL_SUN)
                    .forGetter(VersusData::plantInitialSun),
            Codec.INT.optionalFieldOf("zombie_initial_sun", DEFAULT_ZOMBIE_INITIAL_SUN)
                    .forGetter(VersusData::zombieInitialSun),
            Codec.INT.optionalFieldOf("zombie_income_sun", DEFAULT_INCOME_SUN)
                    .forGetter(VersusData::zombieIncomeSun),
            Codec.INT.optionalFieldOf("zombie_income_ticks", DEFAULT_INCOME_TICKS)
                    .forGetter(VersusData::zombieIncomeTicks),
            Codec.INT.optionalFieldOf("eat_refund_percent", DEFAULT_EAT_REFUND_PERCENT)
                    .forGetter(VersusData::eatRefundPercent),
            Codec.INT.optionalFieldOf("decision_ticks", DEFAULT_DECISION_TICKS)
                    .forGetter(VersusData::decisionTicks),
            Codec.INT.optionalFieldOf("zombie_start_ticks", DEFAULT_ZOMBIE_START_TICKS)
                    .forGetter(VersusData::zombieStartTicks),
            Identifier.CODEC.listOf().optionalFieldOf("plant_cards", List.of())
                    .forGetter(VersusData::plantCards),
            Identifier.CODEC.listOf().optionalFieldOf("zombie_cards", List.of())
                    .forGetter(VersusData::zombieCards)
    ).apply(i, VersusData::new));

    public static final Codec<VersusData> CODEC = MAP_CODEC.codec();

    public VersusData {
        plantCards = List.copyOf(plantCards);
        zombieCards = List.copyOf(zombieCards);
    }

    /** The deck one side plays, by the side's team id. */
    public List<Identifier> cardsFor(Identifier team) {
        return com.pvzce.common.PvzceIds.ZOMBIE_TEAM.equals(team) ? zombieCards : plantCards;
    }

    /** True when the plant side is racing a sun total rather than only holding the lawn. */
    public boolean races() {
        return sunGoal > 0;
    }

    /**
     * The fields an editor may change.
     *
     * <p>The card lists are not here: they are lists of ids rather than numbers, and
     * {@code FieldSpec} has no list-of-content field yet - the level file is the place for them,
     * which is also where a level author would go looking for "what does the opponent get".
     */
    public static List<FieldSpec> editorFields() {
        return List.of(
                FieldSpec.integer("sun_goal", "pvzce.mechanic.versus.field.sun_goal", 0, 100_000),
                FieldSpec.integer("plant_initial_sun", "pvzce.mechanic.versus.field.plant_initial_sun",
                        0, 10_000),
                FieldSpec.integer("zombie_initial_sun", "pvzce.mechanic.versus.field.zombie_initial_sun",
                        0, 10_000),
                FieldSpec.integer("zombie_income_sun", "pvzce.mechanic.versus.field.zombie_income_sun",
                        0, 10_000),
                FieldSpec.integer("zombie_income_ticks", "pvzce.mechanic.versus.field.zombie_income_ticks",
                        0, 36_000),
                FieldSpec.integer("eat_refund_percent", "pvzce.mechanic.versus.field.eat_refund_percent",
                        0, 100),
                FieldSpec.integer("decision_ticks", "pvzce.mechanic.versus.field.decision_ticks",
                        20, 36_000),
                FieldSpec.integer("zombie_start_ticks", "pvzce.mechanic.versus.field.zombie_start_ticks",
                        0, 36_000));
    }

    /**
     * One message per problem, for {@code LevelValidator}.
     *
     * <p>The sun card check is the one that is not about a wrong number: a plant deck without
     * {@code pvzce:sun} is a deck whose side cannot collect the resource the level's own win
     * condition is counted in, and nothing else in the game reports it.
     */
    public List<String> validate() {
        List<String> errors = new java.util.ArrayList<>();
        if (sunGoal < 0) {
            errors.add("versus has a negative sun_goal (" + sunGoal + ")");
        }
        if (plantInitialSun < 0 || zombieInitialSun < 0) {
            errors.add("versus has a negative opening sun (plant=" + plantInitialSun
                    + ", zombie=" + zombieInitialSun + ")");
        }
        if (zombieIncomeSun < 0) {
            errors.add("versus has a negative zombie_income_sun (" + zombieIncomeSun + ")");
        }
        if (zombieIncomeSun > 0 && zombieIncomeTicks <= 0) {
            errors.add("versus pays " + zombieIncomeSun
                    + " sun every " + zombieIncomeTicks + " ticks, which is never");
        }
        if (eatRefundPercent < 0 || eatRefundPercent > 100) {
            errors.add("versus has an eat_refund_percent outside 0..100 (" + eatRefundPercent + ")");
        }
        if (zombieStartTicks < 0) {
            errors.add("versus has a negative zombie_start_ticks (" + zombieStartTicks + ")");
        }
        if (decisionTicks < 20) {
            errors.add("versus asks for a decision every " + decisionTicks
                    + " ticks; under a third of a second is not a decision interval");
        }
        if (plantCards.isEmpty() || zombieCards.isEmpty()) {
            errors.add("versus needs both decks: plant_cards=" + plantCards.size()
                    + ", zombie_cards=" + zombieCards.size());
        }
        for (Identifier card : plantCards) {
            if (!isPlantSideCard(card)) {
                errors.add("versus lists '" + card + "' as a plant card, but it is not the plant side's");
            }
        }
        for (Identifier card : zombieCards) {
            if (!isZombieSideCard(card)) {
                errors.add("versus lists '" + card + "' as a zombie card, but it is not the zombie side's");
            }
        }
        if (!plantCards.isEmpty() && !hasSunCard(plantCards)) {
            errors.add("versus gives the plant side no pvzce:sun card, so it can never collect the "
                    + "sun its own win condition counts");
        }
        return errors;
    }

    /** True when this id resolves to a card the plant side may hold. */
    public static boolean isPlantSideCard(Identifier card) {
        com.pvzce.common.core.SlotResolver.ResolvedCard resolved =
                com.pvzce.common.core.SlotResolver.resolve(card).orElse(null);
        return resolved == null || resolved.kind() != com.pvzce.common.core.Slot.Kind.ZOMBIE;
    }

    /** True when this id resolves to a zombie card. */
    public static boolean isZombieSideCard(Identifier card) {
        com.pvzce.common.core.SlotResolver.ResolvedCard resolved =
                com.pvzce.common.core.SlotResolver.resolve(card).orElse(null);
        return resolved != null && resolved.kind() == com.pvzce.common.core.Slot.Kind.ZOMBIE;
    }

    private static boolean hasSunCard(List<Identifier> cards) {
        for (Identifier card : cards) {
            com.pvzce.common.core.SlotResolver.ResolvedCard resolved =
                    com.pvzce.common.core.SlotResolver.resolve(card).orElse(null);
            if (resolved != null && com.pvzce.common.PvzceIds.SUN.equals(resolved.content())) {
                return true;
            }
        }
        return false;
    }
}
