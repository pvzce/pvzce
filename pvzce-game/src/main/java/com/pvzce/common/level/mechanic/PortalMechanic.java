package com.pvzce.common.level.mechanic;

import com.mojang.serialization.MapCodec;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.PortalData;
import com.pvzce.api.entity.EntityLayers;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.server.level.LevelServer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 斗转星移: two cells that are the same cell as far as a zombie is concerned.
 *
 * <p>A ground zombie that walks into one end of a pair comes out of the other, in the lane and at
 * the x that end names, and keeps walking. What makes it a mini-game rather than a shortcut is
 * where the other end is: the original pairs a lane the player has fortified with one they have
 * not, so a defence that only watches the left of the board loses to a portal it ignored.
 *
 * <p><b>The trigger is a crossing, not a cell.</b> The original's zombies always walk towards the
 * house, so "entered the portal's cell" is the same as "crossed its centre from the right" - but a
 * level may place portals anywhere, and a zombie that was *teleported onto* a portal's centre must
 * not immediately fall through it again. So the check is "was on the right of the centre last tick
 * and is on the left of it now", and the zombie that comes out of the far end is marked for a few
 * ticks: without that mark, a pair whose exit sits just right of the other end's trigger would
 * bounce the same zombie back and forth forever.
 *
 * <p>Only ground zombies travel. A balloon zombie flies over the lawn and a digger is underground;
 * both pass a portal the same way they pass a mower (see {@code MowerMechanic}), and the predicate
 * for that is the zombie's own layer rather than a list of ids kept here.
 */
public final class PortalMechanic implements LevelMechanic<PortalData> {
    /**
     * How far past the exit a teleported zombie is placed, in cells.
     *
     * <p>Just enough that it is clearly on the far side of the exit's centre - the crossing test
     * reads positions, so landing exactly on the centre would be "not yet through" for one tick and
     * then a crossing backwards.
     */
    public static final float EXIT_OFFSET = 0.51F;

    /**
     * How many ticks a zombie may not use a portal after coming out of one.
     *
     * <p>Half a second: long enough that the exit's own trigger cannot catch it (the offset above
     * already puts it past the centre) and short enough that a portal the player *wants* to send it
     * through still works.
     */
    public static final int IMMUNITY_TICKS = 30;

    /** Per-zombie bookkeeping: the ticks of immunity left, and the x it was at last tick. */
    private static final class State {
        final Map<Integer, Integer> immunity = new HashMap<>();
        final Map<Integer, Float> lastX = new HashMap<>();
    }

    private static State state(LevelServer level) {
        return level.mechanicState(PvzceIds.MECHANIC_PORTAL, State::new);
    }

    @Override
    public MapCodec<PortalData> codec() {
        return PortalData.MAP_CODEC;
    }

    @Override
    public void tick(LevelServer level, PortalData data) {
        State state = state(level);
        // Immunities first, and for every zombie that has one: a zombie that left the board while
        // immune must not keep its entry for the rest of the run.
        state.immunity.replaceAll((id, ticks) -> ticks - 1);
        state.immunity.values().removeIf(ticks -> ticks <= 0);

        // One position is read per zombie per tick, and then every pair is asked about it.
        //
        // The loop order is the whole of this method. Asking *inside* a per-pair loop means each
        // pair overwrites the remembered x before the next one reads it, so the second pair and
        // every pair after it compare the zombie's position against itself and can never see a
        // crossing - a level with two doors silently plays with one. The pairs are the inner loop
        // for that reason, and a zombie that came out of a door stops being asked (see below).
        for (ZombieEntity zombie : zombies(level)) {
            if (zombie.isRemoved() || !zombie.isAlive() || zombie.layer() != EntityLayers.GROUND) {
                continue;
            }
            float x = zombie.cellX();
            Float previous = state.lastX.put(zombie.id(), x);
            if (previous == null || state.immunity.containsKey(zombie.id())) {
                continue;
            }
            for (PortalData.Pair pair : data.pairs()) {
                if (crossedLeft(previous, x, pair.ax()) && zombie.gridY() == pair.ay()) {
                    teleport(level, state, zombie, pair.bx(), pair.by());
                    // One door per tick: the zombie is somewhere else now, and the position this
                    // tick's test was made against no longer describes it.
                    break;
                } else if (crossedLeft(previous, x, pair.bx()) && zombie.gridY() == pair.by()) {
                    teleport(level, state, zombie, pair.ax(), pair.ay());
                    break;
                }
            }
        }
    }

    /** Every zombie on the board, as its own list so the loop below cannot see a half-updated one. */
    private static List<ZombieEntity> zombies(LevelServer level) {
        List<ZombieEntity> found = new ArrayList<>();
        for (com.pvzce.server.entity.PvzceEntity entity : level.entities()) {
            if (entity instanceof ZombieEntity zombie) {
                found.add(zombie);
            }
        }
        return found;
    }

    /** True when the zombie's centre passed {@code portalX} from the right on this tick. */
    private static boolean crossedLeft(float previousX, float x, int portalX) {
        float centre = portalX + 0.5F;
        return previousX > centre && x <= centre;
    }

    /** Puts the zombie at the other end and gives it a moment before it may use one again. */
    private static void teleport(LevelServer level, State state, ZombieEntity zombie, int x, int y) {
        zombie.setCellX(x + EXIT_OFFSET);
        zombie.setCellY(y + 0.5F);
        state.immunity.put(zombie.id(), IMMUNITY_TICKS);
        // Where it is now is where the crossing test starts from next tick, or the teleport itself
        // would read as a crossing (the zombie jumped from one end's centre to the other's).
        state.lastX.put(zombie.id(), zombie.cellX());
    }

    @Override
    public List<String> validate(LevelDef def, PortalData data) {
        List<String> errors = new ArrayList<>();
        if (data.pairs().isEmpty()) {
            errors.add("This level declares a portal mechanic with no pairs");
        }
        for (PortalData.Pair pair : data.pairs()) {
            for (int[] cell : new int[][] {{pair.ax(), pair.ay()}, {pair.bx(), pair.by()}}) {
                if (cell[0] < 0 || cell[0] >= def.width() || cell[1] < 0 || cell[1] >= def.height()) {
                    errors.add("A portal pair names the cell " + cell[0] + "," + cell[1]
                            + ", which is off a " + def.width() + "x" + def.height() + " board");
                }
            }
            if (pair.ax() == pair.bx() && pair.ay() == pair.by()) {
                errors.add("A portal pair's two ends are the same cell, which is a portal to nowhere");
            }
        }
        return errors;
    }
}
