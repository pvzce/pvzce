package com.pvzce.common.level.mechanic;

import com.mojang.serialization.MapCodec;
import com.pvzce.api.content.FogData;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.mechanic.FieldSpec;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PacketStruct;
import com.pvzce.common.network.packet.MechanicSyncS2C;
import com.pvzce.server.level.LevelServer;

import java.util.ArrayList;
import java.util.List;

/**
 * The fog over part of the board (world 4, and the mutation that brings it to a lawn).
 *
 * <p>Almost all of this feature is on the client: what is dark, what is hidden, and how the
 * boundary is drawn are questions about the picture. What lives here is the one thing only the
 * server can answer - <b>how much fog this board has right now</b>, after the level's own block and
 * the run's active buffs have both had their say - and the one job of telling the client when that
 * answer changes.
 *
 * <p>It streams on {@link MechanicSyncS2C} like every other mechanic, as a whole-state replacement:
 * the state is three floats, and "the boundary moved" is not something an incremental update can
 * express.
 *
 * <p><b>Nothing here touches a rule, a hit test or a spawn.</b> The fog is a fact about what the
 * player can see; a plant behind it shoots exactly as far and a zombie behind it walks exactly as
 * fast. That is the reading the player asked for ("视觉遮挡"), and it is also what keeps the whole
 * feature inside the render layer - there is no simulation state to keep in step.
 *
 * <h2>What moves the boundary</h2>
 *
 * <p>A level's block is the baseline. On top of it:
 *
 * <ul>
 *   <li>the <b>fog retreat</b> buff pushes it right (see {@code LevelServer.fogData});</li>
 *   <li>a mutation that rolls the fog in installs the mechanic on a level that never declared it,
 *       through {@code LevelServer.setFogOverride};</li>
 *   <li>a lamp that lights a circle of it is the plantern's own capability, which asks this
 *       mechanic to publish a smaller span for as long as it stands.</li>
 * </ul>
 *
 * <p>All three end up in one place - {@code LevelServer.fogData()} - so the client draws one
 * picture and does not have to know that any of them exist.
 */
public final class FogMechanic implements LevelMechanic<FogData> {
    /** How often the published picture is re-checked. Cheap; it only sends when it changed. */
    private static final int CHECK_INTERVAL_TICKS = 10;

    @Override
    public MapCodec<FogData> codec() {
        return FogData.MAP_CODEC;
    }

    @Override
    public List<String> validate(LevelDef def, FogData data) {
        return data.validate(def.width());
    }

    @Override
    public List<FieldSpec> editorFields() {
        return List.of(
                new FieldSpec.Number("start_column", "pvzce.mechanic.fog.field.start_column",
                        0F, 64F, false),
                new FieldSpec.Number("end_column", "pvzce.mechanic.fog.field.end_column",
                        0F, 64F, false),
                new FieldSpec.Number("max_alpha", "pvzce.mechanic.fog.field.max_alpha",
                        0F, 1F, false));
    }

    @Override
    public void onLevelCreated(LevelServer level, FogData data) {
        level.setMechanicState(PvzceIds.MECHANIC_FOG, Wire.of(data, List.of()));
    }

    @Override
    public void tick(LevelServer level, FogData data) {
        if (level.tickCount() % CHECK_INTERVAL_TICKS != 0) {
            return;
        }
        // Asked of the level rather than read from `data`: the buff that shortens the fog is not
        // this mechanic's to know about, a mutation may have overridden the span outright, and the
        // lamps standing on the lawn are the plants' business rather than the level file's.
        Wire next = Wire.of(level.fogData(), level.fogReveals());
        Wire current = level.mechanicState(PvzceIds.MECHANIC_FOG, () -> next);
        if (!next.equals(current)) {
            level.setMechanicState(PvzceIds.MECHANIC_FOG, next);
            level.send(MechanicSyncS2C.of(PvzceIds.MECHANIC_FOG, Wire.CODEC, next));
        }
    }

    /**
     * How dark the fog is at a point, with every lamp folded in.
     *
     * <p>The one definition of "how dark is it here", shared by the drawing and by the hiding test,
     * and living on the server side of the mechanic because both sides can read it: the client
     * already imports this class for the wire format, and a second implementation on the client is
     * how a zombie ends up hidden at a place the player can see.
     *
     * <p>A lamp <em>subtracts</em> rather than replaces: a circle of "this much clearer" applied to
     * whatever the level's fog already was. That is what lets a mutation that darkens the board and
     * a lamp that lightens it compose without either knowing about the other.
     */
    /** The lamps a level is standing, in the form the fold and the wire both use. */
    public static List<Reveal> lampsOf(LevelServer level) {
        List<Reveal> reveals = new ArrayList<>();
        for (LevelServer.FogReveal lamp : level.fogReveals()) {
            reveals.add(new Reveal(lamp.entityId(), lamp.x(), lamp.y(), lamp.radius(),
                    lamp.strength()));
        }
        return reveals;
    }

    public static float alphaAt(FogData fog, List<Reveal> reveals, float x, float y) {
        float alpha = fog.alphaAt(x);
        if (alpha <= 0F || reveals == null || reveals.isEmpty()) {
            return alpha;
        }
        for (Reveal reveal : reveals) {
            if (reveal.radius() <= 0F) {
                continue;
            }
            float dx = x - reveal.x();
            float dy = y - reveal.y();
            float distance = (float) Math.sqrt(dx * dx + dy * dy);
            if (distance >= reveal.radius()) {
                continue;
            }
            // Falls off to nothing at the rim, so a lamp has an edge rather than a disc: a hard
            // cut would read as a hole in the fog rather than as light in it.
            float t = 1F - distance / reveal.radius();
            alpha *= 1F - reveal.strength() * t * t;
        }
        return Math.max(0F, Math.min(1F, alpha));
    }

    /**
     * One lamp, as the client needs it to carve its hole.
     *
     * <p>It travels even though the plant it belongs to already did, and the reason is the one
     * thing the client cannot work out for itself: <em>which</em> plants have a lamp. The radius
     * and the strength live in the plant's definition, which the client has - but "does this plant
     * reveal fog" is a capability, and capabilities are not sent. Twenty-four bytes per lamp is
     * cheaper than shipping every plant's capability list.
     */
    public record Reveal(int entityId, float x, float y, float radius, float strength) {
        public static final PacketStruct.Codec<Reveal> CODEC = PacketStruct.<Reveal>builder()
                .field(Reveal::entityId, PacketByteBuf::writeInt, PacketByteBuf::readInt)
                .field(Reveal::x, PacketByteBuf::writeFloat, PacketByteBuf::readFloat)
                .field(Reveal::y, PacketByteBuf::writeFloat, PacketByteBuf::readFloat)
                .field(Reveal::radius, PacketByteBuf::writeFloat, PacketByteBuf::readFloat)
                .field(Reveal::strength, PacketByteBuf::writeFloat, PacketByteBuf::readFloat)
                .build(values -> new Reveal((Integer) values.get(0), (Float) values.get(1),
                        (Float) values.get(2), (Float) values.get(3), (Float) values.get(4)));

        public void encode(PacketByteBuf buf) {
            CODEC.encode(this, buf);
        }

        public static Reveal decode(PacketByteBuf buf) {
            return CODEC.decode(buf);
        }
    }

    /**
     * What travels, and what the client draws from.
     *
     * <p>The span only. A reveal source - the plantern's lamp, a gust - does not travel on this
     * packet: it is a plant, so the client already has its position, and sending it would be the
     * same fact on the wire twice. The client folds the lamps it can see into the span it was
     * given; see {@code FogClientMechanic}.
     */
    public record Wire(float startColumn, float endColumn, float maxAlpha, List<Reveal> reveals) {
        public static final PacketStruct.Codec<Wire> CODEC = PacketStruct.<Wire>builder()
                .field(Wire::startColumn, PacketByteBuf::writeFloat, PacketByteBuf::readFloat)
                .field(Wire::endColumn, PacketByteBuf::writeFloat, PacketByteBuf::readFloat)
                .field(Wire::maxAlpha, PacketByteBuf::writeFloat, PacketByteBuf::readFloat)
                .list(Wire::reveals, Reveal::encode, Reveal::decode)
                .build(values -> new Wire((Float) values.get(0), (Float) values.get(1),
                        (Float) values.get(2), castReveals(values.get(3))));

        public Wire {
            reveals = List.copyOf(reveals);
        }

        @SuppressWarnings("unchecked")
        private static List<Reveal> castReveals(Object value) {
            return value == null ? List.of() : (List<Reveal>) value;
        }

        /** The span alone; a board with no lamps is the ordinary case. */
        public Wire(float startColumn, float endColumn, float maxAlpha) {
            this(startColumn, endColumn, maxAlpha, List.of());
        }

        public static Wire of(FogData data, java.util.Collection<LevelServer.FogReveal> lamps) {
            List<Reveal> reveals = new ArrayList<>();
            if (lamps != null) {
                for (LevelServer.FogReveal lamp : lamps) {
                    reveals.add(new Reveal(lamp.entityId(), lamp.x(), lamp.y(), lamp.radius(),
                            lamp.strength()));
                }
            }
            return new Wire(data.startColumn(), data.endColumn(), data.maxAlpha(), reveals);
        }

        public FogData data() {
            return new FogData(startColumn, endColumn, maxAlpha);
        }

        public void encode(PacketByteBuf buf) {
            CODEC.encode(this, buf);
        }

        public static Wire decode(PacketByteBuf buf) {
            return CODEC.decode(buf);
        }
    }
}
