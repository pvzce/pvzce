package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.util.Identifier;

import java.util.Optional;

/**
 * A 1x1 scene element (grass, ground, water, flat/sloped roof, grave, crater...).
 *
 * <p>What may be planted on an element is decided entirely by tags
 * ({@code #c:ground}, {@code #c:plantable}, {@code #c:water},
 * {@code #c:unplantable} in the {@code scene_element} registry - see
 * {@code PlantPlacement}), not by a field here. An earlier version carried an
 * {@code accepts} list of free strings ("plantable", "flower_pot") that the server
 * switched on; it was a second, hand-maintained copy of the same rules and went
 * stale the moment a pack added a tile. Terrain is now data-in, tags-rule: define
 * the element, then tag it in {@code data/<ns>/tags/scene_element/}.
 *
 * <p>{@code maxHeight} gives the slope apex used by projectile hit tests on
 * roofs.
 *
 * <p>{@code liquid}, when present, names the {@link LiquidDef} whose shader renders
 * this element instead of a flat texture. It is an id rather than an embedded
 * definition so the renderer's parameters stay out of the simulation's content:
 * two elements can share one liquid, and a resource pack can restyle a liquid
 * without redefining the element.
 */
public record SceneElementDef(
        Identifier id,
        String surfaceClass,
        float maxHeight,
        Optional<Identifier> liquid
) implements com.pvzce.api.entity.LevelAccess.SceneElementAccess {
    public static final Codec<SceneElementDef> CODEC = RecordCodecBuilder.create(i -> i.group(
            Identifier.CODEC.fieldOf("id").forGetter(SceneElementDef::id),
            Codec.STRING.fieldOf("surface").forGetter(SceneElementDef::surfaceClass),
            Codec.FLOAT.optionalFieldOf("max_height", 0F).forGetter(SceneElementDef::maxHeight),
            Identifier.CODEC.optionalFieldOf("liquid").forGetter(SceneElementDef::liquid)
    ).apply(i, SceneElementDef::new));

    /** An element with no liquid surface; the common case for land tiles. */
    public SceneElementDef(Identifier id, String surfaceClass, float maxHeight) {
        this(id, surfaceClass, maxHeight, Optional.empty());
    }

    /** True when this element is drawn by the liquid renderer. */
    public boolean isLiquid() {
        return liquid.isPresent();
    }

    /** Height at a cell coordinate; sloped roofs interpolate 0..maxHeight. */
    @Override
    public float heightAt(float x, int width) {
        if (maxHeight <= 0 || width <= 1) {
            return 0F;
        }
        return maxHeight * (x / Math.max(1, width - 1));
    }
}
