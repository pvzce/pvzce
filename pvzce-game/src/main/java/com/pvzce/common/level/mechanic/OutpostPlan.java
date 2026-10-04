package com.pvzce.common.level.mechanic;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.mechanic.MechanicData;
import java.util.List;
import java.util.Optional;

/** Capturable cells grant supplies or a limited number of spatial artillery strikes. */
public record OutpostPlan(List<Point> points, Optional<Goal> goal, int baseMaxX) implements MechanicData {
    public static final MapCodec<OutpostPlan> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Point.CODEC.listOf().fieldOf("points").forGetter(OutpostPlan::points),
            Goal.CODEC.optionalFieldOf("goal").forGetter(OutpostPlan::goal),
            Codec.INT.fieldOf("base_max_x").forGetter(OutpostPlan::baseMaxX)
    ).apply(i, OutpostPlan::new));

    public OutpostPlan {
        points = List.copyOf(points);
    }

    /** Shared frontier geometry; the caller supplies its authoritative or mirrored ownership. */
    public boolean allowsPlacement(int x, int y, String surface, java.util.function.IntPredicate owned) {
        if (x <= baseMaxX) {
            return true;
        }
        for (int i = 0; i < points.size(); i++) {
            Point point = points.get(i);
            if (point.surface().equals(surface) && (point.x() == x && point.y() == y
                    || owned.test(i) && x <= point.unlocksMaxX())) {
                return true;
            }
        }
        return false;
    }

    public record Point(String name, String surface, int x, int y, int captureTicks,
                        int supplySun, int charges, int damage, float radius, int fromWave, int unlocksMaxX) {
        public static final Codec<Point> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("name").forGetter(Point::name),
                Codec.STRING.fieldOf("surface").forGetter(Point::surface),
                Codec.INT.fieldOf("x").forGetter(Point::x),
                Codec.INT.fieldOf("y").forGetter(Point::y),
                Codec.INT.fieldOf("capture_ticks").forGetter(Point::captureTicks),
                Codec.INT.fieldOf("supply_sun").forGetter(Point::supplySun),
                Codec.INT.fieldOf("charges").forGetter(Point::charges),
                Codec.INT.fieldOf("damage").forGetter(Point::damage),
                Codec.FLOAT.fieldOf("radius").forGetter(Point::radius),
                Codec.INT.fieldOf("from_wave").forGetter(Point::fromWave),
                Codec.INT.fieldOf("unlocks_max_x").forGetter(Point::unlocksMaxX)
        ).apply(i, Point::new));
    }

    /** A finishing strike is reserved separately, so earlier artillery cannot soft-lock a run. */
    public record Goal(String surface, int x, int y) {
        public static final Codec<Goal> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("surface").forGetter(Goal::surface),
                Codec.INT.fieldOf("x").forGetter(Goal::x),
                Codec.INT.fieldOf("y").forGetter(Goal::y)
        ).apply(i, Goal::new));
    }
}
