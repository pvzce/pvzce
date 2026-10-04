package com.pvzce.common.level.mechanic;

import com.pvzce.api.content.WeatherData;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PacketStruct;

import java.util.Locale;

/**
 * Weather, its starting simulation tick, and the forecast being shouted about it.
 *
 * <p>Published on transitions and in joining snapshots; the warning rides along because it is the
 * one part of the weather that is a <em>moment</em> rather than a state - "the dark clouds are
 * coming" is said once, ten seconds before the wave that brings them, and a client that joins
 * afterwards must not hear it again.
 *
 * @param warning  which forecast is on screen right now, or {@code null}
 * @param sequence how many forecasts this run has given out; the client watches it for "this is a
 *                 new one" rather than trying to tell two identical messages apart
 */
public record WeatherState(WeatherData.Kind weather, long tick, Warning warning, int sequence) {
    /**
     * The forecasts a weather phase change can be announced with.
     *
     * <p>Every phrase is about what the player is about to lose or get back, rather than about the
     * label the HUD will show: the roof is dark, so "the moonlight is about to be covered" is the
     * consequence a player understands, where "it is turning cloudy" is a word.
     */
    public enum Warning {
        /** Clear turning cloudy or rainy: the moon is about to go behind something. */
        MOON_BLOCKED,
        /** Anything turning cloudy or rainy without the moon being out: the clouds are arriving. */
        CLOUDS_COMING,
        /** Cloudy turning clear: they are leaving. */
        CLOUDS_CLEARING,
        /** Rain turning clear: the moon comes back out. */
        MOON_RETURNING;

        public static final com.mojang.serialization.Codec<Warning> CODEC =
                com.mojang.serialization.Codec.STRING.comapFlatMap(value -> {
                    try {
                        return com.mojang.serialization.DataResult.success(
                                valueOf(value.toUpperCase(Locale.ROOT)));
                    } catch (IllegalArgumentException e) {
                        return com.mojang.serialization.DataResult.error(
                                () -> "Unknown weather warning: " + value);
                    }
                }, warning -> warning.name().toLowerCase(Locale.ROOT));

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** The same state with no forecast on screen; what a wave boundary clears it to. */
    public WeatherState withoutWarning() {
        return warning == null ? this : new WeatherState(weather, tick, null, sequence);
    }

    public static WeatherState of(WeatherData.Kind weather, long tick) {
        return new WeatherState(weather, tick, null, 0);
    }

    /**
     * Which forecast a phase change is announced by, or {@code null} when there is nothing to say.
     *
     * <p>The one place the four phrases are decided, so the server and the tests cannot disagree
     * about which change says what. A change between two kinds the level never has is not a
     * change: the caller only asks about the kinds its own forecast names.
     */
    public static Warning warningFor(WeatherData.Kind from, WeatherData.Kind to) {
        if (from == to) {
            return null;
        }
        if (to == WeatherData.Kind.CLEAR) {
            return from == WeatherData.Kind.RAIN ? Warning.MOON_RETURNING : Warning.CLOUDS_CLEARING;
        }
        if (from == WeatherData.Kind.CLEAR) {
            return Warning.MOON_BLOCKED;
        }
        return Warning.CLOUDS_COMING;
    }

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

    public float coldDurationMultiplier() {
        return weather == WeatherData.Kind.RAIN ? com.pvzce.common.PvzceConstants.WEATHER_RAIN_COLD_DURATION : 1F;
    }

    public float icySplashMultiplier() {
        return weather == WeatherData.Kind.RAIN ? com.pvzce.common.PvzceConstants.WEATHER_RAIN_ICY_SPLASH_RANGE : 1F;
    }

    public static final PacketStruct.Codec<WeatherState> CODEC = PacketStruct.<WeatherState>builder()
            .field(state -> state.weather().key(), PacketByteBuf::writeString, PacketByteBuf::readString)
            .field(WeatherState::tick, PacketByteBuf::writeLong, PacketByteBuf::readLong)
            .field(state -> state.warning() == null ? "" : state.warning().key(),
                    PacketByteBuf::writeString, PacketByteBuf::readString)
            .field(WeatherState::sequence, PacketByteBuf::writeVarInt, PacketByteBuf::readVarInt)
            .build(values -> new WeatherState(WeatherData.Kind.valueOf(
                    ((String) values.get(0)).toUpperCase(Locale.ROOT)), (Long) values.get(1),
                    ((String) values.get(2)).isEmpty() ? null
                            : Warning.valueOf(((String) values.get(2)).toUpperCase(Locale.ROOT)),
                    (Integer) values.get(3)));
}
