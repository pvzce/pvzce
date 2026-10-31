package com.pvzce.common.level.mechanic;

import com.mojang.serialization.MapCodec;
import com.pvzce.api.content.LevelBelt;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.VersusData;
import com.pvzce.api.content.mechanic.MechanicData;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.capability.zombie.BungeeCapability;
import com.pvzce.common.capability.zombie.ZombossCapability;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PacketStruct;
import com.pvzce.common.network.packet.MechanicSyncS2C;
import com.pvzce.common.network.packet.ServerMessageS2C;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.server.level.ConveyorBelt;
import com.pvzce.server.level.LevelServer;
import com.pvzce.server.level.cardsource.BeltCardSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import static com.pvzce.common.PvzceConstants.*;

/**
 * Boss-specific victory and presentation; combat state belongs to the zombie capability.
 *
 * <p>The machine runs its own attacks unless a person plays the zombie side - a human versus match on
 * a boss map. Then it is <em>piloted</em> ({@link #piloted}): it only moves when the pilot asks
 * ({@link #command}), every move recharges on its own clock, and the head keeps the three clocks
 * {@link Pilot} reports. The plant side's belt is tuned here in both cases.
 */
public final class ZombossMechanic implements LevelMechanic<MechanicData.Empty> {
    private static final class State { int deathTicks; }

    /** The pilot's moves, by the name the command packet carries. */
    public enum PilotMove {
        LOWER, RAISE, FIRE, ICE, STOMP, RV, BUNGEE;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** The move a packet names, or {@code null} for a name that is not one. */
        public static PilotMove parse(String id) {
            for (PilotMove move : values()) {
                if (move.id().equals(id)) {
                    return move;
                }
            }
            return null;
        }

        /** True for the moves that are aimed at the lawn; the head's two act at once. */
        public boolean aimed() {
            return this != LOWER && this != RAISE;
        }

        /**
         * The cells this move covers when aimed at {@code (x, y)}, or {@code null} for a move that is
         * not aimed. The machine's target and the pilot's preview are both read from here.
         *
         * <p>A stomp takes the clicked row and the one below it on the flat roof; the camper is centred
         * on the clicked column; the thieves take the clicked cell and its neighbours in the row; a ball
         * rolls the whole row.
         */
        public PilotArea area(int x, int y, int width, int height) {
            return switch (this) {
                case STOMP -> new PilotArea(ZOMBOSS_STOMP_COLUMN, Math.clamp(y, 0, height - 2),
                        width - ZOMBOSS_STOMP_COLUMN, 2);
                case RV -> new PilotArea(Math.clamp(x - 1, 0, width - 3), Math.clamp(y, 0, height - 2), 3, 2);
                case BUNGEE -> new PilotArea(Math.clamp(x - 1, 0, width - ZOMBOSS_BUNGEE_COUNT),
                        Math.clamp(y, 0, height - 1), ZOMBOSS_BUNGEE_COUNT, 1);
                case FIRE, ICE -> new PilotArea(0, Math.clamp(y, 0, height - 1), width, 1);
                case LOWER, RAISE -> null;
            };
        }
    }

    /** A block of cells: its first column and row, and how many of each. */
    public record PilotArea(int column, int row, int columns, int rows) {
    }

    /**
     * What a pilot flies the machine with, as ticks left; {@link #NONE} while it runs its own attacks.
     *
     * <p>Sent to both sides: the plant side plans around the forced lowering as much as the pilot does.
     *
     * @param stomp  until the stomp is ready
     * @param rv     until the camper is ready
     * @param bungee until the bungee raid is ready
     * @param lower  until the pilot may lower the head
     * @param forced until the head is lowered whatever the pilot wants
     * @param hold   until a lowered head may rise again
     * @param spat   whether this lowering has already spat its ball
     */
    public record Pilot(boolean active, int stomp, int rv, int bungee, int lower, int forced, int hold,
                        boolean spat) {
        public static final Pilot NONE = new Pilot(false, 0, 0, 0, 0, 0, 0, false);
        public static final PacketStruct.Codec<Pilot> CODEC = PacketStruct.<Pilot>builder()
                .field(Pilot::active, PacketByteBuf::writeBoolean, PacketByteBuf::readBoolean)
                .field(Pilot::stomp, PacketByteBuf::writeVarInt, PacketByteBuf::readVarInt)
                .field(Pilot::rv, PacketByteBuf::writeVarInt, PacketByteBuf::readVarInt)
                .field(Pilot::bungee, PacketByteBuf::writeVarInt, PacketByteBuf::readVarInt)
                .field(Pilot::lower, PacketByteBuf::writeVarInt, PacketByteBuf::readVarInt)
                .field(Pilot::forced, PacketByteBuf::writeVarInt, PacketByteBuf::readVarInt)
                .field(Pilot::hold, PacketByteBuf::writeVarInt, PacketByteBuf::readVarInt)
                .field(Pilot::spat, PacketByteBuf::writeBoolean, PacketByteBuf::readBoolean)
                .build(v -> new Pilot((Boolean) v.get(0), (Integer) v.get(1), (Integer) v.get(2),
                        (Integer) v.get(3), (Integer) v.get(4), (Integer) v.get(5), (Integer) v.get(6),
                        (Boolean) v.get(7)));

        /** Ticks until a recharging move is ready again. */
        public int cooldown(PilotMove move) {
            return switch (move) {
                case STOMP -> stomp;
                case RV -> rv;
                case BUNGEE -> bungee;
                case LOWER -> lower;
                case RAISE -> hold;
                case FIRE, ICE -> 0;
            };
        }
    }

    public record BungeeRope(int entityId, int column, int row, float handX, float handY) {
        public static final PacketStruct.Codec<BungeeRope> CODEC = PacketStruct.<BungeeRope>builder()
                .field(BungeeRope::entityId, PacketByteBuf::writeVarInt, PacketByteBuf::readVarInt)
                .field(BungeeRope::column, PacketByteBuf::writeVarInt, PacketByteBuf::readVarInt)
                .field(BungeeRope::row, PacketByteBuf::writeVarInt, PacketByteBuf::readVarInt)
                .field(BungeeRope::handX, PacketByteBuf::writeFloat, PacketByteBuf::readFloat)
                .field(BungeeRope::handY, PacketByteBuf::writeFloat, PacketByteBuf::readFloat)
                .build(v -> new BungeeRope((Integer)v.get(0), (Integer)v.get(1), (Integer)v.get(2),
                        (Float)v.get(3), (Float)v.get(4)));
    }

    public record Status(int health, int maxHealth, String phase, int ticksLeft, int column, int row,
                         int ballType, float ballX, int ballRow, boolean frozen, int ballAge, int spitType,
                         List<BungeeRope> bungees, Pilot pilot) {
        public Status(int health, int maxHealth, String phase, int ticksLeft, int column, int row,
                      int ballType, float ballX, int ballRow, boolean frozen, int ballAge, int spitType) {
            this(health, maxHealth, phase, ticksLeft, column, row, ballType, ballX, ballRow, frozen,
                    ballAge, spitType, List.of(), Pilot.NONE);
        }

        /** The same snapshot with the pilot's clocks and the bungee ropes filled in. */
        public Status with(List<BungeeRope> ropes, Pilot clocks) {
            return new Status(health, maxHealth, phase, ticksLeft, column, row, ballType, ballX, ballRow,
                    frozen, ballAge, spitType, ropes, clocks);
        }

        /**
         * Why the pilot cannot make this move now, as a language key, or {@code null} when it can.
         *
         * <p>The one statement of the pilot's rules: the machine asks it before a move begins, and the
         * pilot's buttons ask it to grey themselves out, from the same synced numbers. Orders are taken
         * between actions only, a head that is moving finishes the motion, and a head whose deadline
         * has run out takes no attack because it is about to come down.
         */
        public String refusal(PilotMove move) {
            if (frozen) return "gui.pvzce.zomboss.refused.frozen";
            boolean idle = phase.equals("idle"), down = phase.equals("head_down");
            String notDown = phase.startsWith("head_") ? "gui.pvzce.zomboss.refused.busy"
                    : "gui.pvzce.zomboss.refused.head_up";
            return switch (move) {
                case LOWER -> !idle ? "gui.pvzce.zomboss.refused.busy"
                        : pilot.lower() > 0 ? "gui.pvzce.zomboss.refused.cooldown" : null;
                case RAISE -> !down ? notDown : pilot.hold() > 0 ? "gui.pvzce.zomboss.refused.hold" : null;
                case FIRE, ICE -> !down ? notDown
                        : pilot.spat() || ballType != 0 ? "gui.pvzce.zomboss.refused.spat" : null;
                case STOMP, RV, BUNGEE -> phase.startsWith("head_") ? "gui.pvzce.zomboss.refused.head_down"
                        : !idle ? "gui.pvzce.zomboss.refused.busy"
                        : pilot.forced() <= 0 ? "gui.pvzce.zomboss.refused.forced"
                        : pilot.cooldown(move) > 0 ? "gui.pvzce.zomboss.refused.cooldown" : null;
            };
        }
        @SuppressWarnings("unchecked")
        public static final PacketStruct.Codec<Status> CODEC = PacketStruct.<Status>builder()
                .field(Status::health, PacketByteBuf::writeVarInt, PacketByteBuf::readVarInt)
                .field(Status::maxHealth, PacketByteBuf::writeVarInt, PacketByteBuf::readVarInt)
                .field(Status::phase, PacketByteBuf::writeString, PacketByteBuf::readString)
                .field(Status::ticksLeft, PacketByteBuf::writeVarInt, PacketByteBuf::readVarInt)
                .field(Status::column, PacketByteBuf::writeVarInt, PacketByteBuf::readVarInt)
                .field(Status::row, PacketByteBuf::writeVarInt, PacketByteBuf::readVarInt)
                .field(Status::ballType, PacketByteBuf::writeVarInt, PacketByteBuf::readVarInt)
                .field(Status::ballX, PacketByteBuf::writeFloat, PacketByteBuf::readFloat)
                .field(Status::ballRow, PacketByteBuf::writeVarInt, PacketByteBuf::readVarInt)
                .field(Status::frozen, PacketByteBuf::writeBoolean, PacketByteBuf::readBoolean)
                .field(Status::ballAge, PacketByteBuf::writeVarInt, PacketByteBuf::readVarInt)
                .field(Status::spitType, PacketByteBuf::writeVarInt, PacketByteBuf::readVarInt)
                .field(Status::bungees, (buf, ropes) -> buf.writeList(ropes, (rope, out) -> BungeeRope.CODEC.encode(rope, out)),
                        buf -> buf.readList(BungeeRope.CODEC::decode))
                .nested(Status::pilot, Pilot.CODEC)
                .build(v -> new Status((Integer)v.get(0), (Integer)v.get(1), (String)v.get(2),
                        (Integer)v.get(3), (Integer)v.get(4), (Integer)v.get(5), (Integer)v.get(6),
                        (Float)v.get(7), (Integer)v.get(8), (Boolean)v.get(9), (Integer)v.get(10), (Integer)v.get(11),
                        (List<BungeeRope>)v.get(12), (Pilot)v.get(13)));
    }

    @Override public MapCodec<MechanicData.Empty> codec() { return MapCodec.unit(MechanicData.Empty.INSTANCE); }
    @Override public void onLevelCreated(LevelServer level, MechanicData.Empty data) {
        BeltCardSource source = level.beltOf(PvzceIds.PLANT_TEAM);
        ConveyorBelt belt = source.belt();
        belt.setWeight(card -> conveyorWeight(level, belt, card));
        belt.setInterval(() -> conveyorInterval(belt.cards().size()));
        belt.delayNext(ZOMBOSS_BELT_OPENING_TICKS);
        // receiveCard inserts at the front, hence the reverse of the original opening order.
        for (String card : List.of("ice_shroom", "cabbage_pult", "jalapeno", "cabbage_pult")) {
            source.receiveCard(level, packet -> {}, PvzceIds.id(card));
        }
    }

    public static int conveyorInterval(int held) {
        return held > 8 ? ZOMBOSS_BELT_CROWDED_TICKS : held > 6 ? ZOMBOSS_BELT_SLOW_TICKS
                : held > 4 ? ZOMBOSS_BELT_MEDIUM_TICKS : ZOMBOSS_BELT_FAST_TICKS;
    }

    public static int conveyorWeight(LevelServer level, ConveyorBelt belt, LevelBelt.BeltCard card) {
        int weight = card.weight();
        String plant = card.card().path();
        if (plant.endsWith("_pult")) {
            long empty = level.entities().stream().filter(e -> e instanceof PlantEntity p
                    && !p.isRemoved() && p.defId().equals(PvzceIds.id("flower_pot"))
                    && level.plantsAt(p.gridX(), p.gridY()).stream().noneMatch(top -> !LevelServer.isCarrier(top))).count();
            if (empty <= 2) weight /= 5;
            else if (empty <= 5) weight /= 3;
        } else if (plant.equals("flower_pot")) {
            long pots = level.entities().stream().filter(e -> e instanceof PlantEntity p
                    && !p.isRemoved() && p.defId().equals(card.card())).count()
                    + belt.cards().stream().filter(c -> c.cardId().equals(card.card())).count();
            weight = Math.round(weight + (1F - weight) * Math.min(pots, ZOMBOSS_POT_WEIGHT_LIMIT) / ZOMBOSS_POT_WEIGHT_LIMIT);
            ZombieEntity boss = boss(level);
            if (boss != null && boss.capability(ZombossCapability.class).status(boss).phase().equals("rv")) weight = ZOMBOSS_RV_POT_WEIGHT;
        }
        long held = belt.cards().stream().filter(c -> c.cardId().equals(card.card())).count();
        if (held >= 4) return 1;
        if (held >= 3) return 5;
        return card.card().equals(belt.lastDealt()) ? weight / 2 : weight;
    }

    @Override public boolean controlsVictory() { return true; }

    @Override public List<String> validate(LevelDef def, MechanicData.Empty data) {
        List<String> errors = new ArrayList<>();
        if (def.width() != 9 || def.height() != 5) errors.add("Zomboss requires a 9 × 5 roof");
        long bosses = def.initialEntities().stream().filter(e -> e.id().equals(PvzceIds.id("zombie_boss"))).count();
        if (bosses != 1 || !def.waves().isEmpty()) {
            errors.add("Zomboss requires exactly one initial zombie_boss and no waves");
        }
        // The plant side's belt is tuned from the board (see onLevelCreated), so it has to exist.
        boolean plantBelt = LevelMechanics.dealsItsOwnCards(def)
                || LevelMechanics.versusData(def).flatMap(VersusData::plantBelt).isPresent();
        if (!plantBelt) {
            errors.add("Zomboss deals the plant side from a belt: declare pvzce:conveyor or a versus plant_belt");
        }
        return errors;
    }

    @Override public List<Identifier> previewZombieIds(LevelDef def, MechanicData.Empty data) {
        var ids = new ArrayList<>(ZombossCapability.summonIds());
        ids.add(PvzceIds.id("basic_zombie")); ids.add(PvzceIds.id("bungee_zombie")); ids.add(PvzceIds.id("zombie_boss"));
        return List.copyOf(ids);
    }

    private static State state(LevelServer level) { return level.mechanicState(PvzceIds.MECHANIC_ZOMBOSS, State::new); }
    public static ZombieEntity boss(LevelServer level) {
        return level.entities().stream().filter(e -> e instanceof ZombieEntity z
                        && z.capability(ZombossCapability.class) != null)
                .map(e -> (ZombieEntity)e).findFirst().orElse(null);
    }

    /**
     * True when a person plays the zombie side, which makes the machine theirs to fly.
     *
     * <p>Asked of the level rather than declared by the map: the same boss runs its own attacks for a
     * plant player alone and waits for orders when the zombie side has someone at the controls.
     */
    public static boolean piloted(LevelServer level) {
        return level.humanVersus() || PvzceIds.ZOMBIE_TEAM.equals(level.humanTeamId());
    }

    /**
     * One move from the pilot. The seat being served must be on the zombie side; whether the move can
     * begin now is the machine's answer, and a refusal goes back to that seat as a language key.
     */
    public static boolean command(LevelServer level, String action, int x, int y) {
        ZombieEntity boss = boss(level);
        PilotMove move = PilotMove.parse(action);
        if (boss == null || !boss.isAlive() || move == null) {
            return false;
        }
        if (!PvzceIds.ZOMBIE_TEAM.equals(level.humanTeamId())) {
            level.send(new ServerMessageS2C("gui.pvzce.zomboss.refused.side"));
            return false;
        }
        String refusal = boss.capability(ZombossCapability.class).command(boss, level, move, x, y);
        if (refusal != null) {
            level.send(new ServerMessageS2C(refusal));
        }
        return refusal == null;
    }

    private static Status snapshot(LevelServer level, ZombieEntity boss) {
        ZombossCapability capability = boss.capability(ZombossCapability.class);
        List<BungeeRope> ropes = level.entities().stream().filter(e -> e instanceof ZombieEntity z && z.isAlive())
                .map(e -> (ZombieEntity)e).map(z -> {
                    var cap = z.capability(BungeeCapability.class);
                    return cap == null ? null : cap.bossRope(z);
                }).filter(Objects::nonNull).toList();
        return capability.status(boss).with(ropes, piloted(level) ? capability.pilot() : Pilot.NONE);
    }

    @Override public void tick(LevelServer level, MechanicData.Empty data) {
        ZombieEntity boss = boss(level);
        State state = state(level);
        if (state.deathTicks > 0 || boss != null && !boss.isAlive()) {
            if (state.deathTicks++ == 0) level.sweepLawn();
            if (state.deathTicks >= ZOMBOSS_DEATH_TICKS) level.plantGoalReached();
        }
        if (boss != null && level.tickCount() % ZOMBOSS_SYNC_TICKS == 0) {
            level.send(MechanicSyncS2C.of(PvzceIds.MECHANIC_ZOMBOSS, Status.CODEC,
                    snapshot(level, boss)));
        }
    }

    @Override public void sendState(LevelServer level, MechanicData.Empty data, LevelServer.ServerBridge bridge) {
        ZombieEntity boss = boss(level);
        if (boss != null) bridge.send(MechanicSyncS2C.of(PvzceIds.MECHANIC_ZOMBOSS, Status.CODEC,
                snapshot(level, boss)));
    }

    @Override public void collectSave(LevelServer level, MechanicData.Empty data, CompoundTag root) {
        root.putInt("ZombossDeathTicks", state(level).deathTicks);
    }
    @Override public void applySave(LevelServer level, MechanicData.Empty data, CompoundTag root) {
        state(level).deathTicks = root.getInt("ZombossDeathTicks");
    }
}
