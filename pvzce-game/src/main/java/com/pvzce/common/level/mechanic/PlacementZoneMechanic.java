package com.pvzce.common.level.mechanic;

import com.mojang.serialization.MapCodec;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.PlacementZone;
import com.pvzce.api.content.PlantDef;
import com.pvzce.api.content.mechanic.FieldSpec;
import com.pvzce.server.level.LevelServer;

import java.util.List;

/**
 * Restricts planting to part of the board - the red line in Wall-nut Bowling.
 *
 * <p>A general mechanic rather than a bowling feature: "this level is played on the left
 * four columns" is a statement about the level, and any level may make it. It is checked
 * through {@link LevelMechanics#canPlacePlant}, which the server consults at the same
 * point it asks the terrain and stacking rules, so the player, the shovel, the glove and
 * the plant AI all obey it - the two authoring paths that deliberately do not are
 * {@code /spawn plant} and a level's own {@code initial_entities}.
 */
public final class PlacementZoneMechanic implements LevelMechanic<PlacementZone> {
    @Override
    public MapCodec<PlacementZone> codec() {
        return PlacementZone.MAP_CODEC;
    }

    @Override
    public List<String> validate(LevelDef def, PlacementZone data) {
        return data.validate(def.width(), def.height());
    }

    @Override
    public boolean canPlacePlant(LevelServer level, PlacementZone data, PlantDef def, int x, int y) {
        return data.contains(x, y);
    }

    @Override
    public List<FieldSpec> editorFields() {
        return List.of(
                FieldSpec.integer("min_x", "pvzce.mechanic.placement_zone.field.min_x", 0, 64),
                FieldSpec.integer("max_x", "pvzce.mechanic.placement_zone.field.max_x", 0, 64),
                FieldSpec.integer("min_y", "pvzce.mechanic.placement_zone.field.min_y", 0, 64),
                FieldSpec.integer("max_y", "pvzce.mechanic.placement_zone.field.max_y", 0, 64));
    }
}
