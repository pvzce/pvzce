package com.pvzce.common.level.mechanic;

import com.pvzce.common.level.SceneBoard;
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
 * it; {@code LevelServer} adds a {@link RakeData#RANDOM} block when the player owns the rake and the
 * level did not declare one. So the level file's block means "this level decides", an absent block
 * means "the player's rake, if they have one", and an explicit empty row set means "not here" - the
 * same three-way shape {@code MowerData} uses.
 *
 * <p><b>Where it stands.</b> In the second column from the right of the lawn, which is where the
 * original's rake is laid down: far enough out that the zombie which trips it dies before it has
 * crossed the board, and late enough that the player sees the lane they will have to defend. The
 * rake is a fixture, not an occupant - a plant may be planted on that cell, and the rake is drawn
 * under it like the terrain it lies on.
 *
 * <p><b>What it is worth.</b> 1800 damage, the original's number: enough for every ordinary
 * zombie, armour included, and not enough for a Gargantuar. The damage type is the mower's, for the
 * two properties the original's rake also has - it ignores armour (a buckethead dies as fast as a
 * basic zombie) and it takes the zombie apart.
 *
 * <p><b>The lane it is on is the lane the first zombie comes down.</b> The original guarantees it,
 * so that a rake which could not be reached would not be a purchase that silently does nothing;
 * {@link LevelServer} answers {@code forcedOpeningLane} from this rig and the wave director asks it
 * for the first spawn of the run. A lane with no rake (or a spent one) answers -1 and the wave
 * director deals as it always did.
 *
 * <p><b>What it does not touch.</b> Fliers and diggers, for the mower's reason: the predicate is
 * the zombie's own layer. A balloon zombie flies over the rake exactly as it flies over a mower.
 */
public final class RakeMechanic implements LevelMechanic<RakeData> {
    /**
     * Which column the rake lies in, counted from the right.
     *
     * <p>Two, so a nine-column lawn puts it in column 7. This is the only place in the codebase
     * that knows where a rake stands: the client draws the {@code x} the server sends it, and the
     * trigger is that same {@code x}.
     */
    public static final int COLUMNS_FROM_RIGHT = 2;
    /**
     * What one rake is worth.
     *
     * <p>The original's 1800: every ordinary zombie dies, armour and all (a buckethead is 1100
     * points of bucket over 200 points of zombie), and a Gargantuar's 3000 is out of reach. That
     * boundary is the whole reason the number is a number rather than "kill it": a rake is a head
     * start, not a mower.
     */
    public static final int RAKE_DAMAGE = 1800;
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
            // Reaching the rake's own x rather than a grid line: the rake lies in the middle of a
            // cell, and the zombie's position is continuous, so this fires on the tick its feet
            // get there - the same "asked of the point, not of the cell" rule the hammer follows.
            if (zombie.cellX() <= rig.x) {
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
        // The column is derived from the board rather than saved, so a level whose width changed
        // under a save gets a rake in the right place rather than one off the edge.
        rig.x = columnX(level);
        rig.dirty = true;
    }

    /** Where a rake lies on this board: the centre of {@link #COLUMNS_FROM_RIGHT} from the right. */
    public static float columnX(LevelServer level) {
        int column = Math.max(0, level.width() - COLUMNS_FROM_RIGHT);
        return column + 0.5F;
    }

    /** One level's rake. */
    public static final class Rig {
        private int row = -1;
        private float x;
        private boolean spent;
        private boolean dirty;

        /**
         * Lays the rake down, once, on a lane the level allows.
         *
         * <p>The lane is picked from the level's dice rather than from a fresh one, so a run
         * watched twice from the same seed has its rake in the same place. Water lanes are not
         * candidates whatever the level wrote: a rake lies on grass, and a rake the first zombie
         * could not walk to would be worse than no rake at all.
         */
        private void place(LevelServer level, RakeData data) {
            List<Integer> lanes = new ArrayList<>();
            for (int lane : data.rowsFor(level.height())) {
                if (!level.rowIsWater(lane)) {
                    lanes.add(lane);
                }
            }
            if (lanes.isEmpty()) {
                // "Nowhere" is announced rather than left unsaid: the client has an overlay either
                // way, and a level that deliberately has no rake should say so rather than relying
                // on the absence of a packet a late-joining client never saw. The row stays -1,
                // which is what "no rake here" already means everywhere else.
                row = -1;
                dirty = true;
                return;
            }
            row = lanes.get(level.random().nextInt(lanes.size()));
            x = columnX(level);
            spent = false;
            dirty = true;
        }

        /** True while the rake is still lying there. */
        public boolean armed() {
            return row >= 0 && !spent;
        }

        /** The lane it is on, or -1 when the level has none or it has sprung. */
        public int row() {
            return row;
        }

        /** The lane the next zombie must take, or -1 when the rake is not waiting for one. */
        public int armedLane() {
            return armed() ? row : -1;
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
            return new State(x, row, spent);
        }

        /** Ground-layer zombies of one row, as a copy: springing one removes it. */
        private static List<ZombieEntity> groundZombiesInRow(LevelServer level, int row) {
            List<ZombieEntity> found = new ArrayList<>();
            for (var entity : level.entities()) {
                if (entity instanceof ZombieEntity zombie && zombie.isAlive()
                        && zombie.gridY() == row && zombie.layer() == EntityLayers.GROUND
                        && zombie.surfaceId().equals(SceneBoard.DEFAULT_SURFACE)) {
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
     * The rake as the client sees it.
     *
     * <p>{@code row} of -1 means "this level has no rake", which a level that wrote an empty row
     * set reports. {@code x} travels rather than being derived on the client, so "where a rake
     * stands" is one fact in one place - the server's {@link #columnX}. A whole-state replacement
     * rather than an incremental update: there are three facts and they change together.
     */
    public record State(float x, int row, boolean spent) {
        public static final PacketStruct.Codec<State> CODEC = PacketStruct.<State>builder()
                .field(State::x, PacketByteBuf::writeFloat, PacketByteBuf::readFloat)
                .field(State::row, PacketByteBuf::writeInt, PacketByteBuf::readInt)
                .field(State::spent, PacketByteBuf::writeBoolean, PacketByteBuf::readBoolean)
                .build(values -> new State((Float) values.get(0), (Integer) values.get(1),
                        (Boolean) values.get(2)));

        public void encode(PacketByteBuf buf) {
            CODEC.encode(this, buf);
        }

        public static State decode(PacketByteBuf buf) {
            return CODEC.decode(buf);
        }
    }
}
