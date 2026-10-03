package com.pvzce.common.level.mechanic;

import com.mojang.serialization.MapCodec;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.PlantDef;
import com.pvzce.api.content.WeatherData;
import com.pvzce.api.content.mechanic.FieldSpec;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.capability.plant.CobCannonCapability;
import com.pvzce.common.capability.plant.ExplosiveCapability;
import com.pvzce.common.capability.plant.GoldMagnetCapability;
import com.pvzce.common.capability.plant.NocturnalCapability;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.common.network.packet.MechanicSyncS2C;
import com.pvzce.server.level.LevelServer;

import java.util.List;

/** Weather is independent of night: its live modifiers compose with care, buffs and rules. */
public final class WeatherMechanic implements LevelMechanic<WeatherData> {
    @Override public MapCodec<WeatherData> codec() { return WeatherData.MAP_CODEC; }

    @Override public List<String> validate(LevelDef def, WeatherData data) {
        return data.validate(def.waves().size());
    }

    @Override public List<FieldSpec> editorFields() {
        return List.of(new FieldSpec.ListField("phases", "pvzce.mechanic.weather.field.phases", List.of(
                new FieldSpec.Number("from_wave", "pvzce.mechanic.weather.field.from_wave", 1, 1000, true),
                new FieldSpec.Choice("weather", "pvzce.mechanic.weather.field.weather",
                        List.of("clear", "cloudy", "rain")))));
    }

    @Override public void onLevelCreated(LevelServer level, WeatherData data) {
        level.setMechanicState(PvzceIds.MECHANIC_WEATHER, new WeatherState(data.atWave(1), 0));
    }

    @Override public void tick(LevelServer level, WeatherData data) {
        WeatherState previous = stateOf(level);
        WeatherData.Kind weather = data.atWave(level.currentWave());
        if (previous != null && previous.weather() == weather) return;
        WeatherState next = new WeatherState(weather, level.tickCount());
        level.setMechanicState(PvzceIds.MECHANIC_WEATHER, next);
        if (previous != null && previous.weather() != next.weather() && level.plantPlayer() != null) {
            float ratio = next.ashCooldownMultiplier() / previous.ashCooldownMultiplier();
            for (var slot : level.plantPlayer().slots()) {
                if (slot.kind() == com.pvzce.common.core.Slot.Kind.PLANT && slot.cooldownLeft() > 0
                        && ashPlant(com.pvzce.common.core.BuiltInRegistries.PLANTS.get(slot.defId()))) {
                    int remaining = (int) Math.ceil(slot.cooldownLeft() * ratio);
                    slot.clearCooldown();
                    slot.startCooldown(remaining);
                }
            }
        }
        level.send(MechanicSyncS2C.of(PvzceIds.MECHANIC_WEATHER, WeatherState.CODEC, next));
    }

    public static WeatherState stateOf(LevelServer level) {
        return level.mechanicStateOrNull(PvzceIds.MECHANIC_WEATHER, WeatherState.class);
    }

    @Override public void onWaveChanged(LevelServer level, WeatherData data) {
        tick(level, data);
    }

    @Override public void sendState(LevelServer level, WeatherData data, LevelServer.ServerBridge bridge) {
        WeatherState state = stateOf(level);
        bridge.send(MechanicSyncS2C.of(PvzceIds.MECHANIC_WEATHER, WeatherState.CODEC,
                state == null ? new WeatherState(data.atWave(level.currentWave()), level.tickCount()) : state));
    }

    @Override public void collectSave(LevelServer level, WeatherData data, CompoundTag root) {
        WeatherState state = stateOf(level);
        if (state != null) root.putLong("WeatherTick", state.tick());
    }

    @Override public void applySave(LevelServer level, WeatherData data, CompoundTag root) {
        // The restored wave is the source of the weather, not a stale enum in a save file.
        level.setMechanicState(PvzceIds.MECHANIC_WEATHER,
                new WeatherState(data.atWave(level.currentWave()), Math.max(0L, root.getLong("WeatherTick"))));
    }

    public static boolean mushroom(PlantDef plant) {
        return plant != null && (plant.capability(NocturnalCapability.class).isPresent()
                || plant.capability(GoldMagnetCapability.class).isPresent());
    }

    public static boolean ashPlant(PlantDef plant) {
        return plant != null && (plant.capability(ExplosiveCapability.class)
                .filter(blast -> PvzceIds.DAMAGE_ASH.equals(blast.damageType())).isPresent()
                || plant.capability(CobCannonCapability.class).isPresent());
    }

    public static float actionRate(LevelServer level, PlantDef plant) {
        WeatherState state = stateOf(level);
        if (state == null || plant == null) return 1F;
        if (mushroom(plant)) return state.mushroomMultiplier();
        if (PvzceIds.id("sunflower").equals(plant.id()) || PvzceIds.id("twin_sunflower").equals(plant.id())) {
            return state.sunflowerMultiplier();
        }
        return 1F;
    }

}
