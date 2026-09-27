package com.pvzce.common.level.mechanic;

import com.mojang.serialization.MapCodec;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.SeedRainData;
import com.pvzce.api.content.mechanic.FieldSpec;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.server.entity.PvzceEntity;
import com.pvzce.server.level.LevelServer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * It's Raining Seeds: seed packets fall onto the lawn on a clock.
 *
 * <p>The mechanic is one decision repeated - <em>when</em>, <em>where</em> and <em>what</em> - and
 * the packets themselves are not new. A packet is a {@code CardDropEntity}, the same object a
 * broken vase leaves behind, picked up with the same packet ({@code PickUpCardC2S}) and planted
 * with the same one ({@code PlantHeldCardC2S} for free). What this class adds is only the clock
 * that puts them there; everything about what a packet <em>is</em> was settled by the vase work
 * and is deliberately not re-decided here.
 *
 * <p><b>The clock is saved.</b> A run resumed after the rain had already been falling for a minute
 * must not start counting from zero: the player's lawn is the state the packets produced, and the
 * timer is part of the same fact. What is <em>not</em> saved is which cards have already fallen -
 * a resumed run may see one more cherry bomb than a run that was never quit, and paying for that
 * with a counter per card would be a second, weaker copy of the same truth.
 */
public final class SeedRainMechanic implements LevelMechanic<SeedRainData> {
    /**
     * The cell a packet falls on, drawn fresh for each packet.
     *
     * <p>Occupied cells are skipped rather than stacked on: two packets in one cell are one packet
     * as far as the player is concerned (the click picks one, and the other is invisible under it),
     * so a drop that lands on a drop is a card the level promised and never delivered. The board is
     * nine by five, so a full lawn still leaves a cell for the next one.
     */
    /** How many ticks are left before the next packet falls, and how many of each card have. */
    private static final class Clock {
        int ticks;
        final Map<Identifier, Integer> dropped = new HashMap<>();
    }

    private static Clock clock(LevelServer level) {
        return level.mechanicState(PvzceIds.MECHANIC_SEED_RAIN, Clock::new);
    }

    @Override
    public MapCodec<SeedRainData> codec() {
        return SeedRainData.MAP_CODEC;
    }

    @Override
    public void onLevelCreated(LevelServer level, SeedRainData data) {
        // A countdown, so the declared start delay and the declared interval are the same kind of
        // number and the first packet can be late without the second one being late too.
        // `applySave` runs after this and overwrites it for a resumed run.
        clock(level).ticks = data.initialDelayTicks();
    }

    @Override
    public void tick(LevelServer level, SeedRainData data) {
        if (!level.gameState().equals(com.pvzce.common.network.packet.GameStateS2C.RUNNING)) {
            return;
        }
        Clock clock = clock(level);
        if (clock.ticks > 0) {
            clock.ticks--;
        }
        if (clock.ticks > 0) {
            return;
        }
        clock.ticks = data.intervalTicks();
        drop(level, data);
    }

    /** Draws a card and a free cell, and leaves a packet on it. */
    private void drop(LevelServer level, SeedRainData data) {
        Clock clock = clock(level);
        Identifier card = data.pick(level.random(), id -> clock.dropped.getOrDefault(id, 0));
        if (card == null) {
            // Every card in the pool is at its max_count, so this level's rain is over. Not an
            // error: an author who caps every card said so on purpose.
            return;
        }
        Random random = level.random();
        List<int[]> free = freeCells(level, random);
        if (free.isEmpty()) {
            // No room this time. The draw is *not* counted, because nothing fell: counting it
            // would spend a capped card on a packet that does not exist.
            return;
        }
        int[] cell = free.get(random.nextInt(free.size()));
        level.spawnCardDrop(card, cell[0], cell[1]);
        clock.dropped.merge(card, 1, Integer::sum);
    }

    /**
     * Every cell that has no packet on it already, in a shuffled order.
     *
     * <p>Shuffled rather than filtered-and-picked so the choice does not favour low coordinates -
     * the natural "walk the board and take the first free cell" reading would rain on the top-left
     * corner first every time a packet was cleared.
     */
    private static List<int[]> freeCells(LevelServer level, Random random) {
        Map<Long, Boolean> taken = new HashMap<>();
        for (PvzceEntity entity : level.entities()) {
            if (entity instanceof com.pvzce.server.entity.CardDropEntity drop && !drop.isRemoved()) {
                taken.put(key(drop.gridX(), drop.gridY()), Boolean.TRUE);
            }
        }
        List<int[]> cells = new ArrayList<>();
        for (int y = 0; y < level.height(); y++) {
            for (int x = 0; x < level.width(); x++) {
                if (!taken.containsKey(key(x, y))) {
                    cells.add(new int[]{x, y});
                }
            }
        }
        java.util.Collections.shuffle(cells, random);
        return cells;
    }

    private static long key(int x, int y) {
        return ((long) x << 32) | (y & 0xFFFFFFFFL);
    }

    @Override
    public List<String> validate(LevelDef def, SeedRainData data) {
        List<String> errors = new ArrayList<>(data.validate());
        List<Identifier> cards = new ArrayList<>();
        for (SeedRainData.Card card : data.cards()) {
            cards.add(card.card());
        }
        errors.addAll(LevelMechanics.unknownCards(cards, "seed rain card"));
        return errors;
    }

    /**
     * The rain's own fields, described as data.
     *
     * <p>The pool is edited as one line of card ids, the same way the belt's is: per-card weights
     * and caps are part of the format and survive an edit, but the editor has no weighted-list
     * widget yet (see {@code ConveyorMechanic.editorFields}).
     */
    @Override
    public List<FieldSpec> editorFields() {
        return List.of(
                FieldSpec.integer("interval_ticks", "pvzce.mechanic.seed_rain.field.interval_ticks",
                        SeedRainData.MIN_INTERVAL_TICKS, 6000),
                FieldSpec.integer("initial_delay_ticks",
                        "pvzce.mechanic.seed_rain.field.initial_delay_ticks", 0, 6000),
                new FieldSpec.Ref("cards", "pvzce.mechanic.seed_rain.field.cards", "slot", true));
    }

    @Override
    public void collectSave(LevelServer level, SeedRainData data, CompoundTag root) {
        root.putInt("SeedRain", clock(level).ticks);
    }

    @Override
    public void applySave(LevelServer level, SeedRainData data, CompoundTag root) {
        if (root.contains("SeedRain")) {
            clock(level).ticks = Math.max(0, root.getInt("SeedRain"));
        }
    }
}
