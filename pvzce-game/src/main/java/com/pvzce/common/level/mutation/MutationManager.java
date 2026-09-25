package com.pvzce.common.level.mutation;

import com.pvzce.api.content.PlantDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.network.packet.MutationStateS2C;
import com.pvzce.common.network.packet.ServerMessageS2C;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.server.level.LevelServer;
import com.pvzce.server.level.cardsource.CardSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The mutations a level currently runs with, in arrival order, and the clock that adds more.
 *
 * <p>Owned by the level rather than by the mechanic, because the state is per-run: the mechanic
 * is a shared registry entry that only says "this level mutates", and everything that changes -
 * which mutations are on the field, what each one rolled, how long until the next one - lives
 * here.
 *
 * <p>Three ideas hold the whole thing together:
 *
 * <ul>
 *   <li><b>Arrival order is the list.</b> "The oldest one is evicted" is the first element, and
 *       the panel lists them in the same order, so what the player reads and what the engine
 *       drops are the same fact.</li>
 *   <li><b>Acting is derived, never toggled in place.</b> Every change re-derives which entries
 *       are acting ({@link #recompute}) and applies or undoes the difference. A mutation held
 *       back by a later one therefore comes back by itself when that one leaves, and nothing has
 *       to remember that it was held back.</li>
 *   <li><b>The client is told the whole set.</b> A mutation that only changes its own number
 *       still moves the panel's countdown, so there is one packet for the feature rather than one
 *       per mutation.</li>
 * </ul>
 *
 * <p>What is deliberately <em>not</em> saved: the active list. A resumed run rolls a fresh
 * schedule, because half the catalogue changes things a save cannot describe (a bar handed to the
 * player, cells rewritten on the lawn, a card source swapped) and a restore that brought back
 * some of them would bring back the wrong half.
 */
public final class MutationManager {
    /** How often the client hears the countdown even when nothing changed, in ticks. */
    private static final int RESYNC_INTERVAL_TICKS = 40;

    private final LevelServer level;
    private final List<MutationEntry> entries = new ArrayList<>();
    /**
     * The entries that are currently acting, by <em>entry</em> rather than by id.
     *
     * <p>Per entry because a level may roll the same mutation twice - the weights allow it, and two
     * "僵尸危机" with different subjects are two entries a player can see on the panel. A set of ids
     * cannot tell those apart, and the earlier copy of a pair would be left marked as acting after
     * the later one arrived to hold it back: the undo pass asked "is this id in the new acting set"
     * and the second copy's id answered for the first, so the suppressed copy went on ticking.
     */
    private final Set<MutationEntry> applied = new LinkedHashSet<>();

    private int ticksUntilNext;
    /** False until the first mutation of this run has arrived; see {@link #tick}. */
    private boolean arrivedYet;
    /** The mutation whose card bar is in force, or {@code null} when the level's own bar stands. */
    private MutationEntry cardSourceOwner;
    /**
     * The mutation that owns what is <em>on</em> the bar rather than which bar it is.
     *
     * <p>Separate from {@link #cardSourceOwner} because the two answer different questions and
     * only one of them may be in force: a rewriter needs the level's own bar to stand so its work
     * is visible. {@link #reapplyBarRewrite} is how the level asks it to lay that work down again
     * after a rebuild it could not see.
     */
    private MutationEntry barRewriteOwner;
    /** What the level was last told to deal the cards with - the change detector for the handoff. */
    private MutationCardSource cardSourceFactory;
    private int lastEffects = MutationEffects.NONE.mask();
    private int lastSentTick = Integer.MIN_VALUE;
    private int lastSentEntryCount = -1;
    /**
     * True until the first tick, which is when the opening state can finally be sent.
     *
     * <p>{@link #start} runs inside the level's constructor, where there is no bridge yet: a
     * packet sent then goes nowhere. The client is owed the panel before the first mutation
     * arrives - it carries the tier, the interval and the countdown - so the send waits for the
     * first tick instead of being lost at construction.
     */
    private boolean firstSendPending = true;

    public MutationManager(LevelServer level) {
        this.level = level;
        this.ticksUntilNext = Math.max(1, level.rules().getInt(PvzceIds.RULE_MUTATION_INITIAL_TICKS));
    }

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    /** How hard this level mutates, read from its rules. */
    public MutationDifficulty difficulty() {
        return level.rules().getEnum(PvzceIds.RULE_MUTATION_DIFFICULTY, MutationDifficulty::parse);
    }

    /** How many mutations may stand at once. */
    public int limit() {
        return difficulty().maxConcurrent();
    }

    /** How long this level waits between two mutations, its own multiplier included. */
    public int intervalTicks() {
        float multiplier = Math.max(0.05F,
                level.rules().getFloat(PvzceIds.RULE_MUTATION_INTERVAL_MULTIPLIER));
        return Math.max(1, Math.round(difficulty().intervalTicks() / multiplier));
    }

    /** Ticks until the next mutation arrives. */
    public int ticksUntilNext() {
        return ticksUntilNext;
    }

    /** The mutations on the field, oldest first. */
    public List<Identifier> activeIds() {
        List<Identifier> ids = new ArrayList<>(entries.size());
        for (MutationEntry entry : entries) {
            ids.add(entry.id());
        }
        return List.copyOf(ids);
    }

    /** True while that mutation is on the field and acting. */
    public boolean isApplied(Identifier mutationId) {
        for (MutationEntry entry : applied) {
            if (entry.id().equals(mutationId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True while that mutation is on the field at all, acting or not.
     *
     * <p>What one mutation asks about another: "is the thing I depend on present" is a question
     * about the list, not about whether that other mutation happens to be held back right now.
     */
    public boolean isPresent(Identifier mutationId) {
        for (MutationEntry entry : entries) {
            if (entry.id().equals(mutationId)) {
                return true;
            }
        }
        return false;
    }

    /** What that mutation rolled, or {@link Mutation.Roll#NONE} when it is not on the field. */
    public Mutation.Roll rollOf(Identifier mutationId) {
        for (MutationEntry entry : entries) {
            if (entry.id().equals(mutationId)) {
                return entry.roll();
            }
        }
        return Mutation.Roll.NONE;
    }

    /** The union of every acting mutation's client effects. */
    public int effects() {
        return lastEffects;
    }

    /**
     * The card bar every acting mutation agreed on, or {@code null} when none deals cards.
     *
     * <p>Read by {@code LevelServer} at the handoff: it builds the source from this factory and
     * hands it the level's own context, so the player, the level definition and the cards the
     * player chose all reach a mutation's bar the same way they reach a mechanic's.
     */
    public MutationCardSource cardSourceFactory() {
        return cardSourceFactory;
    }

    // ------------------------------------------------------------------
    // The level's hooks
    // ------------------------------------------------------------------

    /** Runs on level construction; the first mutation is due after the level's grace period. */
    public void start() {
        recompute();
    }

    /** One tick: the arrival clock, and every acting mutation's own clock. */
    public void tick() {
        if (firstSendPending) {
            firstSendPending = false;
            sendState(true);
        }
        if (--ticksUntilNext <= 0) {
            arrive();
        } else if (!arrivedYet) {
            // Nothing has arrived yet, so this countdown is the level's grace period and it reads
            // its rule live - the same way a card's cooldown reads its multiplier and the sky its
            // interval. `/gamerule mutation_initial_ticks` therefore bites on the spot instead of
            // waiting for the next run, which is what makes the opening delay tunable in play and
            // what a test can use to see a mutation without ticking thirty seconds of simulation.
            ticksUntilNext = Math.min(ticksUntilNext, Math.max(1,
                    level.rules().getInt(PvzceIds.RULE_MUTATION_INITIAL_TICKS)));
        }
        for (MutationEntry entry : entries) {
            if (entry.isApplied()) {
                entry.mutation().tick(level, entry.roll(), entry.state());
            }
        }
        sendIfStale();
    }

    /**
     * The bar slots some acting mutation has locked, ascending.
     *
     * <p>Folded from the whole list here rather than asked per card by the client: the lock is part
     * of the state packet, so the bar draws it from the same answer the server refuses a click
     * with. Only acting mutations count - a lock held by a mutation that is being suppressed (or
     * that cannot run) is not a lock the player can see any reason for.
     */
    public List<Integer> lockedSlots() {
        List<Integer> locked = new ArrayList<>();
        for (MutationEntry entry : entries) {
            if (!entry.isApplied()) {
                continue;
            }
            // The bar's size, not the tier's: a mutation that named slot 7 on a six-card bar has
            // nothing to lock, and asking each index rather than each mutation's own list keeps
            // this the same question the placement path asks.
            int size = level.plantPlayer() == null ? 0 : level.plantPlayer().slots().size();
            for (int index = 0; index < size; index++) {
                if (entry.mutation().isSlotLocked(level, entry.roll(), entry.state(), index)
                        && !locked.contains(index)) {
                    locked.add(index);
                }
            }
        }
        java.util.Collections.sort(locked);
        return List.copyOf(locked);
    }

    /** True when that bar slot is locked; what {@code LevelServer.placePlantInternal} asks. */
    public boolean isSlotLocked(int slotIndex) {
        for (MutationEntry entry : entries) {
            if (entry.isApplied() && entry.mutation().isSlotLocked(level, entry.roll(),
                    entry.state(), slotIndex)) {
                return true;
            }
        }
        return false;
    }

    /** A veto on planting, asked of every acting mutation. */
    public boolean canPlacePlant(PlantDef def, int x, int y) {
        for (MutationEntry entry : entries) {
            if (!entry.isApplied()) {
                continue;
            }
            if (!entry.mutation().canPlacePlant(level, entry.roll(), entry.state(), def, x, y)) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Save and restore
    // ------------------------------------------------------------------

    /** The NBT key this manager's whole block lives under, in {@code level.dat}. */
    public static final String KEY_MUTATIONS = "Mutations";

    /**
     * Writes the list, the arrival clock and every mutation's own state.
     *
     * <p>The list has to survive a save because it is not derivable from anything else: which
     * mutations are running, in what order, and what each of them rolled is a sequence of dice
     * rolls, and re-rolling them on resume would hand the player a different game. What the save
     * does <em>not</em> carry is the mutations' effects on the world - those are already in the
     * file's own sections (the scene, the entities, the cards, the buffs, the rules) - so a
     * restore puts back the list and the clocks, not the damage.
     */
    public void save(com.pvzce.common.nbt.CompoundTag root) {
        com.pvzce.common.nbt.CompoundTag block = new com.pvzce.common.nbt.CompoundTag();
        block.putInt("TicksUntilNext", ticksUntilNext);
        block.putString("Difficulty", difficulty().tierName());
        com.pvzce.common.nbt.ListTag list = new com.pvzce.common.nbt.ListTag();
        for (MutationEntry entry : entries) {
            com.pvzce.common.nbt.CompoundTag entryTag = new com.pvzce.common.nbt.CompoundTag();
            entryTag.putString("Id", entry.id().toString());
            entryTag.putFloat("Multiplier", entry.roll().multiplier());
            entry.roll().subject().ifPresent(subject ->
                    entryTag.putString("Subject", subject.toString()));
            // Whatever the mutation keeps between ticks. Empty for most of them; the ones that
            // count something down write it here (see Mutation.saveState).
            handOverForSave(entry);
            entryTag.put("State", entry.mutation().saveState());
            clearSaveHandles();
            list.add(entryTag);
        }
        block.put("List", list);
        // The card bar, if a mutation is dealing it: a belt is a queue, and a queue is state.
        if (cardSourceOwner != null) {
            com.pvzce.common.nbt.CompoundTag sourceTag = new com.pvzce.common.nbt.CompoundTag();
            sourceTag.putString("Owner", cardSourceOwner.id().toString());
            CardSource source = level.cardSource();
            if (source != null) {
                source.collectSave(sourceTag);
            }
            block.put("CardSource", sourceTag);
        }
        root.put(KEY_MUTATIONS, block);
    }

    /**
     * Reads the list back and puts every mutation back on the field.
     *
     * <p>Called from the level's restore <em>before</em> the card bar is restored: a mutation that
     * deals the cards owns the bar, so the bar has to exist before the saved cooldowns are read
     * into it. Runs through the same {@link #recompute} the live path uses, with {@code fromSave}
     * set, so which mutations are acting and which are held back is decided by one implementation.
     */
    public void restore(com.pvzce.common.nbt.CompoundTag root) {
        clear();
        com.pvzce.common.nbt.CompoundTag block = root.getCompound(KEY_MUTATIONS);
        if (block == null) {
            // A save written before mutations were persisted, or a level that has none: the list
            // starts empty and the grace period runs again, which is the old behaviour.
            ticksUntilNext = Math.max(1, level.rules().getInt(PvzceIds.RULE_MUTATION_INITIAL_TICKS));
            firstSendPending = true;
            return;
        }
        ticksUntilNext = Math.max(1, block.getInt("TicksUntilNext"));
        for (com.pvzce.common.nbt.Tag element : block.getList("List").values()) {
            if (!(element instanceof com.pvzce.common.nbt.CompoundTag entryTag)) {
                continue;
            }
            Identifier id = Identifier.tryParse(entryTag.getString("Id"));
            Mutation mutation = MutationRegistry.get(id);
            if (mutation == null) {
                // A mutation this build does not have (a pack was removed): the entry is dropped
                // rather than restored as a blank. Its rule changes are gone with it, since the
                // rules in this save are the level's own scaled by nothing.
                continue;
            }
            Identifier subject = Identifier.tryParse(entryTag.getString("Subject"));
            Mutation.Roll roll = new Mutation.Roll(entryTag.getFloat("Multiplier"),
                    java.util.Optional.ofNullable(subject));
            mutation.loadState(entryTag.getCompound("State"));
            entries.add(new MutationEntry(mutation, roll));
        }
        recompute(true);
        com.pvzce.common.nbt.CompoundTag sourceTag = block.getCompound("CardSource");
        Identifier ownerId = sourceTag == null ? null : Identifier.tryParse(sourceTag.getString("Owner"));
        if (ownerId != null && cardSourceOwner != null && ownerId.equals(cardSourceOwner.id())) {
            CardSource source = level.cardSource();
            if (source != null) {
                source.applySave(level, sourceTag);
            }
        }
        firstSendPending = true;
    }

    /**
     * The rules the level should write to its save: <em>unwound</em> from the mutations that own
     * them.
     *
     * <p>A mutation writes its own factor into a live rule (see {@code RateMutation}), so the
     * level's rule map by the time a save is asked for already carries every factor - and a save
     * that wrote those values would be read back by {@code applyFromSave}, which multiplies once
     * more. The result was a resumed run whose mutated rules were the square of what was saved.
     *
     * <p>So the factors are divided back out for the file and re-applied to memory immediately:
     * the same mutation is written down twice (its id and the number it rolled) and its effect is
     * recomputed on load, which is the shape the rest of the mutation save already had - "what the
     * world looks like" is left to the level's own sections.
     *
     * @param live the rules as they are being played
     * @param forSave the rules to write to the file, with the mutations' factors removed
     * @return the rules this manager owns and has already put back, so a caller with several
     *         writers (a rule mutation and a buff, say) can compose them
     */
    public List<RuleWrite> rulesForSave(Map<Identifier, Float> live, Map<Identifier, Float> forSave) {
        Map<Identifier, Float> unwound = new LinkedHashMap<>();
        // In arrival order, each entry dividing out of what the previous one left: two copies of
        // the same mutation compound when they arrive (their `apply` is a multiply each), so
        // undoing them has to compound in reverse. Dividing both out of the live value instead
        // wrote the intermediate value - a save that resumed one factor short.
        for (MutationEntry entry : entries) {
            if (!entry.isApplied() || !(entry.mutation() instanceof RuleMutation owner)) {
                continue;
            }
            for (Identifier rule : owner.rules()) {
                float factor = owner.appliedFactor(rule, entry.roll(), entry.state());
                if (!(factor > 0F)) {
                    continue;
                }
                // The first entry of a rule starts from the live value; the next one starts from
                // where the previous left off, which is also what gets written for that rule.
                Float working = unwound.containsKey(rule) ? unwound.get(rule) : live.get(rule);
                if (working == null) {
                    continue;
                }
                float without = working / factor;
                unwound.put(rule, without);
                forSave.put(rule, without);
            }
        }
        // The live values are what the caller puts back into memory; `unwound` holds the
        // intermediate values the loop walked through, so the map kept for that is separate.
        Map<Identifier, Float> liveValues = new LinkedHashMap<>();
        for (Identifier rule : unwound.keySet()) {
            liveValues.put(rule, live.get(rule));
        }
        unwound.clear();
        unwound.putAll(liveValues);
        return List.copyOf(unwound.entrySet().stream()
                .map(e -> new RuleWrite(e.getKey(), e.getValue()))
                .toList());
    }

    /**
     * One rule this manager holds a factor for, at its live value.
     *
     * @param rule  the rule the mutation scaled
     * @param value what it says in memory right now
     */
    public record RuleWrite(Identifier rule, float value) {
    }

    /**
     * A mutation that keeps its number in a game rule.
     *
     * <p>The one shape a save has to know about: everything else a mutation does to the world
     * lives in the level's own sections (the scene, the entities, the cards), which the save
     * carries anyway - a rule is the one thing the level writes down itself and would therefore
     * write down twice.
     */
    public interface RuleMutation {
        /** The rule this mutation scales. */
        Identifier rule();

        /**
         * Every rule this mutation scales, when it owns more than one.
         *
         * <p>A mutation whose effect is two rules at once ("the ground is icy: they walk faster
         * <em>and</em> they stay frozen longer") has to have both divided back out of the save, or a
         * resumed run compounds one of them for ever. The default is the single rule above, which is
         * what every numeric mutation needs.
         */
        default java.util.List<Identifier> rules() {
            return java.util.List.of(rule());
        }

        /**
         * The factor this mutation really applied to one of its rules.
         *
         * <p>Almost always the roll - which is why that is the default - but a mutation whose two
         * rules use two different factors has to say so, and it can only say it from the state it
         * applied ({@code apply} returns it). Reading the roll instead left the second factor in the
         * save file and multiplied it in again on every resume.
         */
        default float appliedFactor(Identifier rule, Mutation.Roll roll, Object state) {
            return roll == null ? 1F : roll.multiplier();
        }
    }

    /**
     * A mutation whose {@code saveState} has to read the run state the manager is holding.
     *
     * <p>{@code Mutation.saveState} takes no level and no state - the interface is shared by
     * mutations that need neither, and a server type on that method would be paid for by all of
     * them - so the manager hands the state over around the call instead. This interface is how a
     * mutation says it wants that: it was an {@code instanceof} chain here, and every stateful
     * mutation added had to be remembered in <em>two</em> places of it (set and clear) or its clock
     * silently restarted on every resume.
     */
    interface SaveHandle {
        /** The state to write down, or {@code null} when the handle is being cleared. */
        void savingState(Object state);
    }

    /** Hands a mutation the handle it needs to describe its state; see {@code Mutation.saveState}. */
    private void handOverForSave(MutationEntry entry) {
        if (entry.mutation() instanceof SlotReplaceMutation slotReplace) {
            slotReplace.savingPlayer(level.plantPlayer());
        }
        if (entry.mutation() instanceof SaveHandle handle) {
            handle.savingState(entry.state());
        }
    }

    /** Clears the handles {@link #handOverForSave} set: a shared instance must not keep them. */
    private void clearSaveHandles() {
        for (MutationEntry entry : entries) {
            if (entry.mutation() instanceof SlotReplaceMutation slotReplace) {
                slotReplace.savingPlayer(null);
            }
            if (entry.mutation() instanceof SaveHandle handle) {
                handle.savingState(null);
            }
        }
    }

    // ------------------------------------------------------------------
    // Events
    // ------------------------------------------------------------------

    /**
     * The plant that really appears where one was about to be planted.
     *
     * <p>The first acting mutation that has an opinion wins, in arrival order: two mutations
     * rewriting the same plant into two different plants is a conflict this cannot resolve
     * meaningfully, and "the older one" is at least stable. Returns {@code null} when nothing
     * wants to change it, which is the common case.
     */
    public PlantDef replacePlantedPlant(PlantDef def, int x, int y) {
        for (MutationEntry entry : entries) {
            if (entry.isApplied() && entry.mutation() instanceof MutationHooks hooks) {
                PlantDef replacement = hooks.replacePlantedPlant(def, x, y);
                if (replacement != null && !replacement.equals(def)) {
                    return replacement;
                }
            }
        }
        return null;
    }

    /** Tells every acting mutation that a plant has been placed. */
    public void onPlantPlaced(PlantEntity plant) {
        forEachHook(hooks -> hooks.onPlantPlaced(level, plant));
    }

    /** Tells every acting mutation that a plant has died. */
    public void onPlantDied(PlantEntity plant) {
        forEachHook(hooks -> hooks.onPlantDied(level, plant));
    }

    /** Tells every acting mutation that a zombie has died. */
    public void onZombieDied(ZombieEntity zombie) {
        forEachHook(hooks -> hooks.onZombieDied(level, zombie));
    }

    /** Tells every acting mutation that a zombie has spawned. */
    public void onZombieSpawned(ZombieEntity zombie) {
        forEachHook(hooks -> hooks.onZombieSpawned(level, zombie));
    }

    /** Tells every acting mutation that a projectile has just been born. */
    public void onProjectileFired(Identifier projectileId) {
        forEachHook(hooks -> hooks.onProjectileFired(level, projectileId));
    }

    /**
     * The mutation that rewrites ammunition, or {@code null}.
     *
     * <p>Asked per shot rather than cached, because the answer changes when the mutation that
     * owns it is evicted - and a cached "somebody substitutes peas" would keep substituting them
     * after the field forgot why.
     */
    public ProjectileSubstitution projectileSubstitution() {
        for (MutationEntry entry : entries) {
            if (!entry.isApplied()) {
                continue;
            }
            if (entry.mutation() instanceof ProjectileSubstitution substitution) {
                return substitution;
            }
        }
        return null;
    }

    /** Runs one event against every acting mutation that implements it. */
    private void forEachHook(java.util.function.Consumer<MutationHooks> event) {
        // Over a copy: a hook may add a mutation (a mutation that spawns is one), and mutating
        // the list under a for-each is a ConcurrentModificationException in the middle of a tick.
        for (MutationEntry entry : List.copyOf(entries)) {
            if (entry.isApplied() && entry.mutation() instanceof MutationHooks hooks) {
                event.accept(hooks);
            }
        }
    }

    /**
     * Puts one named mutation on the field, as if it had just been rolled.
     *
     * <p>Public because two callers need it and neither is the dice: a test that would otherwise
     * have to roll until the catalogue happens to produce the mutation it is about (which makes it
     * depend on eighteen weights and on which of them can act on the board it built), and - the
     * day one exists - a command or a level option that forces one. Which mutation arrives is a
     * separate concern from what a mutation does, and this is the seam between them.
     *
     * @param roll the number it "rolled"; {@link Mutation.Roll#NONE} for a stateless one
     * @return the entry that was added, or {@code null} when that mutation is not registered
     */
    public MutationEntry add(Mutation mutation, Mutation.Roll roll) {
        if (mutation == null) {
            return null;
        }
        MutationEntry entry = new MutationEntry(mutation, roll);
        // Runs the same reconciliation an arrival does, so a test-installed mutation is in the
        // same shape the engine would have left it in (including which other entries it holds
        // back, and the card-bar handoff).
        entries.add(entry);
        recompute();
        announce(mutation, roll);
        // Last, and on purpose: `announce` reads the state `recompute` just installed, and the
        // state packet is the one the client redraws its panel from. Sending it first would put
        // the two out of order on the wire - the panel would learn about the mutation before the
        // banner that explains it, and a caller collecting the packets would see them reversed.
        sendState(true);
        return entry;
    }

    /** Undoes everything, for a level that is being torn down. */
    public void clear() {
        for (MutationEntry entry : entries) {
            if (entry.isApplied()) {
                entry.mutation().revert(level, entry.roll(), entry.state());
                entry.setApplied(false);
            }
        }
        entries.clear();
        applied.clear();
        cardSourceOwner = null;
        barRewriteOwner = null;
        cardSourceFactory = null;
    }

    // ------------------------------------------------------------------
    // Arrivals and evictions
    // ------------------------------------------------------------------

    /** Rolls one mutation, makes room for it, and takes effect. */
    private void arrive() {
        arrivedYet = true;
        ticksUntilNext = intervalTicks();
        Mutation mutation = MutationRegistry.roll(level.random());
        if (mutation == null) {
            return;
        }
        int limit = limit();
        while (entries.size() >= limit) {
            if (!evictOldest()) {
                // Nothing was evictable, which can only happen when the list is one card dealer
                // over its own limit. Dropping the list is better than spinning on it.
                clear();
                break;
            }
        }
        Mutation.Roll roll = mutation.roll(level);
        entries.add(new MutationEntry(mutation, roll));
        recompute();
        announce(mutation, roll);
        sendState(true);
    }

    /**
     * Evicts the oldest mutation that is not currently dealing the cards.
     *
     * <p>The exception is the card-bar handoff. Dropping the mutation that owns the bar would
     * take the bar with it, and the player would be left with a card source nothing accounts for;
     * skipping it instead means a field full of card dealers still evicts one of them, so the
     * level cannot deadlock on a full list.
     *
     * <p>No {@code revert} here on purpose: {@link #recompute} sees that the entry is gone and
     * reconciles the rest, including the bar handoff. Two places undoing the same mutation is how
     * a plant ends up un-bowled twice.
     */
    private boolean evictOldest() {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i) == cardSourceOwner || entries.get(i) == barRewriteOwner) {
                continue;
            }
            entries.remove(i);
            return true;
        }
        return false;
    }

    /**
     * Re-derives which mutations are acting, and applies or undoes the difference.
     *
     * <p>The rules, in one pass over the list:
     *
     * <ol>
     *   <li>a mutation whose {@code canRun} says no is <b>waiting</b> - on the field, changing
     *       nothing;</li>
     *   <li>a mutation that a <em>later</em> one holds back is <b>suppressed</b> - on the field,
     *       changing nothing, and back by itself once that other one is gone;</li>
     *   <li>among the mutations that touch the card bar, <b>the last one to arrive wins</b> and the
     *       rest are suppressed, so "one bar" stays true. "Touch" covers both a dealer, which
     *       replaces the bar, and a rewriter, which changes the cards on it.</li>
     * </ol>
     *
     * <p>Order, not a precedence number: the player's only model of this is "the new one took
     * over", and a rule they can see is worth more than one an author can tune. The handoff runs
     * <em>before</em> the newly acting mutations are applied, so a rewrite that lands in the same
     * pass as a belt leaving writes onto the rebuilt deck rather than onto a bar that is about to
     * be replaced under it.
     */
    private void recompute() {
        recompute(false);
    }

    /**
     * @param fromSave true while restoring: mutations are put back rather than applied, so the
     *                 ones that change the world once ({@code Mutation.applyFromSave}) skip the
     *                 part of their effect the save already carries
     */
    private void recompute(boolean fromSave) {
        MutationEntry newDealer = null;
        MutationEntry newRewriter = null;
        MutationEntry lastBarEntry = null;
        List<MutationEntry> toApply = new ArrayList<>();
        Set<MutationEntry> acting = new LinkedHashSet<>();
        for (int i = 0; i < entries.size(); i++) {
            MutationEntry entry = entries.get(i);
            if (!entry.mutation().canRun(level) || isHeldBack(entry, i)) {
                continue;
            }
            if (entry.mutation() instanceof CardDealingMutation) {
                newDealer = entry;
                lastBarEntry = entry;
            } else if (entry.mutation() instanceof RewriteBarMutation) {
                newRewriter = entry;
                lastBarEntry = entry;
            }
            acting.add(entry);
            if (!entry.isApplied()) {
                toApply.add(entry);
            }
        }
        // A dealer only owns the bar while it is the last bar mutation standing; a rewriter that
        // arrived after it takes the bar back to the level's own source and rewrites that.
        if (lastBarEntry != newDealer) {
            newDealer = null;
        }
        if (lastBarEntry != newRewriter) {
            newRewriter = null;
        }
        // Anything that was acting and is not in the new set is undone, in arrival order.
        for (MutationEntry entry : entries) {
            if (entry.isApplied() && !acting.contains(entry)) {
                entry.mutation().revert(level, entry.roll(), entry.state());
                entry.setApplied(false);
            }
        }

        MutationCardSource previous = cardSourceFactory;
        cardSourceOwner = newDealer;
        barRewriteOwner = newRewriter;
        cardSourceFactory = newDealer != null
                && newDealer.mutation() instanceof CardDealingMutation dealing
                ? dealing.cardSource()
                : null;
        // The handoff first: it rebuilds the bar from the level's own cards, so a rewrite applied
        // before it would be written over. See the class doc of RewriteBarMutation.
        if (previous != cardSourceFactory) {
            level.onMutationCardSourceChanged();
        }
        for (MutationEntry entry : toApply) {
            entry.setState(fromSave
                    ? entry.mutation().applyFromSave(level, entry.roll())
                    : entry.mutation().apply(level, entry.roll()));
            entry.setApplied(true);
        }
        applied.clear();
        applied.addAll(acting);
        refreshEffects();
    }

    /**
     * True when a mutation that arrived later holds this one back.
     *
     * <p>Only the entries after {@code index} are asked, because "arrived later" is the whole
     * rule: {@link Mutation#suppressedBy} is written as "the other one is a bar mutation too",
     * and the order lives here rather than in twenty implementations.
     */
    private boolean isHeldBack(MutationEntry entry, int index) {
        for (int i = index + 1; i < entries.size(); i++) {
            if (entry.mutation().suppressedBy(entries.get(i).mutation())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Re-lays the current bar rewriter's work onto the bar, if one is acting.
     *
     * <p>Called by {@code LevelServer} after it rebuilds its own bar (a round re-pick, a restore),
     * which is a thing the mutation cannot see and would otherwise be silently undone by.
     */
    public void reapplyBarRewrite() {
        if (barRewriteOwner == null || !barRewriteOwner.isApplied()) {
            return;
        }
        if (barRewriteOwner.mutation() instanceof RewriteBarMutation rewriter) {
            rewriter.rewriteBar(level, barRewriteOwner.state());
        }
    }

    /**
     * How wide the tray is when a mutation is dealing the cards.
     *
     * <p>Read from the belt itself rather than remembered from the mutation: the capacity is the
     * mutation's decision (one card per plant card it replaced) and asking the live source is what
     * keeps this number and the bar the player is looking at the same answer.
     */
    private int beltCapacity() {
        if (cardSourceOwner == null) {
            return 0;
        }
        CardSource source = level.cardSource();
        if (source instanceof com.pvzce.server.level.cardsource.BeltCardSource belt) {
            return belt.belt().def().capacity();
        }
        return 0;
    }

    /** Folds every acting mutation's client effects into one bit set. */
    private void refreshEffects() {
        int effects = MutationEffects.NONE.mask();
        for (MutationEntry entry : entries) {
            if (entry.isApplied()) {
                effects = MutationEffects.union(effects, entry.mutation().clientEffects().mask());
            }
        }
        lastEffects = effects;
    }

    /** Tells the player what just appeared. */
    private void announce(Mutation mutation, Mutation.Roll roll) {
        // A mutation that can say what it actually did says it; the generic name-and-multiplier
        // line is the fallback. See Mutation.announcement.
        MutationEntry entry = null;
        for (MutationEntry candidate : entries) {
            if (candidate.mutation() == mutation && candidate.roll() == roll) {
                entry = candidate;
                break;
            }
        }
        String text = (entry == null ? java.util.Optional.<String>empty()
                : mutation.announcement(level, roll, entry.state()))
                .filter(line -> !line.isBlank())
                .orElseGet(() -> MutationText.banner(mutation, roll, level));
        if (!text.isEmpty()) {
            level.send(new ServerMessageS2C(text));
        }
    }

    // ------------------------------------------------------------------
    // Streaming
    // ------------------------------------------------------------------

    /**
     * Sends the whole picture when it changed, and a heartbeat every forty ticks otherwise.
     *
     * <p>The heartbeat is what makes the panel's countdown move without a packet per tick; the
     * change detection is what keeps a fifty-mutation run from streaming the same list thirty
     * times a second.
     */
    private void sendIfStale() {
        if (lastSentEntryCount == entries.size()
                && level.tickCount() - lastSentTick < RESYNC_INTERVAL_TICKS) {
            return;
        }
        sendState(false);
    }

    /**
     * Tells the client its buffs changed, now.
     *
     * <p>Called by the buff-shift mutation through {@code LevelServer.setActiveBuffs}. Forced,
     * because the heartbeat's own condition is "the mutation list changed or the resync is due",
     * and a buff shift is neither: without this the icon row would keep showing the buffs the run
     * started with until the next mutation arrived - which, at the higher tiers' intervals, could
     * be most of a round.
     *
     * <p>Nothing is invented here: the packet has carried the list since it gained the field, so
     * this is only "send it a tick early".
     */
    public void buffsChanged() {
        sendState(true);
    }

    /** Sends the mutation list now. */
    public void sendState(boolean force) {
        if (!force && level.tickCount() - lastSentTick < RESYNC_INTERVAL_TICKS) {
            return;
        }
        List<MutationStateS2C.Entry> wire = new ArrayList<>(entries.size());
        for (MutationEntry entry : entries) {
            wire.add(new MutationStateS2C.Entry(
                    entry.id().toString(),
                    statusOf(entry).name().toLowerCase(Locale.ROOT),
                    entry.roll().multiplier(),
                    entry.roll().subject().map(Identifier::toString).orElse("")));
        }
        MutationDifficulty tier = difficulty();
        List<MutationStateS2C.ToolGrant> tools = new ArrayList<>();
        for (com.pvzce.api.content.ToolData tool : level.grantedTools()) {
            if (tool.tool() != null) {
                // "Free" is read as "the block priced it at nothing", which is the one thing the
                // client cannot work out for itself: the tool's own definition may well cost sun.
                boolean free = tool.cost()
                        .map(cost -> cost.resources().isEmpty())
                        .orElse(false);
                tools.add(new MutationStateS2C.ToolGrant(tool.tool().toString(), tool.isDefault(),
                        free, com.pvzce.common.level.mechanic.ToolMechanic.cooldownTicks(tool)));
            }
        }
        level.send(new MutationStateS2C(
                tier.tierName(),
                tier.maxConcurrent(),
                intervalTicks(),
                ticksUntilNext,
                tier.rollMultiplier(),
                level.cardSourceKind(),
                beltCapacity(),
                lastEffects,
                tools,
                lockedSlots(),
                com.pvzce.server.LevelBuffSelection.resolveIds(level.activeBuffs()).stream()
                        .map(com.pvzce.api.util.Identifier::toString).toList(),
                wire));
        lastSentTick = level.tickCount();
        lastSentEntryCount = entries.size();
    }

    /** Whether one entry is acting, waiting, or held back - as the panel shows it. */
    public MutationStatus statusOf(Identifier mutationId) {
        for (MutationEntry entry : entries) {
            if (entry.id().equals(mutationId)) {
                return statusOf(entry);
            }
        }
        return MutationStatus.SUPPRESSED;
    }

    private MutationStatus statusOf(MutationEntry entry) {
        if (entry.isApplied()) {
            return MutationStatus.ACTIVE;
        }
        return entry.mutation().canRun(level) ? MutationStatus.SUPPRESSED : MutationStatus.WAITING;
    }
}
