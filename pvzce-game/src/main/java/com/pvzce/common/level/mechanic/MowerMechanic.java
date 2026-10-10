package com.pvzce.common.level.mechanic;

import com.pvzce.common.level.SceneBoard;
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
    /**
     * What one mower blow is worth.
     *
     * <p>A constant rather than {@code zombie.health()}: the registered {@code pvzce:mower}
     * damage type ignores armour, so this is "more than anything on the lawn has", and
     * reading the target's health would have made the damage depend on what it hit - a
     * Gargantuar is not supposed to survive one mower because a formula said so.
     */
    private static final int MOWER_DAMAGE = PvzceConstants.MOWER_DAMAGE;

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
            tag.putInt("Lane", entry.getValue().lane);
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
            mower.lane = tag.contains("Lane") ? tag.getInt("Lane") : tag.getInt("Row");
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
                mowers.put(row, new Mower(IDLE_X, data.kindFor(row)));
            }
        }

        /** One mower: where it is and what it is doing. */
        private static final class Mower {
            private int lane;
            private int state = STATE_READY;
            private float x;
            /** Ticks until the next dust puff, so the trail is even. */
            private int cloudTimer;
            /** Set by a hand release, whose request has no tick to emit the sound on. */
            private boolean launchSoundPending;
            /**
             * Which vehicle this row's mower is.
             *
             * <p>Carried rather than looked up at the moment it is needed: the rig outlives the
             * data it was built from in every way that matters (the level can be reloaded, the
             * registry repopulated), and "what is standing in this row" is a fact about the rig.
             */
            private final MowerData.MowerKind kind;

            private Mower(float x, MowerData.MowerKind kind) {
                this.x = x;
                this.kind = kind;
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
                    // A mower the player released between ticks still has its launch sound
                    // to play, and this is the first tick it can be played on.
                    playLaunchSound(level, entry.getKey(), mower);
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

        /**
         * Sends a mower rolling.
         *
         * <p>{@code level} is the one the sound is played at, and it is absent when the
         * player released the mower from the HUD: that request arrives between ticks, and
         * {@code LevelServer.emitEffect} is a no-op without an active tick bridge - so the
         * sound is deferred by a flag and played on the rig's next tick instead. The state
         * change itself is immediate either way, which is what matters: the next entity sync
         * has to carry the mower as rolling.
         */
        private void start(LevelServer level, int row, Mower mower) {
            mower.state = STATE_ROLLING;
            mower.lane = row;
            mower.x = IDLE_X;
            mower.cloudTimer = 0;
            mower.launchSoundPending = true;
            dirty = true;
            playLaunchSound(level, row, mower);
        }

        private void playLaunchSound(LevelServer level, int row, Mower mower) {
            if (level == null || !mower.launchSoundPending) {
                return;
            }
            mower.launchSoundPending = false;
            // The kind's own sound when it names one - a pool cleaner does not sound like a
            // mower - and the ordinary mower's otherwise, so a pack that only ships art still
            // gets a launch.
            level.emitEffect("", mower.x, row + 0.5F,
                    mower.kind.sound().orElse(PvzceSounds.EFFECT_LAWNMOWER));
        }

        private void roll(LevelServer level, int row, Mower mower) {
            float from = mower.x;
            mower.x += SPEED / PvzceConstants.TICKS_PER_SECOND;
            PortalMechanic.Exit exit = PortalMechanic.cross(level, -row - 1, from, mower.x, mower.lane);
            if (exit != null) {
                mower.x = exit.x();
                mower.lane = exit.row();
                dirty = true;
            }
            row = mower.lane;

            // Everything the mower has caught up with dies, whatever it is wearing: the mower
            // is not a projectile, so armour does not stop it (armour stops *shots*).
            for (ZombieEntity zombie : groundZombiesInRow(level, row)) {
                if (Math.abs(zombie.cellX() - mower.x) <= HIT_RANGE) {
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
            // The head and the arm are the *death's* to throw, not this method's: emitting them
            // here as well as the ordinary drop gave every mowed zombie two heads. The mower
            // says what it does with `pvzce:mower`'s `dismembers` flag, and the body answers it
            // with its own pair (see ZombieEntity.damageBody).
            level.emitEffect(PvzceParticles.MOWER_CLOUD_POWIE.toString(),
                    zombie.cellX(), zombie.cellY(), null);
            // The `pvzce:mower` damage type destroys armour and body together, which
            // `pvzce:impact` deliberately does not - there, armour absorbs one hit and
            // shatters without passing anything through to the body.
            zombie.damage(MOWER_DAMAGE, ZombieEntity.damageType(PvzceIds.DAMAGE_MOWER), level);
        }

        /** Ground-layer zombies of one row, as a copy: mowing removes them. */
        private static List<ZombieEntity> groundZombiesInRow(LevelServer level, int row) {
            List<ZombieEntity> found = new ArrayList<>();
            for (var entity : level.entities()) {
                // Corpses are left where they fell: the mower is a threat to what is
                // still coming, and `mow` would otherwise shred a body that is already
                // playing its death animation.
                if (entity instanceof ZombieEntity zombie && zombie.isAlive()
                        && zombie.gridY() == row && zombie.layer() == EntityLayers.GROUND
                        && zombie.surfaceId().equals(SceneBoard.DEFAULT_SURFACE)) {
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

        /**
         * Sends one row's parked mower early, and says whether it went.
         *
         * <p>The same start the zombie trigger uses, so a hand-released mower is not a
         * different thing: it makes the same noise, rolls at the same speed, mows the same
         * zombies and is spent the same way. The row is checked here rather than by the
         * caller, so "there is no mower in row 3" and "row 3's mower is already gone" are
         * the same answer, and a second packet for the same row is a no-op instead of a
         * second launch.
         *
         * <p>Deliberately allowed with nothing in the row: the player may be sending it
         * ahead of a wave, which is the whole point of being able to do it by hand.
         */
        public boolean release(int row) {
            Mower mower = mowers.get(row);
            if (mower == null || mower.state != STATE_READY) {
                return false;
            }
            start(null, row, mower);
            return true;
        }

        /**
         * Puts a spent mower back in its row.
         *
         * <p>For the {@code random_supply} mutation - the one thing a player cannot do for
         * themselves. Restoring is the exact inverse of the row's own trigger, so a mower that comes
         * back is parked where it started, rolls when the next zombie reaches it, and is spent
         * again: nothing about it remembers that it had already run.
         *
         * <p>A row that still has its mower, or that never had one, is left alone rather than
         * given a second: "one per row" is the lawn's own rule, and a mutation that could stack two
         * in a lane would be a different mutation.
         *
         * @return true when a mower was restored
         */
        public boolean restore(int row) {
            Mower mower = mowers.get(row);
            if (mower == null || mower.state == STATE_READY) {
                return false;
            }
            mower.state = STATE_READY;
            // Parked exactly where a fresh one starts, so a restored mower is drawn and triggered
            // like any other - see the constructor.
            mower.x = IDLE_X;
            mower.launchSoundPending = false;
            return true;
        }

        /**
         * The rows this level actually has mowers in, so a caller can tell "that row has no
         * mower" from "that mower is gone" without knowing the level data.
         */
        public boolean hasRow(int row) {
            return mowers.containsKey(row);
        }

        /** The wire (and test) view of this rig. */
        public State state() {
            List<Row> rows = new ArrayList<>(mowers.size());
            for (Map.Entry<Integer, Mower> entry : mowers.entrySet()) {
                Mower mower = entry.getValue();
                rows.add(new Row(entry.getKey(), mower.state, mower.x,
                        mower.state == STATE_ROLLING ? mower.lane : entry.getKey()));
            }
            return new State(rows);
        }
    }

    /** One row's mower as the client sees it. */
    public record Row(int row, int state, float x, int lane) {
        public Row(int row, int state, float x) {
            this(row, state, x, row);
        }
        public static final PacketStruct.Codec<Row> CODEC = PacketStruct.<Row>builder()
                .field(Row::row, PacketByteBuf::writeInt, PacketByteBuf::readInt)
                .field(Row::state, PacketByteBuf::writeInt, PacketByteBuf::readInt)
                .field(Row::x, PacketByteBuf::writeFloat, PacketByteBuf::readFloat)
                .field(Row::lane, PacketByteBuf::writeInt, PacketByteBuf::readInt)
                .build(values -> new Row((Integer) values.get(0), (Integer) values.get(1),
                        (Float) values.get(2), (Integer) values.get(3)));

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
