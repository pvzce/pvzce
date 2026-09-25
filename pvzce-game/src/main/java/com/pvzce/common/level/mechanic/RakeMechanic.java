package com.pvzce.common.level.mechanic;

import com.mojang.serialization.MapCodec;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.RakeData;
import com.pvzce.api.entity.EntityLayers;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.PvzceParticles;
import com.pvzce.common.PvzceSounds;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PacketStruct;
import com.pvzce.common.network.packet.MechanicSyncS2C;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.server.level.LevelServer;

import java.util.ArrayList;
import java.util.List;

/**
 * The rake: it lies on the lawn and flattens the first zombie to walk into it.
 *
 * <p>The shop item, and the simplest thing on the board. One rake, on one lane, laid down when the
 * level starts. The first ground zombie to reach it is destroyed; the rake goes with it. Unlike a
 * mower it does not roll, does not clear what is behind the zombie, and does not come back - which
 * is the whole difference between a last line of defence and a head start.
 *
 * <p><b>Whose rake it is.</b> The mechanic is only ever installed for a world whose profile bought
 * it; {@code LevelServer} adds a {@link RakeData#RANDOM} block when the player owns the rake card
 * and the level did not declare one. So the level file's block means "this level decides", an
 * absent block means "the player's rake, if they have one", and an explicit empty row set means
 * "not here" - the same three-way shape {@code MowerData} uses.
 *
 * <p><b>Where it stands.</b> The same anchor a parked mower uses, half a cell left of the first
 * column, and the same trigger line. That is not a coincidence to be preserved for its own sake:
 * both are "at the house", and a rake a quarter of a cell further out would kill a zombie the
 * mower would have let through, which is a difference nobody could see and everybody would feel.
 *
 * <p><b>What it does not touch.</b> Fliers and diggers, for the mower's reason: the predicate is
 * the zombie's own layer. A balloon zombie flies over the rake exactly as it flies over a mower.
 */
public final class RakeMechanic implements LevelMechanic<RakeData> {
    /** Where a rake lies: the mower's own idle anchor, half a cell left of column 0. */
    public static final float IDLE_X = MowerMechanic.IDLE_X;
    /** The zombie position that trips it; the mower's own line, so the two agree. */
    public static final float TRIGGER_X = MowerMechanic.TRIGGER_X;
    /**
     * What one rake is worth.
     *
     * <p>Read from the mower's own constant rather than declared again: a rake "kills the zombie
     * that reaches it", and the mower's number is what that sentence already means in this build.
     * A second constant would be a second answer to "how hard is a lawn fixture".
     */
    private static final int RAKE_DAMAGE = 100_000;
    /** The NBT key this mechanic's run state is written under. */
    private static final String KEY_RAKE = "Rake";

    @Override
    public MapCodec<RakeData> codec() {
        return RakeData.MAP_CODEC;
    }

    @Override
    public List<String> validate(LevelDef def, RakeData data) {
        return data.validate(def.height());
    }

    @Override
    public void onLevelCreated(LevelServer level, RakeData data) {
        Rig rig = rig(level, data);
        rig.place(level, data);
    }

    @Override
    public void tick(LevelServer level, RakeData data) {
        Rig rig = rig(level, data);
        if (rig.dirty) {
            rig.dirty = false;
            level.send(MechanicSyncS2C.of(PvzceIds.MECHANIC_RAKE, State.CODEC, rig.state()));
            return;
        }
        if (!rig.armed()) {
            return;
        }
        for (ZombieEntity zombie : Rig.groundZombiesInRow(level, rig.row)) {
            if (zombie.cellX() <= TRIGGER_X) {
                rig.spring(level, zombie);
                return;
            }
        }
    }

    @Override
    public void collectSave(LevelServer level, RakeData data, CompoundTag root) {
        Rig rig = rig(level, data);
        CompoundTag tag = new CompoundTag();
        tag.putInt("Row", rig.row);
        // A byte rather than a boolean: the tag system has no boolean, and the pool
        // cleaner's own state is written the same way.
        tag.putByte("Spent", (byte) (rig.spent ? 1 : 0));
        root.put(KEY_RAKE, tag);
    }

    @Override
    public void applySave(LevelServer level, RakeData data, CompoundTag root) {
        Rig rig = rig(level, data);
        CompoundTag tag = root.getCompound(KEY_RAKE);
        if (tag == null) {
            // A save written before the rake existed: the one the level just placed stands, which
            // is "a resumed run still has its rake" rather than "a resumed run has none".
            return;
        }
        rig.row = tag.getInt("Row");
        rig.spent = tag.getInt("Spent") != 0;
        rig.dirty = true;
    }

    /** One level's rake. */
    public static final class Rig {
        private int row = -1;
        private boolean spent;
        private boolean dirty;

        /**
         * Lays the rake down, once, on a lane the level allows.
         *
         * <p>The lane is picked from the level's dice rather than from a fresh one, so a run
         * watched twice from the same seed has its rake in the same place.
         */
        private void place(LevelServer level, RakeData data) {
            List<Integer> lanes = data.rowsFor(level.height());
            if (lanes.isEmpty()) {
                return;
            }
            row = lanes.get(level.random().nextInt(lanes.size()));
            spent = false;
            dirty = true;
        }

        /** True while the rake is still lying there. */
        public boolean armed() {
            return row >= 0 && !spent;
        }

        /** The lane it is on, or -1 when the level has none. */
        public int row() {
            return row;
        }

        /** Kills the zombie that walked into it, and is spent. */
        private void spring(LevelServer level, ZombieEntity zombie) {
            spent = true;
            dirty = true;
            level.emitEffect(PvzceParticles.MOWER_CLOUD_POWIE.toString(),
                    zombie.cellX(), zombie.cellY(), PvzceSounds.EFFECT_BONK);
            zombie.damage(RAKE_DAMAGE, ZombieEntity.damageType(PvzceIds.DAMAGE_MOWER), level);
        }

        private State state() {
            return new State(row, spent);
        }

        /** Ground-layer zombies of one row, as a copy: springing one removes it. */
        private static List<ZombieEntity> groundZombiesInRow(LevelServer level, int row) {
            List<ZombieEntity> found = new ArrayList<>();
            for (var entity : level.entities()) {
                if (entity instanceof ZombieEntity zombie && zombie.isAlive()
                        && zombie.gridY() == row && zombie.layer() == EntityLayers.GROUND) {
                    found.add(zombie);
                }
            }
            return found;
        }
    }

    /** The level's rake; one per level instance, since a mechanic is a shared registry entry. */
    public static Rig rig(LevelServer level, RakeData data) {
        return level.mechanicState(PvzceIds.MECHANIC_RAKE, Rig::new);
    }

    /**
     * What the client draws from.
     *
     * <p>{@code row} of -1 means "this level has no rake", which a level that wrote an empty row
     * set reports. A whole-state replacement rather than an incremental update: there are two
     * facts and they change together.
     */
    public record State(int row, boolean spent) {
        public static final PacketStruct.Codec<State> CODEC = PacketStruct.<State>builder()
                .field(State::row, PacketByteBuf::writeInt, PacketByteBuf::readInt)
                .field(State::spent, PacketByteBuf::writeBoolean, PacketByteBuf::readBoolean)
                .build(values -> new State((Integer) values.get(0), (Boolean) values.get(1)));

        public void encode(PacketByteBuf buf) {
            CODEC.encode(this, buf);
        }

        public static State decode(PacketByteBuf buf) {
            return CODEC.decode(buf);
        }
    }
}
