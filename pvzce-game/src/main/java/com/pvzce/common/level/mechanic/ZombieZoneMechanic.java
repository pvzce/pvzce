package com.pvzce.common.level.mechanic;

import com.mojang.serialization.MapCodec;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.PlacementZone;
import com.pvzce.api.content.mechanic.FieldSpec;
import com.pvzce.common.PvzceIds;
import com.pvzce.server.level.LevelServer;

import java.util.List;

/**
 * Restricts where the zombie side may put its zombies down.
 *
 * <p>The mirror of {@link PlacementZoneMechanic}, and deliberately a second mechanic rather than a
 * flag on the first: the two answer different questions ("may a plant go here" and "may a zombie
 * card be spent here") through different hooks, and a level is free to use either one alone. What
 * they share is the data record - a rectangle of the board is a rectangle of the board - so there
 * is one set of bounds, one validator and one editor row per block, not two copies of each.
 *
 * <p>It is checked by {@code LevelServer.canPlaceZombie}, which both the player's placement packet
 * and the opponent's own decisions go through. That is what makes "zombies only on the right four
 * columns" a rule of the level rather than a convention the AI happens to follow.
 */
public final class ZombieZoneMechanic implements LevelMechanic<PlacementZone> {
    public static final String LABEL = "zombie_zone";

    @Override
    public MapCodec<PlacementZone> codec() {
        return PlacementZone.MAP_CODEC;
    }

    @Override
    public List<String> validate(LevelDef def, PlacementZone data) {
        return data.validate(def.width(), def.height(), LABEL);
    }

    @Override
    public boolean canPlaceZombie(LevelServer level, PlacementZone data, int x, int y) {
        return data.contains(x, y);
    }

    @Override
    public List<FieldSpec> editorFields() {
        return List.of(
                FieldSpec.integer("min_x", "pvzce.mechanic." + LABEL + ".field.min_x", 0, 64),
                FieldSpec.integer("max_x", "pvzce.mechanic." + LABEL + ".field.max_x", 0, 64),
                FieldSpec.integer("min_y", "pvzce.mechanic." + LABEL + ".field.min_y", 0, 64),
                FieldSpec.integer("max_y", "pvzce.mechanic." + LABEL + ".field.max_y", 0, 64));
    }

    /** The zone a level declares, or the whole board when it declares none. */
    public static PlacementZone of(LevelDef def) {
        return LevelMechanics.dataOf(def, PvzceIds.MECHANIC_ZOMBIE_ZONE, PlacementZone.class)
                .orElse(PlacementZone.FULL);
    }
}
