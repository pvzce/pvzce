package com.pvzce.common.level.mechanic;

import com.mojang.serialization.MapCodec;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.ResonanceData;
import com.pvzce.api.content.mechanic.FieldSpec;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.capability.plant.EchoRelayCapability;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PacketStruct;
import com.pvzce.common.network.packet.MechanicSyncS2C;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.server.level.LevelServer;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Echo Conservatory's travelling resonant row. Its clock, movement and packets have one owner. */
public final class ResonanceMechanic implements LevelMechanic<ResonanceData> {
    private static final class State {
        int elapsed;
    }

    public record Status(int row, int nextRow, int ticksLeft, int intervalTicks) {
        public static final PacketStruct.Codec<Status> CODEC = PacketStruct.<Status>builder()
                .field(Status::row, PacketByteBuf::writeVarInt, PacketByteBuf::readVarInt)
                .field(Status::nextRow, PacketByteBuf::writeVarInt, PacketByteBuf::readVarInt)
                .field(Status::ticksLeft, PacketByteBuf::writeVarInt, PacketByteBuf::readVarInt)
                .field(Status::intervalTicks, PacketByteBuf::writeVarInt, PacketByteBuf::readVarInt)
                .build(v -> new Status((Integer) v.get(0), (Integer) v.get(1),
                        (Integer) v.get(2), (Integer) v.get(3)));
    }

    @Override
    public MapCodec<ResonanceData> codec() {
        return ResonanceData.MAP_CODEC;
    }

    private static State state(LevelServer level) {
        return level.mechanicState(PvzceIds.MECHANIC_RESONANCE, State::new);
    }

    public static Status status(LevelServer level, ResonanceData data) {
        int elapsed = state(level).elapsed;
        int row = (data.openingRow() + elapsed / data.intervalTicks()) % level.height();
        return new Status(row, (row + 1) % level.height(),
                data.intervalTicks() - elapsed % data.intervalTicks(), data.intervalTicks());
    }

    @Override
    public void tick(LevelServer level, ResonanceData data) {
        State state = state(level);
        state.elapsed++;
        Status status = status(level, data);
        if (state.elapsed % data.pulseTicks() == 0) {
            Set<PlantEntity> visited = new HashSet<>();
            for (int x = 0; x < level.width(); x++) {
                for (PlantEntity plant : level.plantsAt(x, status.row())) {
                    if (visited.contains(plant) || plant.capability(EchoRelayCapability.class) == null
                            || !plant.team().id().equals(PvzceIds.PLANT_TEAM)) {
                        continue;
                    }
                    visited.addAll(EchoRelayCapability.choir(plant, level).keySet());
                    EchoRelayCapability.resonate(plant, level, data.damage(), data.pulseTicks());
                }
            }
        }
        if (state.elapsed % 6 == 0 || status.ticksLeft() == data.intervalTicks()) {
            level.send(MechanicSyncS2C.of(PvzceIds.MECHANIC_RESONANCE, Status.CODEC, status));
        }
    }

    @Override
    public float zombieSpeedMultiplier(LevelServer level, ResonanceData data, ZombieEntity zombie) {
        return zombie.team().id().equals(PvzceIds.ZOMBIE_TEAM) && zombie.canBeHitByGround()
                && zombie.gridY() == status(level, data).row() ? data.zombieSpeed() : 1F;
    }

    @Override
    public void sendState(LevelServer level, ResonanceData data, LevelServer.ServerBridge bridge) {
        bridge.send(MechanicSyncS2C.of(PvzceIds.MECHANIC_RESONANCE, Status.CODEC, status(level, data)));
    }

    @Override
    public void collectSave(LevelServer level, ResonanceData data, CompoundTag root) {
        root.putInt("ResonanceElapsed", state(level).elapsed);
    }

    @Override
    public void applySave(LevelServer level, ResonanceData data, CompoundTag root) {
        state(level).elapsed = Math.max(0, root.getInt("ResonanceElapsed"));
    }

    @Override
    public List<String> validate(LevelDef def, ResonanceData data) {
        return data.openingRow() >= def.height() ? List.of("Resonance opening_row is off the board")
                : data.pulseTicks() > data.intervalTicks()
                ? List.of("Resonance pulse_ticks exceeds interval_ticks") : List.of();
    }

    @Override
    public List<FieldSpec> editorFields() {
        return List.of(
                new FieldSpec.Number("interval_ticks", "pvzce.mechanic.resonance.field.interval_ticks", 60, 7200, true),
                new FieldSpec.Number("pulse_ticks", "pvzce.mechanic.resonance.field.pulse_ticks", 60, 7200, true),
                new FieldSpec.Number("opening_row", "pvzce.mechanic.resonance.field.opening_row", 0, 63, true),
                new FieldSpec.Number("zombie_speed", "pvzce.mechanic.resonance.field.zombie_speed", 1F, 3F, false),
                new FieldSpec.Number("damage", "pvzce.mechanic.resonance.field.damage", 1, 1000, true));
    }
}
