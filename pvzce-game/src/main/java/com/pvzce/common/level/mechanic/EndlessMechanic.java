package com.pvzce.common.level.mechanic;

import com.mojang.serialization.MapCodec;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.WaveDef;
import com.pvzce.api.content.mechanic.MechanicData;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;

import java.util.ArrayList;
import java.util.List;

/**
 * Waves that never run out: the original's Survival Endless.
 *
 * <p>A level with this mechanic cycles a small set of wave shapes and inflates each cycle, so the
 * table a player meets in minute thirty is not the one they met in minute one. The expansion
 * happens once, when the level is built ({@link #expand}), because everything downstream - the wave
 * director, the progress bar, the save file - already understands a long wave list, and teaching
 * all of them about a generator would be a much larger change than generating the list.
 *
 * <p>The number of waves is finite and enormous rather than infinite. "Never ends" is a property of
 * the count, not of the type: at the pace these waves arrive, running out would take about two and
 * a half hours of continuous play, and a level that somehow reached the end would simply be won -
 * which is a better failure than a loop that never terminates.
 *
 * <p>The tier's own rules still apply. Zombie health and speed come from the zombie definitions, the
 * spawn cadence from {@code zombie_spawn_speed_multiplier} (which a mutation may rewrite), and the
 * composition from the pools below - so an endless level is a level like any other, with one long
 * table.
 */
public final class EndlessMechanic implements LevelMechanic<MechanicData.Empty> {
    /** A marker block: {@code {"type": "pvzce:endless"}} and nothing else. */
    public static final MapCodec<MechanicData.Empty> CODEC = MapCodec.unit(MechanicData.Empty.INSTANCE);

    /**
     * How many waves the table is expanded to.
     *
     * <p>Three thousand nine hundred and ninety, which at the thirty-to-sixty seconds a wave takes
     * is about a day and a half of continuous play - "endless" by any measure a session has.
     *
     * <p>The number is not free: the wave-type list is one field of the level payload, and a
     * collection on the wire is capped at {@code PacketByteBuf.MAX_COLLECTION_SIZE}. A table longer
     * than that does not merely truncate - the level fails to send at all, and the client is
     * disconnected with "Collection too large". The cap is read from the protocol rather than
     * repeated, so raising it cannot leave this behind.
     */
    public static final int TOTAL_WAVES = com.pvzce.common.network.PacketByteBuf.MAX_COLLECTION_SIZE - 106;

    /** The zombie pool a cycle starts with: what a pool lawn can actually walk through. */
    private static final List<Identifier> EARLY_POOL = List.of(
            PvzceIds.id("basic_zombie"),
            PvzceIds.id("conehead_zombie"),
            PvzceIds.id("flag_zombie"));

    /** Everything the deep cycles add, in the order the difficulty ramps. */
    private static final List<Identifier> RAMP = List.of(
            PvzceIds.id("buckethead_zombie"),
            PvzceIds.id("newspaper_zombie"),
            PvzceIds.id("pole_vaulter_zombie"),
            PvzceIds.id("football_zombie"),
            PvzceIds.id("door_zombie"),
            PvzceIds.id("gargantuar"));

    /** Rows a land zombie may use on the pool board, and the rows the water zombies use. */
    private static final List<Integer> LAND_ROWS = List.of(0, 1, 4, 5);
    private static final List<Integer> WATER_ROWS = List.of(2, 3);
    /**
     * The floatie zombies, which are the only ones that may walk the two water rows.
     *
     * <p>Not in the ramp, and deliberately: "which rows a zombie may use" is a property of the
     * zombie's art and behaviour, and an ordinary zombie sent into the pool row drowns. So these
     * three are added by the water half of every cycle instead of by the pool of land zombies.
     */
    private static final List<Identifier> WATER_POOL = List.of(
            PvzceIds.id("ducky_tube_zombie"),
            PvzceIds.id("ducky_tube_conehead_zombie"),
            PvzceIds.id("ducky_tube_buckethead_zombie"));

    /** How many waves make one cycle: two ordinary, one huge. */
    private static final int WAVES_PER_CYCLE = 3;

    @Override
    public MapCodec<MechanicData.Empty> codec() {
        return CODEC;
    }

    @Override
    public List<String> validate(LevelDef def, MechanicData.Empty data) {
        List<String> errors = new ArrayList<>();
        if (!def.waves().isEmpty()) {
            // The templates replace the table rather than adding to it: two answers to "what comes
            // next" is one too many, and the one that loses would be the author's.
            errors.add("endless expands its own wave table, so the " + def.waves().size()
                    + " waves this level writes are ignored; delete them or drop the mechanic");
        }
        for (Identifier zombie : EARLY_POOL) {
            if (BuiltInRegistries.ZOMBIES.get(zombie) == null) {
                errors.add("endless names unknown zombie '" + zombie + "'");
            }
        }
        return errors;
    }

    /**
     * The long wave table this level runs with.
     *
     * <p>Called from the level's constructor, which is the one moment the expansion can happen: a
     * level's waves are read once, and a mutation that rewrote the pacing later has the rules to do
     * it with.
     *
     * @param levelWidth how many rows the board has, for the Zombie-Land rows the entries use
     */
    public static List<WaveDef> expand(int levelWidth) {
        return expand(levelWidth, TOTAL_WAVES);
    }

    /** The table, cut short at {@code total} waves; the short form is what the tests use. */
    static List<WaveDef> expand(int rows, int total) {
        List<WaveDef> waves = new ArrayList<>(Math.max(0, total));
        int cycle = 0;
        for (int index = 0; index < total; index++) {
            if (index > 0 && index % WAVES_PER_CYCLE == 0) {
                cycle++;
            }
            waves.add(waveFor(index % WAVES_PER_CYCLE, cycle, rows));
        }
        return List.copyOf(waves);
    }

    /**
     * One wave of a cycle.
     *
     * <p>Three shapes, and the cycle's number inflates all of them: two ordinary waves that arrive
     * faster and heavier, then a huge one with a warning. The gap between them shortens as well -
     * a wave that took a minute to arrive in cycle one arrives in twenty seconds by cycle ten - so
     * the pressure comes from the clock as much as from the count.
     */
    private static WaveDef waveFor(int slot, int cycle, int rows) {
        int count = 2 + cycle + slot * 2;
        float delayFactor = Math.max(0.25F, 1F - cycle * 0.05F);
        int delay = Math.round(1500 * delayFactor);
        int interval = Math.max(60, 420 - cycle * 15);
        List<WaveDef.Entry> entries = entries(count, cycle, rows);
        if (slot == WAVES_PER_CYCLE - 1) {
            return new WaveDef(WaveDef.WaveType.HUGE, Math.round(delay * 1.2F), 180,
                    entries, Math.max(40, interval / 2));
        }
        return new WaveDef(WaveDef.WaveType.SMALL, delay, 0, entries, interval);
    }

    /** The zombies of one wave: land rows and water rows, in the mix the cycle has unlocked. */
    private static List<WaveDef.Entry> entries(int count, int cycle, int rows) {
        List<WaveDef.Entry> entries = new ArrayList<>();
        List<Identifier> land = landPool(cycle);
        int waterRows = rows >= 4 ? 2 : 0;
        int landCount = waterRows > 0 ? Math.max(1, count - waterRows) : count;
        entries.add(new WaveDef.Entry(land.get(cycle % land.size()), landCount, LAND_ROWS));
        if (waterRows > 0) {
            Identifier floater = WATER_POOL.get(cycle % WATER_POOL.size());
            entries.add(new WaveDef.Entry(floater, 1 + cycle / 3, WATER_ROWS));
        }
        return entries;
    }

    /**
     * The land zombies a cycle may send: the starter pool plus one more per two cycles.
     *
     * <p>A ramp rather than a fixed pool with a count multiplier, because "more of the same" stops
     * being harder once the player has a full board, while "and now there are Footballs" does not.
     */
    private static List<Identifier> landPool(int cycle) {
        List<Identifier> pool = new ArrayList<>(EARLY_POOL);
        int unlocked = Math.min(RAMP.size(), 1 + cycle / 2);
        for (int i = 0; i < unlocked; i++) {
            pool.add(RAMP.get(i));
        }
        return pool;
    }
}
