package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.mechanic.MechanicData;

import java.util.List;

/**
 * Pairs of portals: what walks into one comes out of the other.
 *
 * <p>The original's 斗转星移 (Portal Combat). A zombie that crosses a portal's cell leaves the
 * paired one instead - which is a threat rather than a shortcut, because the pair is usually in a
 * lane the player has not defended.
 *
 * @param pairs the portal pairs, each an "in" cell and an "out" cell
 * @param relocateMinX the leftmost column a door may relocate to; the columns to its left are the
 *                     player's reaction room, so a level can keep a door from moving onto the
 *                     house's doorstep. Zero means the whole board, which is what a flat
 *                     "斗转星移"-style board wants
 */
public record PortalData(List<Pair> pairs, int relocateIntervalTicks, int initialRelocateTicks,
                         int relocateMinX)
        implements MechanicData {
    public PortalData(List<Pair> pairs) {
        this(pairs, 0, 0, 0);
    }
    /**
     * One pair. Ends may occupy a lawn cell or the road column just beyond the right edge:
     * which way a zombie travels through is decided by which end it walks into, not by the order
     * the author wrote them in.
     */
    public record Pair(int ax, int ay, int bx, int by) {
        public static final Codec<Pair> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("ax").forGetter(Pair::ax),
                Codec.INT.fieldOf("ay").forGetter(Pair::ay),
                Codec.INT.fieldOf("bx").forGetter(Pair::bx),
                Codec.INT.fieldOf("by").forGetter(Pair::by)
        ).apply(i, Pair::new));
    }

    public static final MapCodec<PortalData> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Pair.CODEC.listOf().fieldOf("pairs").forGetter(PortalData::pairs),
            Codec.INT.optionalFieldOf("relocate_interval_ticks", 0).forGetter(PortalData::relocateIntervalTicks),
            Codec.INT.optionalFieldOf("initial_relocate_ticks", 0).forGetter(PortalData::initialRelocateTicks),
            Codec.INT.optionalFieldOf("relocate_min_x", 0).forGetter(PortalData::relocateMinX)
    ).apply(i, PortalData::new));

    public static final Codec<PortalData> CODEC = MAP_CODEC.codec();

    public PortalData {
        pairs = pairs == null ? List.of() : List.copyOf(pairs);
        relocateIntervalTicks = Math.max(0, relocateIntervalTicks);
        initialRelocateTicks = initialRelocateTicks <= 0 ? relocateIntervalTicks : initialRelocateTicks;
        relocateMinX = Math.max(0, relocateMinX);
    }

    public PortalData(List<Pair> pairs, int relocateIntervalTicks, int initialRelocateTicks) {
        this(pairs, relocateIntervalTicks, initialRelocateTicks, 0);
    }
}
