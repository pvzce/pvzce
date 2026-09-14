package com.pvzce.common.level.mechanic;

import com.mojang.serialization.MapCodec;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.MowerData;
import com.pvzce.api.entity.EntityLayers;
import com.pvzce.common.PvzceConstants;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.PvzceParticles;
import com.pvzce.common.PvzceSounds;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PacketStruct;
import com.pvzce.common.network.packet.MechanicSyncS2C;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.common.nbt.ListTag;
import com.pvzce.common.nbt.Tag;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.server.level.LevelServer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lawn mowers: the last line of defence, one per row.
 *
 * <p>The original's rule, and this one: a mower waits just off the left edge of its row; the
 * first ground zombie to reach the house starts it; it drives right, destroying everything it
 * passes in that row; it leaves the board and is gone. A row whose mower has been used is
 * open, so the next zombie to walk in loses the level - which is what makes the mower a last
 * resort rather than a health bar.
 *
 * <p><strong>Implicit by default.</strong> {@link LevelMechanics#effective} adds this
 * mechanic to every level that does not declare it, so ordinary levels have mowers without
 * saying anything and every level written before mowers existed gained them for free. That is
 * the same shape as the implicit {@code deck} card source: the absence of a block is a
 * statement, not a gap. A level that wants different rows declares {@link MowerData}, and the
 * one that wants none lists an empty row set (Wall-nut Bowling, as in the original).
 *
 * <p><strong>What a mower does not touch</strong>: fliers still in the air and diggers still
 * underground. The predicate is the zombie's own layer - the same one the projectile router
 * uses - so a balloon zombie flies over the mower and a miner is not run over from the
 * surface; both still cost the player the level when they reach the house, exactly as in the
 * original.
 *
 * <p>The run state lives in a {@link Rig} the level owns (see
 * {@link LevelServer#mechanicState}), because a mechanic instance is a shared registry entry
 * and two levels must not share one lawn's mowers.
 */
public final class MowerMechanic implements LevelMechanic<MowerData> {
    /** Where an unused mower waits: its centre half a cell left of the first column. */
    public static final float IDLE_X = -0.5F;
    /**
     * The zombie position that starts a mower.
     *
     * <p>The left edge of the first column: a zombie eating a plant in column 0 stands at
     * about 0.5 and must not trigger it, while one that has walked past the last plant is at
     * the house.
     */
    public static final float TRIGGER_X = 0.05F;
    /** How fast a running mower crosses the lawn, in cells per second. */
    public static final float SPEED = 3.3F;
    /** How far ahead of itself a running mower destroys what it touches. */
    public static final float HIT_RANGE = 0.55F;
    /** How far past the right edge it has to get before it is off the board. */
    public static final float EXIT_MARGIN = 0.8F;
    /** How often a rolling mower puffs dust. */
    private static final int CLOUD_INTERVAL_TICKS = 4;
    /** How often a rolling mower's position is streamed, in ticks. */
    private static final int SYNC_INTERVAL_TICKS = 3;

    /** The NBT key this mechanic's run state is written under. */
    private static final String KEY_MOWERS = "Mowers";

    /** What one row's mower is doing. The numbers travel on the wire, so they are fixed. */
    public static final int STATE_READY = 0;
    public static final int STATE_ROLLING = 1;
    public static final int STATE_USED = 2;

    @Override
    public MapCodec<MowerData> codec() {
        return MowerData.MAP_CODEC;
    }

    @Override
    public List<String> validate(LevelDef def, MowerData data) {
        return data.validate(def.height());
    }

    @Override
    public void onLevelCreated(LevelServer level, MowerData data) {
        // Built eagerly so the client's first sync carries real rows even for a level that
        // has not ticked yet, and so every later hook finds the same rig.
        rig(level, data);
    }

    @Override
    public void tick(LevelServer level, MowerData data) {
        Rig rig = rig(level, data);
        rig.tick(level);
        // Sent when something changed, and periodically while something is moving so the
        // client can draw it crossing the lawn. An untouched level sends nothing at all.
        if (rig.dirty || (rig.anyRolling() && level.tickCount() % SYNC_INTERVAL_TICKS == 0)) {
            rig.dirty = false;
            level.send(MechanicSyncS2C.of(PvzceIds.MECHANIC_MOWER, State.CODEC, rig.state()));
        }
    }

    @Override
    public void collectSave(LevelServer level, MowerData data, CompoundTag root) {
        Rig rig = rig(level, data);
        ListTag rows = new ListTag();
        for (Map.Entry<Integer, Rig.Mower> entry : rig.mowers.entrySet()) {
            CompoundTag tag = new CompoundTag();
            tag.putInt("Row", entry.getKey());
            tag.putInt("State", entry.getValue().state);
            tag.putFloat("X", entry.getValue().x);
            rows.add(tag);
        }
        root.put(KEY_MOWERS, rows);
    }

    @Override
    public void applySave(LevelServer level, MowerData data, CompoundTag root) {
        Rig rig = rig(level, data);
        // Rows the save says nothing about keep the state they were built with (ready), so a
        // level saved by a version with fewer rows still loads.
        for (Tag entry : root.getList(KEY_MOWERS).values()) {
            if (!(entry instanceof CompoundTag tag)) {
                continue;
            }
            Rig.Mower mower = rig.mowers.get(tag.getInt("Row"));
            if (mower == null) {
                continue;
            }
            mower.state = Math.max(STATE_READY, Math.min(STATE_USED, tag.getInt("State")));
            mower.x = tag.getFloat("X");
        }
        // The client is looking at a freshly created board; it has to hear about a mower
        // this save already spent.
        rig.dirty = true;
    }

    // ------------------------------------------------------------------
    // Run state
    // ------------------------------------------------------------------

    /** The rows' mowers for one level instance. */
    public static final class Rig {
        private final Map<Integer, Mower> mowers = new LinkedHashMap<>();
        private int width;
        private boolean dirty = true;

        private Rig(LevelServer level, MowerData data) {
            this.width = level.width();
            for (int row : data.rowsFor(level.height())) {
                mowers.put(row, new Mower(IDLE_X));
            }
        }

        /** One mower: where it is and what it is doing. */
        private static final class Mower {
            private int state = STATE_READY;
            private float x;
            /** Ticks until the next dust puff, so the trail is even. */
            private int cloudTimer;

            private Mower(float x) {
                this.x = x;
            }
        }

        private void tick(LevelServer level) {
            this.width = level.width();
            for (Map.Entry<Integer, Mower> entry : mowers.entrySet()) {
                Mower mower = entry.getValue();
                if (mower.state == STATE_READY) {
                    if (triggered(level, entry.getKey())) {
                        start(level, entry.getKey(), mower);
                    }
                } else if (mower.state == STATE_ROLLING) {
                    roll(level, entry.getKey(), mower);
                }
            }
        }

        /** True when a ground zombie in this row has reached the house. */
        private boolean triggered(LevelServer level, int row) {
            for (ZombieEntity zombie : groundZombiesInRow(level, row)) {
                if (zombie.cellX() <= TRIGGER_X) {
                    return true;
                }
            }
            return false;
        }

        private void start(LevelServer level, int row, Mower mower) {
            mower.state = STATE_ROLLING;
            mower.x = IDLE_X;
            mower.cloudTimer = 0;
            dirty = true;
            level.emitEffect("", mower.x, row + 0.5F, PvzceSounds.EFFECT_LAWNMOWER);
        }

        private void roll(LevelServer level, int row, Mower mower) {
            mower.x += SPEED / PvzceConstants.TICKS_PER_SECOND;

            // Everything the mower has caught up with dies, whatever it is wearing: the mower
            // is not a projectile, so armour does not stop it (armour stops *shots*).
            for (ZombieEntity zombie : groundZombiesInRow(level, row)) {
                if (zombie.cellX() <= mower.x + HIT_RANGE) {
                    mow(level, zombie);
                }
            }

            if (--mower.cloudTimer <= 0) {
                mower.cloudTimer = CLOUD_INTERVAL_TICKS;
                level.emitEffect(PvzceParticles.MOWER_CLOUD.toString(), mower.x - 0.3F, row + 0.4F, null);
            }

            if (mower.x >= width + EXIT_MARGIN) {
                // Gone for the rest of the level: the row is open now, and that is the whole
                // cost of having used it.
                mower.state = STATE_USED;
                dirty = true;
            }
        }

        private void mow(LevelServer level, ZombieEntity zombie) {
            level.emitEffect(PvzceParticles.MOWERED_ZOMBIE_HEAD.toString(),
                    zombie.cellX(), zombie.cellY(), null);
            level.emitEffect(PvzceParticles.MOWERED_ZOMBIE_ARM.toString(),
                    zombie.cellX(), zombie.cellY(), null);
            level.emitEffect(PvzceParticles.MOWER_CLOUD_POWIE.toString(),
                    zombie.cellX(), zombie.cellY(), null);
            // Damage equal to the zombie's own health: the mower destroys armour and body
            // together, which `damageImpact` deliberately does not - there, armour absorbs one
            // hit and shatters without passing anything through to the body.
            zombie.damageBody(Math.max(1, zombie.health()), level);
        }

        /** Ground-layer zombies of one row, as a copy: mowing removes them. */
        private static List<ZombieEntity> groundZombiesInRow(LevelServer level, int row) {
            List<ZombieEntity> found = new ArrayList<>();
            for (var entity : level.entities()) {
                // Corpses are left where they fell: the mower is a threat to what is
                // still coming, and `mow` would otherwise shred a body that is already
                // playing its death animation.
                if (entity instanceof ZombieEntity zombie && zombie.isAlive()
                        && zombie.gridY() == row && zombie.layer() == EntityLayers.GROUND) {
                    found.add(zombie);
                }
            }
            return found;
        }

        private boolean anyRolling() {
            for (Mower mower : mowers.values()) {
                if (mower.state == STATE_ROLLING) {
                    return true;
                }
            }
            return false;
        }

        /** How many mowers are still parked in their row, i.e. never used. */
        public int readyCount() {
            int ready = 0;
            for (Mower mower : mowers.values()) {
                if (mower.state == STATE_READY) {
                    ready++;
                }
            }
            return ready;
        }

        /** The wire (and test) view of this rig. */
        public State state() {
            List<Row> rows = new ArrayList<>(mowers.size());
            for (Map.Entry<Integer, Mower> entry : mowers.entrySet()) {
                rows.add(new Row(entry.getKey(), entry.getValue().state, entry.getValue().x));
            }
            return new State(rows);
        }
    }

    /** One row's mower as the client sees it. */
    public record Row(int row, int state, float x) {
        public static final PacketStruct.Codec<Row> CODEC = PacketStruct.<Row>builder()
                .field(Row::row, PacketByteBuf::writeInt, PacketByteBuf::readInt)
                .field(Row::state, PacketByteBuf::writeInt, PacketByteBuf::readInt)
                .field(Row::x, PacketByteBuf::writeFloat, PacketByteBuf::readFloat)
                .build(values -> new Row((Integer) values.get(0), (Integer) values.get(1),
                        (Float) values.get(2)));

        public void encode(PacketByteBuf buf) {
            CODEC.encode(this, buf);
        }

        public static Row decode(PacketByteBuf buf) {
            return CODEC.decode(buf);
        }
    }

    /**
     * Every row's mower, as the client needs it.
     *
     * <p>A whole-table replacement like the conveyor's card bar: the rows are few, they change
     * together, and "this row has no mower any more" is not something an incremental update
     * can say.
     */
    public record State(List<Row> rows) {
        public static final PacketStruct.Codec<State> CODEC = PacketStruct.<State>builder()
                .list(State::rows, Row::encode, Row::decode)
                .build(values -> new State(castRows(values.get(0))));

        public State {
            rows = List.copyOf(rows);
        }

        @SuppressWarnings("unchecked")
        private static List<Row> castRows(Object value) {
            return (List<Row>) value;
        }
    }

    /** The level's mower rig; created on first use so every hook sees the same one. */
    private static Rig rig(LevelServer level, MowerData data) {
        return level.mechanicState(PvzceIds.MECHANIC_MOWER, () -> new Rig(level, data));
    }
}
