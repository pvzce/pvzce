package com.pvzce.common.level.mechanic;

import com.mojang.serialization.MapCodec;
import com.pvzce.api.content.LevelDef;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.PvzceConstants;
import com.pvzce.common.level.SceneBoard;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.server.level.LevelServer;
import java.util.ArrayList;
import java.util.List;

/** Directed, same-lane ramp crossings; connections never infer a destination from render height. */
public final class SurfaceLinksMechanic implements LevelMechanic<SurfaceLinksData> {
    @Override
    public MapCodec<SurfaceLinksData> codec() {
        return SurfaceLinksData.CODEC;
    }

    public static void cross(LevelServer level, ZombieEntity zombie, float beforeX, String beforeSurface) {
        SurfaceLinksData data = LevelMechanics.data(level.def(), PvzceIds.MECHANIC_SURFACE_LINKS,
                SurfaceLinksData.class);
        if (data == null || !zombie.isAlive() || !beforeSurface.equals(zombie.surfaceId())
                || zombie.height() > level.surfaceHeight(beforeSurface, beforeX, zombie.cellY())
                        + PvzceConstants.SURFACE_LINK_AIRBORNE_MARGIN_CELLS) {
            return;
        }
        for (SurfaceLinksData.Connection link : data.connections()) {
            float boundary = link.x() + 0.5F;
            boolean crossed = link.direction() < 0
                    ? beforeX > boundary && zombie.cellX() <= boundary
                    : beforeX < boundary && zombie.cellX() >= boundary;
            if (crossed && link.from().equals(beforeSurface) && zombie.gridY() == link.y()
                    && level.sceneBoard().exists(link.to(), zombie.gridX(), zombie.gridY())) {
                zombie.setSurfaceId(link.to());
                zombie.setHeight(level.surfaceHeight(link.to(), zombie.cellX(), zombie.cellY()));
                return;
            }
        }
    }

    @Override
    public List<String> validate(LevelDef def, SurfaceLinksData data) {
        List<String> errors = new ArrayList<>();
        SceneBoard board = SceneBoard.forLevel(def);
        for (SurfaceLinksData.Connection link : data.connections()) {
            if (Math.abs(link.direction()) != 1 || link.from().equals(link.to())
                    || !board.exists(link.from(), link.x(), link.y())
                    || !board.exists(link.to(), link.x(), link.y())) {
                errors.add("Invalid surface connection at " + link.x() + "," + link.y());
            }
        }
        return errors;
    }
}
