package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.mechanic.MechanicData;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** A wave-indexed forecast. Clear weather changes plants, never the day/night clock. */
public record WeatherData(List<Phase> phases) implements MechanicData {
    public enum Kind {
        CLEAR, CLOUDY, RAIN;

        public static final Codec<Kind> CODEC = Codec.STRING.comapFlatMap(value -> {
            try {
                return com.mojang.serialization.DataResult.success(valueOf(value.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                return com.mojang.serialization.DataResult.error(() -> "Unknown weather: " + value);
            }
        }, kind -> kind.name().toLowerCase(Locale.ROOT));

        public String key() { return name().toLowerCase(Locale.ROOT); }
    }

    public record Phase(int fromWave, Kind weather) {
        public static final Codec<Phase> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("from_wave").forGetter(Phase::fromWave),
                Kind.CODEC.fieldOf("weather").forGetter(Phase::weather)
        ).apply(i, Phase::new));
    }

    public static final MapCodec<WeatherData> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Phase.CODEC.listOf().fieldOf("phases").forGetter(WeatherData::phases)
    ).apply(i, WeatherData::new));

    public WeatherData { phases = List.copyOf(phases); }

    public Kind atWave(int wave) {
        Kind result = phases.isEmpty() ? Kind.CLOUDY : phases.getFirst().weather();
        for (Phase phase : phases) {
            if (phase.fromWave() > Math.max(1, wave)) break;
            result = phase.weather();
        }
        return result;
    }

    public Phase nextAtWave(int wave) {
        return phases.stream().filter(phase -> phase.fromWave() > Math.max(1, wave))
                .findFirst().orElse(null);
    }

    public List<String> validate(int waveCount) {
        List<String> errors = new ArrayList<>();
        if (phases.isEmpty() || phases.getFirst().fromWave() != 1) {
            errors.add("weather forecast must start at wave 1");
        }
        int previous = 0;
        for (Phase phase : phases) {
            if (phase.fromWave() <= previous || phase.fromWave() > waveCount) {
                errors.add("weather phases must use increasing, existing waves: " + phase.fromWave());
            }
            previous = phase.fromWave();
        }
        return errors;
    }
}
