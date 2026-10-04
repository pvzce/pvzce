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
        level.setMechanicState(PvzceIds.MECHANIC_WEATHER, WeatherState.of(data.atWave(1), 0));
    }

    @Override public void tick(LevelServer level, WeatherData data) {
        WeatherState previous = stateOf(level);
        WeatherData.Kind weather = data.atWave(level.currentWave());
        // The countdown to the change is computed every tick, not only on the tick the phase
        // changes: the forecast is due ten seconds *before* the wave that brings it, and nothing
        // happens on that tick except this clock reaching the threshold.
        WeatherState.Warning pending = pendingWarning(level, data, weather, previous);
        int sequence = previous == null ? 0 : previous.sequence();
        if (pending != null && (previous == null || previous.warning() != pending)) {
            sequence++;
        }
        WeatherState next = new WeatherState(weather, level.tickCount(), pending, sequence);
        boolean changed = previous == null || previous.weather() != weather
                || previous.warning() != pending || previous.sequence() != sequence;
        if (!changed) {
            return;
        }
        level.setMechanicState(PvzceIds.MECHANIC_WEATHER, next);
        if (previous != null && previous.weather() != weather && level.plantPlayer() != null) {
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

    /**
     * The forecast that should be on screen now, or {@code null}.
     *
     * <p>Due {@link com.pvzce.common.PvzceConstants#WEATHER_WARNING_TICKS} before the wave that
     * changes the sky, and taken down the moment the change happens - the wave boundary is the
     * thing being announced, so "the clouds are coming" and "the clouds are here" cannot both be
     * on screen.
     *
     * <p>Measured from the next phase rather than from a timer of its own, so the warning cannot
     * drift away from the thing it is warning about: a clear bonus that shortens the wait shortens
     * the warning with it, and a countdown frozen by a preparation stage freezes this too.
     */
    private static WeatherState.Warning pendingWarning(LevelServer level, WeatherData data,
                                                       WeatherData.Kind current, WeatherState previous) {
        WeatherData.Phase next = data.nextAtWave(level.currentWave());
        if (next == null) {
            return null;
        }
        WeatherState.Warning warning = WeatherState.warningFor(current, next.weather());
        if (warning == null) {
            return null;
        }
        // The index and the wave number are the same thing here: the sky changes on the tick the
        // wave written as `from_wave` *arrives*, and the director's index is how many have. Asking
        // for `fromWave - 1` would be asking about the wave before the one that changes it.
        int remaining = level.countdownToWave(next.fromWave());
        // A countdown that has not started yet answers with the whole gap, which is longer than the
        // lead: the forecast waits for the clock it is counting down.
        return remaining >= 0 && remaining <= com.pvzce.common.PvzceConstants.WEATHER_WARNING_TICKS
                ? warning : null;
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
                state == null ? WeatherState.of(data.atWave(level.currentWave()), level.tickCount()) : state));
    }

    @Override public void collectSave(LevelServer level, WeatherData data, CompoundTag root) {
        WeatherState state = stateOf(level);
        if (state != null) root.putLong("WeatherTick", state.tick());
    }

    @Override public void applySave(LevelServer level, WeatherData data, CompoundTag root) {
        // The restored wave is the source of the weather, not a stale enum in a save file. The
        // forecast is not saved either: a resumed run re-earns it from the countdown it is in, and
        // a banner restored mid-sentence would be a warning about a wave the player already met.
        level.setMechanicState(PvzceIds.MECHANIC_WEATHER,
                WeatherState.of(data.atWave(level.currentWave()),
                        Math.max(0L, root.getLong("WeatherTick"))));
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
