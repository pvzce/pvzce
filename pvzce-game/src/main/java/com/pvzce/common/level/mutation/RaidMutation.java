package com.pvzce.common.level.mutation;

import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.common.tag.PvzceTags;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.server.level.LevelServer;

import java.util.ArrayList;
import java.util.List;

/**
 * Something arrives, over and over: the five raid mutations.
 *
 * <p>"植物僵尸", "巨人突袭", "小鬼空投", "蹦极僵尸", "气球空袭" are the same sentence with different
 * nouns - <em>every so often, send this, here</em> - so they are one class parameterised by a spec
 * rather than five that would each have to get the countdown, the save and the lane choice right.
 * {@link ZombieCrisisMutation} is the same shape with a rolled subject; these pick from a tag or name
 * one outright.
 *
 * <p>Three things the spec decides, and they are the whole difference between the five:
 *
 * <ul>
 *   <li><b>How many.</b> One for most of them, three to five for the balloon raid, which is a flock
 *       rather than a visitor;</li>
 *   <li><b>Which lane.</b> {@link Lane#ANY} for anything that flies or walks in from the road,
 *       {@link Lane#LAND} for a Gargantuar - a zombie that cannot swim dropped into the pool would be
 *       a raid the player watches drown, which is a bug and not a threat. The engine's
 *       {@code spawnRowFor} would catch it, and this is the mutation not needing to be caught;</li>
 *   <li><b>Where.</b> Off the right edge, like a wave - a raid is pressure from the road, not
 *       something that appears in the middle of the lawn - except for the airdrop, which is
 *       <em>defined</em> by landing on the lawn.</li>
 * </ul>
 *
 * <p>A raid on a lawn that already has the field's own limit of zombies stands down for an interval,
 * exactly as the crisis does: a mutation that kept piling bodies on would turn a long run into a
 * slideshow.
 */
final class RaidMutation implements Mutation, MutationManager.SaveHandle {
    /** Which lanes a raid may use. */
    enum Lane {
        /** Any row of the board: fliers, and walkers that the spawn rule will place correctly. */
        ANY,
        /** Land rows only. */
        LAND,
        /** Water rows only. */
        WATER
    }

    /**
     * One raid's shape.
     *
     * @param pool        the tag to pick from, or {@code null} when {@code fixed} names the zombie
     * @param fixed       one zombie id, or {@code null} when {@code pool} is used
     * @param minCount    how many arrive at once, at least
     * @param maxCount    and at most
     * @param baseInterval the gap between raids, before the tier's multiplier
     * @param lane        which rows may be used
     * @param sameRow     true when everything in one raid shares a lane (a flock, a squad)
     * @param minColumn   the leftmost column a raid may land in, or {@code -1} for "off the edge"
     * @param maxColumn   and the rightmost, or {@code -1} for "off the edge"
     */
    record Spec(com.pvzce.api.tag.TagKey<com.pvzce.api.content.ZombieDef> pool,
                Identifier fixed, int minCount, int maxCount, int baseInterval,
                Lane lane, boolean sameRow, int minColumn, int maxColumn) {
        List<Identifier> poolIds() {
            if (fixed != null) {
                return BuiltInRegistries.ZOMBIES.containsKey(fixed) ? List.of(fixed) : List.of();
            }
            if (pool == null) {
                return List.of();
            }
            List<Identifier> ids = new ArrayList<>();
            for (Identifier id : PvzceTags.ZOMBIES.ids(pool)) {
                if (BuiltInRegistries.ZOMBIES.get(id) != null) {
                    ids.add(id);
                }
            }
            ids.sort(java.util.Comparator.comparing(Identifier::toString));
            return List.copyOf(ids);
        }

        boolean fromTheEdge() {
            return minColumn < 0 || maxColumn < 0;
        }
    }

    /** How many of a raid may be on the lawn at once, so a long run cannot lock the tick up. */
    private static final int MAX_ALIVE = 16;

    private final Identifier id;
    private final Spec spec;

    RaidMutation(Identifier id, Spec spec) {
        this.id = id;
        this.spec = spec;
    }

    /**
     * The five shipped raids.
     *
     * <p>Intervals are ordered by how much each one costs the player: a plant-headed zombie every
     * twenty seconds is pressure, a Gargantuar every forty is an event. The bungee is the longest
     * of the lot on purpose - it cannot be shot while it works and it <em>takes</em> a plant
     * rather than damaging it, so at 1800 it was one guaranteed lost plant every twenty seconds
     * (ten at HELL), which is not a raid but an eraser. Sixty seconds base leaves the player time
     * to rebuild between visits.
     */
    static List<Mutation> all() {
        return List.of(
                new RaidMutation(PvzceIds.MUTATION_ZOMBOTANY, new Spec(
                        PvzceTags.ZOMBIE_MUTATION_ZOMBOTANY, null, 1, 1, 1200,
                        Lane.ANY, true, -1, -1)),
                new RaidMutation(PvzceIds.MUTATION_GARGANTUAR_RAID, new Spec(
                        null, PvzceIds.id("gargantuar"), 1, 1, 2400, Lane.LAND, true, -1, -1)),
                new RaidMutation(PvzceIds.MUTATION_IMP_AIRDROP, new Spec(
                        null, PvzceIds.id("imp"), 1, 1, 900, Lane.ANY, true, 4, 6)),
                new RaidMutation(PvzceIds.MUTATION_BUNGEE_RAID, new Spec(
                        null, PvzceIds.id("bungee_zombie"), 1, 1, 3600, Lane.ANY, true, -1, -1)),
                new RaidMutation(PvzceIds.MUTATION_BALLOON_RAID, new Spec(
                        null, PvzceIds.id("balloon_zombie"), 3, 5, 1500, Lane.ANY, true, -1, -1)));
    }

    @Override
    public Identifier id() {
        return id;
    }

    @Override
    public boolean canRun(LevelServer level) {
        return !spec.poolIds().isEmpty();
    }

    @Override
    public Object apply(LevelServer level, Mutation.Roll roll) {
        return new Applied(level.mutations().difficulty().scaledInterval(spec.baseInterval()));
    }

    /**
     * On a restore the clock comes back and nothing new arrives.
     *
     * <p>The zombies it already sent are entities in the save; sending them again would double the
     * raid, and the raid is the mutation's whole effect.
     */
    @Override
    public Object applyFromSave(LevelServer level, Mutation.Roll roll) {
        return pendingState;
    }

    @Override
    public void tick(LevelServer level, Mutation.Roll roll, Object state) {
        if (!(state instanceof Applied applied)) {
            return;
        }
        if (level.hostileZombieCount() >= MAX_ALIVE) {
            return;
        }
        if (--applied.ticksUntilRaid > 0) {
            return;
        }
        applied.ticksUntilRaid = applied.interval();
        List<Identifier> pool = spec.poolIds();
        if (pool.isEmpty()) {
            return;
        }
        int count = spec.minCount() + (spec.maxCount() > spec.minCount()
                ? level.random().nextInt(spec.maxCount() - spec.minCount() + 1) : 0);
        int row = rowFor(level);
        for (int i = 0; i < count; i++) {
            Identifier zombieId = pool.get(level.random().nextInt(pool.size()));
            int lane = spec.sameRow() ? row : rowFor(level);
            float x = spec.fromTheEdge()
                    ? level.width() + 0.5F + i
                    : spec.minColumn() + level.random().nextInt(
                            Math.max(1, spec.maxColumn() - spec.minColumn() + 1)) + 0.5F;
            level.spawnZombie(zombieId, level.team(PvzceIds.ZOMBIE_TEAM), x, lane);
        }
    }

    /** The lane this raid uses, respecting the spec's own restriction. */
    private int rowFor(LevelServer level) {
        List<Integer> rows = switch (spec.lane()) {
            case LAND -> level.landRows();
            case WATER -> {
                List<Integer> water = new ArrayList<>();
                for (int y = 0; y < level.height(); y++) {
                    if (level.rowIsWater(y)) {
                        water.add(y);
                    }
                }
                yield water;
            }
            case ANY -> {
                List<Integer> all = new ArrayList<>();
                for (int y = 0; y < level.height(); y++) {
                    all.add(y);
                }
                yield all;
            }
        };
        if (rows.isEmpty()) {
            return Math.max(0, level.height() / 2);
        }
        return rows.get(level.random().nextInt(rows.size()));
    }

    @Override
    public CompoundTag saveState() {
        CompoundTag tag = new CompoundTag();
        if (savingState != null) {
            savingState.save(tag);
        }
        return tag;
    }

    @Override
    public void loadState(CompoundTag tag) {
        this.pendingState = Applied.load(tag);
    }

    /** The clock as it stands, set by the manager around {@link #saveState}. */
    private Applied savingState;
    /** What {@link #loadState} read, waiting for {@link #applyFromSave} to consume it. */
    private Applied pendingState;

    @Override
    public void savingState(Object state) {
        this.savingState = state instanceof Applied applied ? applied : null;
    }

    /** This activation's countdown. */
    private static final class Applied {
        private final int interval;
        private int ticksUntilRaid;

        private Applied(int intervalTicks) {
            this.interval = Math.max(1, intervalTicks);
            this.ticksUntilRaid = this.interval;
        }

        int interval() {
            return interval;
        }

        void save(CompoundTag tag) {
            tag.putInt("Interval", interval);
            tag.putInt("TicksUntilRaid", ticksUntilRaid);
        }

        static Applied load(CompoundTag tag) {
            if (tag == null || !tag.contains("Interval")) {
                return null;
            }
            Applied applied = new Applied(tag.getInt("Interval"));
            applied.ticksUntilRaid = Math.max(1, tag.getInt("TicksUntilRaid"));
            return applied;
        }
    }
}
