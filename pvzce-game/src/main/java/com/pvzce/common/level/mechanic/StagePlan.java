package com.pvzce.common.level.mechanic;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.mechanic.MechanicData;
import java.util.List;

/** Finite stages share one lawn and expose a choice between cleared wave groups. */
public record StagePlan(List<Phase> phases) implements MechanicData {
    public static final MapCodec<StagePlan> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Phase.CODEC.listOf().fieldOf("phases").forGetter(StagePlan::phases)
    ).apply(i, StagePlan::new));

    public StagePlan {
        phases = List.copyOf(phases);
    }

    public record Phase(String name, int fromWave, int supplySun, List<LevelDef.MusicCue> music, String atmosphere) {
        public static final Codec<Phase> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("name").forGetter(Phase::name),
                Codec.INT.fieldOf("from_wave").forGetter(Phase::fromWave),
                Codec.INT.fieldOf("supply_sun").forGetter(Phase::supplySun),
                LevelDef.MusicCue.CODEC.listOf().fieldOf("music").forGetter(Phase::music),
                Codec.STRING.optionalFieldOf("atmosphere", "day").forGetter(Phase::atmosphere)
        ).apply(i, Phase::new));

        public Phase(String name, int fromWave, int supplySun, List<LevelDef.MusicCue> music) {
            this(name, fromWave, supplySun, music, "day");
        }

        public Phase {
            music = List.copyOf(music);
        }
    }
}
