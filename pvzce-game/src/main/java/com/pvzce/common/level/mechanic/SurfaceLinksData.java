package com.pvzce.common.level.mechanic;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.mechanic.MechanicData;
import com.pvzce.common.level.SceneBoard;
import java.util.List;

/** Authored, directed connections crossed by walkers at a cell centre. */
public record SurfaceLinksData(List<Connection> connections) implements MechanicData {
    public static final MapCodec<SurfaceLinksData> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Connection.CODEC.listOf().fieldOf("connections").forGetter(SurfaceLinksData::connections)
    ).apply(i, SurfaceLinksData::new));

    public SurfaceLinksData {
        connections = List.copyOf(connections);
    }

    public record Connection(String from, String to, int x, int y, int direction) {
        public static final Codec<Connection> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.optionalFieldOf("from", SceneBoard.DEFAULT_SURFACE).forGetter(Connection::from),
                Codec.STRING.fieldOf("to").forGetter(Connection::to),
                Codec.INT.fieldOf("x").forGetter(Connection::x),
                Codec.INT.fieldOf("y").forGetter(Connection::y),
                Codec.INT.fieldOf("direction").forGetter(Connection::direction)
        ).apply(i, Connection::new));
    }
}
