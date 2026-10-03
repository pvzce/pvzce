package com.pvzce.common.level.mechanic;

import com.pvzce.api.content.WeatherData;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PacketStruct;

/** Weather and its starting simulation tick; published on transitions and in joining snapshots. */
public record WeatherState(WeatherData.Kind weather, long tick) {
    public float mushroomMultiplier() {
        return switch (weather) {
            case CLEAR -> com.pvzce.common.PvzceConstants.WEATHER_CLEAR_MUSHROOM_RATE;
            case CLOUDY -> 1F;
            case RAIN -> com.pvzce.common.PvzceConstants.WEATHER_RAIN_MUSHROOM_RATE;
        };
    }

    public float sunflowerMultiplier() {
        return switch (weather) {
            case CLEAR -> com.pvzce.common.PvzceConstants.WEATHER_CLEAR_SUNFLOWER_RATE;
            case CLOUDY -> com.pvzce.common.PvzceConstants.WEATHER_CLOUDY_SUNFLOWER_RATE;
            case RAIN -> com.pvzce.common.PvzceConstants.WEATHER_RAIN_SUNFLOWER_RATE;
        };
    }

    public float torchMultiplier() {
        return switch (weather) {
            case CLEAR -> 1F;
            case CLOUDY -> com.pvzce.common.PvzceConstants.WEATHER_CLOUDY_TORCH_DAMAGE;
            case RAIN -> 0F;
        };
    }

    public float ashMultiplier() {
        return switch (weather) {
            case CLEAR -> 1F;
            case CLOUDY -> com.pvzce.common.PvzceConstants.WEATHER_CLOUDY_ASH_DAMAGE;
            case RAIN -> com.pvzce.common.PvzceConstants.WEATHER_RAIN_ASH_DAMAGE;
        };
    }

    public float ashCooldownMultiplier() {
        return switch (weather) {
            case CLEAR -> 1F;
            case CLOUDY -> com.pvzce.common.PvzceConstants.WEATHER_CLOUDY_ASH_COOLDOWN;
            case RAIN -> com.pvzce.common.PvzceConstants.WEATHER_RAIN_ASH_COOLDOWN;
        };
    }

    public static final PacketStruct.Codec<WeatherState> CODEC = PacketStruct.<WeatherState>builder()
            .field(state -> state.weather().key(), PacketByteBuf::writeString, PacketByteBuf::readString)
            .field(WeatherState::tick, PacketByteBuf::writeLong, PacketByteBuf::readLong)
            .build(values -> new WeatherState(WeatherData.Kind.valueOf(
                    ((String) values.get(0)).toUpperCase(java.util.Locale.ROOT)), (Long) values.get(1)));
}
