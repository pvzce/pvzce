package com.pvzce.common.level.mutation;

import com.pvzce.api.util.Identifier;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.server.level.LevelServer;

import java.util.Optional;

/**
 * One thing a mutation level may do to itself.
 *
 * <p>A mutation is behaviour with no data file: it reaches into the running level (writing game
 * rules, moving the card bar, raising graves, spawning zombies) and it can be taken back again,
 * because a level holds a <em>list</em> of them and the oldest one is evicted once the list is
 * full. That reversibility is the whole shape of the interface - every hook that changes
 * something comes in a pair, and the state that pairs them lives in the object {@link #apply}
 * returns.
 *
 * <p>Layering: this lives in {@code common} rather than in {@code api} for the same reason
 * {@code LevelMechanic} does - its hooks speak of {@link LevelServer}. Unlike a mechanic it is
 * not declared by a level and has no JSON block: the catalogue belongs to the engine, and a
 * level opts in by naming {@code pvzce:mutation} among its mechanics.
 *
 * <p>An instance is a shared registry entry: <b>a mutation must not keep per-level state in a
 * field</b>. Everything a run needs - a countdown, the cells it changed, the number it rolled -
 * travels in the object {@link #apply} returns, or is asked of the level.
 */
public interface Mutation {
    /** The id this mutation is registered under; also its language key's last segment. */
    Identifier id();

    /**
     * How likely this mutation is to be the one that appears next.
     *
     * <p>A weight rather than a chance, so the catalogue can grow without every entry having to
     * be re-tuned to keep a sum at one. Zero is "not in the roll", which is how a mutation that
     * is only reachable another way says so.
     */
    default int weight() {
        return 10;
    }

    /**
     * What the client needs to know about this mutation beyond its id.
     *
     * <p>Presentation only, and read from the whole running list at once: the banner, the panel
     * and the overlays ask "does any of them need this" rather than each mutation inventing a
     * packet.
     */
    default MutationEffects clientEffects() {
        return MutationEffects.NONE;
    }

    /**
     * Whether this mutation may act at all right now.
     *
     * <p>Asked before it is added and again whenever the running list changes, because "may this
     * run" can stop being true - a mutation that needs a gravestone on the lawn has nothing to
     * do on a lawn without one. A mutation that answers false is still recorded in the list (the
     * panel shows it as waiting) and simply changes nothing; it starts acting the moment this
     * answers true.
     */
    default boolean canRun(LevelServer level) {
        return true;
    }

    /**
     * Whether a mutation that <em>arrived later</em> holds this one back.
     *
     * <p>Overriding this must keep it antisymmetric: if A suppresses B, B must not suppress A,
     * or neither ever runs.
     *
     * <p>The card bar is the only resource two mutations fight over, and the rule for it is
     * <b>"the one that arrived last wins"</b> - the same rule the panel reads by. So the default
     * is "a bar mutation is held back by a later bar mutation" and nothing else: a rate, weather
     * or board mutation is never suppressed by a dealer, which is what keeps a belt from becoming
     * a mute button for the whole field.
     *
     * <p>It used to be a bare precedence comparison ({@code other.cardSourcePrecedence() >
     * cardSourcePrecedence()}) applied without regard to arrival order, which had two
     * consequences the player could see: the belt's fixed 10 beat every other mutation's 0
     * wherever it sat in the order, and - the bug this replaces - a belt that arrived <em>first</em>
     * permanently suppressed a random-card-slot mutation that arrived <em>later</em>, because the
     * eviction pass refuses to drop the entry that owns the bar. Order, not magnitude, is the
     * thing an author can actually reason about.
     *
     * <p>The caller passes only the entries that arrived after this one, so an implementation
     * never has to ask about order itself.
     */
    default boolean suppressedBy(Mutation other) {
        return isBarMutation(this) && isBarMutation(other);
    }

    /** True for a mutation that owns the card bar, in either of the two ways there are. */
    static boolean isBarMutation(Mutation mutation) {
        return mutation instanceof CardDealingMutation || mutation instanceof RewriteBarMutation;
    }

    /**
     * What to tell the player instead of the generic banner, or empty to use the generic one.
     *
     * <p>The banner is built from the mutation's name and its rolled multiplier, which describes
     * every mutation whose effect is a number. It cannot describe one whose effect is a
     * <em>change to a list</em>: "增益变动 ×1.40" says a buff moved without saying which way or
     * which buff, and the player's only way to find out was to watch the icons in the corner -
     * which they were not told to look at. A mutation that knows what it did says so here.
     *
     * <p>Called after {@link #apply}, so a mutation can answer from the state it just returned.
     */
    default java.util.Optional<String> announcement(LevelServer level, Roll roll, Object state) {
        return java.util.Optional.empty();
    }

    /**
     * Rolls this mutation's one random number.
     *
     * <p>Called when it is added and never again: "the mutation that says 1.4x" has to keep
     * saying 1.4x for as long as it is on the field, or a level whose mutations stack would
     * quietly re-roll itself. The level's own random source is the dice, so a run watched twice
     * from the same seed shows the same mutations.
     */
    default Roll roll(LevelServer level) {
        return Roll.NONE;
    }

    /**
     * Applies the mutation and returns the run state that can undo it, or {@code null} when it
     * keeps none.
     *
     * <p>Called once per activation - not once per tick - and only while {@link #canRun} says
     * yes. Whatever it returns is handed back to {@link #revert} when the mutation leaves the
     * field or is suppressed by a later one.
     */
    default Object apply(LevelServer level, Roll roll) {
        return null;
    }

    /**
     * Applies the mutation to a level that has just been read back from a save.
     *
     * <p>Not the same job as {@link #apply}: everything the mutation did to the <em>world</em> is
     * already in the save - the cards it handed over, the graves it raised, the craters it left,
     * the kelp it spread - so repeating those would double them. What a restore has to put back is
     * the part the save does not carry on its own: the mutation's own clock, and the game rules it
     * owns a share of.
     *
     * <p>So a stateless mutation that only reacts to events (a blast on death, a bullet
     * substitution) does nothing here, and one whose effect is a rule multiplies it again, exactly
     * as it did when the mutation first arrived - the rules in the save are the level's own, which
     * is what the mutation scaled the first time round.
     *
     * <p>The default is {@link #apply}, because a mutation that changes nothing but a rule is the
     * common case and the two calls are then the same call.
     */
    default Object applyFromSave(LevelServer level, Roll roll) {
        return apply(level, roll);
    }

    /**
     * This mutation's run state, as NBT, for the level save.
     *
     * <p>The other half of {@link #saveState}: whatever a mutation keeps between ticks - a
     * countdown, a target, the number of things it has already done - has to survive a save or a
     * resumed run would restart that clock, which for a mutation that spawns something means it
     * would arrive late, and for one that has already fired means it would fire again.
     *
     * <p>Empty for a mutation whose state is entirely derived (a rate mutation's factor is in its
     * {@code Roll}, and the rule itself is in the save's rules).
     *
     * <p>Called on the <em>shared registry instance</em>, so a mutation that has to read the live
     * board to describe its state cannot do it from here alone: the manager sets whatever handle
     * the mutation asked for before the call and clears it after (see
     * {@code SlotReplaceMutation.savingPlayer}). The alternative - passing the level in - would
     * put a server type on a method every mutation implements whether or not it needs one.
     */
    default CompoundTag saveState() {
        return new CompoundTag();
    }

    /**
     * Reads back what {@link #saveState} wrote, into the object {@link #applyFromSave} is about to
     * return.
     *
     * <p>Called <em>before</em> {@code applyFromSave}, so a mutation can rebuild its state from
     * the save and then have the effect applied to it. Must tolerate an absent or partial block -
     * a save written by an older build has none of these fields.
     */
    default void loadState(CompoundTag tag) {
    }

    /**
     * Undoes one {@link #apply}.
     *
     * <p>Must tolerate a {@code null} state and a level that has lost the things the mutation
     * touched: a card can have been spent, a grave smashed, a plant eaten between the two calls,
     * and none of that is an error.
     */
    default void revert(LevelServer level, Roll roll, Object state) {
    }

    /**
     * One tick of this mutation's own clock, while it is running.
     *
     * <p>What a mutation that keeps something happening (a grave every twenty seconds, a zombie
     * walking in from the right) does. Its countdown lives in {@code state}.
     */
    default void tick(LevelServer level, Roll roll, Object state) {
    }

    /**
     * A veto on planting, layered on terrain, stacking and the level's own mechanics.
     *
     * <p>Same contract as {@code LevelMechanic.canPlacePlant}: false means "this mutation does
     * not allow it here". Unused by the catalogue today - it exists so a lawn-rewriting mutation
     * would not have to become a level mechanic to be possible.
     */
    default boolean canPlacePlant(LevelServer level, Roll roll, Object state,
                                  com.pvzce.api.content.PlantDef def, int x, int y) {
        return true;
    }

    /**
     * What one roll produced: the strength multiplier and, for a mutation that had to pick a
     * subject, which one it picked.
     *
     * @param multiplier scales this mutation's own numbers; 1 is "as written"
     * @param subject    what the roll chose among (the zombie of a "crisis", the bullet a
     *                   substitution landed on), empty when there was nothing to pick
     */
    record Roll(float multiplier, Optional<Identifier> subject) {
        /** A roll with no strength and no subject: the shape of a mutation with no numbers. */
        public static final Roll NONE = new Roll(1F, Optional.empty());

        public Roll {
            multiplier = multiplier <= 0F ? 1F : multiplier;
            subject = subject == null ? Optional.empty() : subject;
        }

        public static Roll of(float multiplier) {
            return new Roll(multiplier, Optional.empty());
        }

        public static Roll of(Identifier subject) {
            return new Roll(1F, Optional.of(subject));
        }
    }
}
