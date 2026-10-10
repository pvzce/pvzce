package com.pvzce.client.renderer;

import com.pvzce.api.content.PlantDef;
import com.pvzce.api.content.SceneElementDef;
import com.pvzce.api.entity.EntityKind;
import com.pvzce.api.util.Identifier;
import com.pvzce.client.ClientEntity;
import com.pvzce.client.ClientLevel;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.core.PlantPlacement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Where the server would put a plant, asked of the client's own mirror of the board.
 *
 * <p>The one place the client needs the placement rules: the ghost of the plant in the player's
 * hand has to stand exactly where the plant will appear, or the promise the preview makes is a
 * different promise from the one the server keeps. It used to write the cell's middle and the
 * terrain's height and stop there, which left the ghost 0.06 cells to the left of - and 0.30 cells
 * below - a plant planted on a lily pad, and 0.10 below one on a pot: the two offsets
 * {@code LevelServer.spawnPlantInternal} applies through {@link PlantPlacement}.
 *
 * <p>Not a second implementation of the rules: the terrain, the stack and the arithmetic are the
 * same {@link PlantPlacement} the server calls, fed from the client's entities and scene. What the
 * client cannot answer - whether the placement is <em>allowed</em> - is not asked here; that
 * verdict belongs to the server, and the hover tint's own rule is the level's plantable area.
 */
public final class ClientPlacement {
    private ClientPlacement() {
    }

    /** One plant of a cell's stack, as the placement rules see it. */
    private record Stacked(PlantDef def, int entityId) {
    }

    /** Where a plant would stand in this cell: {@code {x, y, height}}, in world cells. */
    public static float[] anchoredAt(ClientLevel level, int x, int y) {
        List<Stacked> stack = plantsAt(level, x, y);
        // The cell's middle, which is where an entity's position lives - writing the grid index
        // straight in drew the ghost half a cell down and to the left of its own cell.
        float anchorX = x + 0.5F;
        if (stack.stream().anyMatch(plant -> PlantPlacement.isCarrier(plant.def()))) {
            anchorX += PlantPlacement.CARRIER_X_OFFSET;
        }
        return new float[]{anchorX, y + 0.5F, PlantPlacement.placementHeight(context(level), x, y)};
    }

    /** The plants standing in a cell, bottom-to-top as {@link PlantPlacement} orders a stack. */
    private static List<Stacked> plantsAt(ClientLevel level, int x, int y) {
        List<Stacked> plants = new ArrayList<>();
        for (ClientEntity entity : level.entities().values()) {
            if (!EntityKind.PLANT.equals(entity.kind()) || !entity.surfaceId().equals(level.activeSurface())) {
                continue;
            }
            PlantDef def = BuiltInRegistries.PLANTS.get(entity.defId());
            if (def != null && PlantPlacement.coversCell(def, entity.gridX(), entity.gridY(), x, y)) {
                plants.add(new Stacked(def, entity.id()));
            }
        }
        plants.sort(Comparator.comparingInt(plant -> PlantPlacement.layerIndex(plant.def())));
        return plants;
    }

    public static int footprintLeft(ClientLevel level, PlantDef def, int x, int y) {
        return def == null ? x : PlantPlacement.upgradeAnchorX(def, context(level), x, y);
    }

    public static float[] anchoredAt(ClientLevel level, PlantDef def, int x, int y) {
        if (def == null) return anchoredAt(level, x, y);
        int left = footprintLeft(level, def, x, y);
        float[] anchor = anchoredAt(level, left, y);
        anchor[0] += (def.placement().width() - 1) * 0.5F;
        return anchor;
    }

    /** The client's own view of a cell's terrain and stack; the server passes its own. */
    private static PlantPlacement.Ctx context(ClientLevel level) {
        return new PlantPlacement.Ctx() {
            @Override
            public boolean upgradesWithoutBases() {
                return level.hasMechanic(com.pvzce.common.PvzceIds.MECHANIC_FUSION);
            }

            @Override
            public PlantPlacement.Terrain terrain(int x, int y) {
                Identifier id = Identifier.tryParse(level.sceneAt(level.activeSurface(), x, y));
                SceneElementDef element = id == null ? null
                        : BuiltInRegistries.SCENE_ELEMENTS.get(id);
                return element == null ? PlantPlacement.Terrain.NONE
                        : new PlantPlacement.Terrain(element,
                                level.sceneBoard().elevationAt(level.activeSurface(), x + 0.5F, y + 0.5F));
            }

            @Override
            public List<PlantPlacement.PlantLayer> plants(int x, int y) {
                List<PlantPlacement.PlantLayer> layers = new ArrayList<>();
                for (Stacked plant : plantsAt(level, x, y)) {
                    layers.add(new PlantPlacement.PlantLayer(plant.def(), plant.entityId()));
                }
                return layers;
            }
        };
    }
}
