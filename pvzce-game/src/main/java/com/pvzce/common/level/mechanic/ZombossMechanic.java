package com.pvzce.common.level.mechanic;

import com.mojang.serialization.MapCodec;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.mechanic.MechanicData;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.capability.zombie.ZombossCapability;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PacketStruct;
import com.pvzce.common.network.packet.MechanicSyncS2C;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.server.level.LevelServer;

import java.util.List;

import static com.pvzce.common.PvzceConstants.*;

/** Boss-specific victory and presentation; combat state belongs to the zombie capability. */
public final class ZombossMechanic implements LevelMechanic<MechanicData.Empty> {
    private static final class State { int deathTicks; }

    public record Status(int health, int maxHealth, String phase, int ticksLeft, int column, int row,
                         int ballType, float ballX, int ballRow, boolean frozen) {
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
                .build(v -> new Status((Integer)v.get(0), (Integer)v.get(1), (String)v.get(2),
                        (Integer)v.get(3), (Integer)v.get(4), (Integer)v.get(5), (Integer)v.get(6),
                        (Float)v.get(7), (Integer)v.get(8), (Boolean)v.get(9)));
    }

    @Override public MapCodec<MechanicData.Empty> codec() { return MapCodec.unit(MechanicData.Empty.INSTANCE); }
    @Override public void onLevelCreated(LevelServer level, MechanicData.Empty data) {
        var source = (com.pvzce.server.level.cardsource.BeltCardSource) level.cardSource();
        source.belt().setWeight(card -> conveyorWeight(level, source.belt(), card));
        // receiveCard inserts at the front, hence the reverse of the original opening order.
        for (String card : List.of("ice_shroom", "cabbage_pult", "jalapeno", "cabbage_pult")) {
            level.cardSource().receiveCard(level, packet -> {}, PvzceIds.id(card));
        }
    }

    private static int conveyorWeight(LevelServer level, com.pvzce.server.level.ConveyorBelt belt,
                                      com.pvzce.api.content.LevelBelt.BeltCard card) {
        int weight = card.weight();
        String plant = card.card().path();
        if (plant.endsWith("_pult")) {
            long empty = level.entities().stream().filter(e -> e instanceof com.pvzce.server.entity.PlantEntity p
                    && !p.isRemoved() && p.defId().equals(PvzceIds.id("flower_pot"))
                    && level.plantsAt(p.gridX(), p.gridY()).stream().noneMatch(top -> !LevelServer.isCarrier(top))).count();
            if (empty <= 2) weight /= 5;
            else if (empty <= 5) weight /= 3;
        } else if (plant.equals("flower_pot")) {
            ZombieEntity boss = boss(level);
            if (boss != null && boss.capability(ZombossCapability.class).status(boss).phase().equals("rv")) weight = 500;
        }
        long held = belt.cards().stream().filter(c -> c.cardId().equals(card.card())).count();
        if (held >= 4) return 1;
        if (held >= 3) return 5;
        return card.card().equals(belt.lastDealt()) ? weight / 2 : weight;
    }

    @Override public boolean controlsVictory() { return true; }

    @Override public List<String> validate(LevelDef def, MechanicData.Empty data) {
        if (def.width() != 9 || def.height() != 5) return List.of("Zomboss requires a 9 × 5 roof");
        long bosses = def.initialEntities().stream().filter(e -> e.id().equals(PvzceIds.id("zombie_boss"))).count();
        return bosses == 1 && def.waves().isEmpty() ? List.of()
                : List.of("Zomboss requires exactly one initial zombie_boss and no waves");
    }

    @Override public List<Identifier> previewZombieIds(LevelDef def, MechanicData.Empty data) {
        var ids = new java.util.ArrayList<>(ZombossCapability.summonIds());
        ids.add(PvzceIds.id("basic_zombie")); ids.add(PvzceIds.id("bungee_zombie")); ids.add(PvzceIds.id("zombie_boss"));
        return List.copyOf(ids);
    }

    private static State state(LevelServer level) { return level.mechanicState(PvzceIds.MECHANIC_ZOMBOSS, State::new); }
    public static ZombieEntity boss(LevelServer level) {
        return level.entities().stream().filter(e -> e instanceof ZombieEntity z
                        && z.capability(ZombossCapability.class) != null)
                .map(e -> (ZombieEntity)e).findFirst().orElse(null);
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
                    boss.capability(ZombossCapability.class).status(boss)));
        }
    }

    @Override public void sendState(LevelServer level, MechanicData.Empty data, LevelServer.ServerBridge bridge) {
        ZombieEntity boss = boss(level);
        if (boss != null) bridge.send(MechanicSyncS2C.of(PvzceIds.MECHANIC_ZOMBOSS, Status.CODEC,
                boss.capability(ZombossCapability.class).status(boss)));
    }

    @Override public void collectSave(LevelServer level, MechanicData.Empty data, CompoundTag root) {
        root.putInt("ZombossDeathTicks", state(level).deathTicks);
    }
    @Override public void applySave(LevelServer level, MechanicData.Empty data, CompoundTag root) {
        state(level).deathTicks = root.getInt("ZombossDeathTicks");
    }
}
