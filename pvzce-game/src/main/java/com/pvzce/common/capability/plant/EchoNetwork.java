package com.pvzce.common.capability.plant;

import com.pvzce.api.entity.LevelAccess;
import com.pvzce.common.PvzceConstants;
import com.pvzce.common.core.PlantPlacement;
import com.pvzce.common.network.packet.EchoNetworkS2C;
import com.pvzce.server.entity.PlantEntity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** One authoritative topology for relays, row excitation and plants standing on conduits. */
public final class EchoNetwork {
    private EchoNetwork() {
    }

    public static boolean node(PlantEntity plant, LevelAccess level) {
        return plant != null && !plant.isRemoved() && plant.health() > 0 && plant.occupiesCell()
                && !plant.isAsleep(level) && (plant.capability(EchoRelayCapability.class) != null
                || plant.capability(EchoConduitCapability.class) != null);
    }

    /** BFS over occupied cells: two nodes on the same cell have zero relay distance. */
    public static Map<PlantEntity, Integer> nodes(PlantEntity root, LevelAccess level) {
        Map<PlantEntity, Integer> result = new LinkedHashMap<>();
        if (!node(root, level)) {
            return result;
        }
        record Cell(int x, int y, int distance) { }
        var queue = new ArrayList<Cell>();
        var visited = new java.util.HashSet<Integer>();
        queue.add(new Cell(root.gridX(), root.gridY(), 0));
        visited.add(root.gridY() * level.width() + root.gridX());
        for (int i = 0; i < queue.size(); i++) {
            Cell cell = queue.get(i);
            for (PlantEntity plant : level.plantsAt(cell.x(), cell.y(), root.surfaceId())) {
                if (node(plant, level) && plant.team().id().equals(root.team().id())) {
                    result.put(plant, cell.distance());
                }
            }
            for (int[] step : new int[][]{{-1, 0}, {1, 0}, {0, -1}, {0, 1}}) {
                int x = cell.x() + step[0];
                int y = cell.y() + step[1];
                if (x < 0 || x >= level.width() || y < 0 || y >= level.height()
                        || visited.contains(y * level.width() + x)) {
                    continue;
                }
                if (level.plantsAt(x, y, root.surfaceId()).stream().anyMatch(p -> node(p, level)
                        && p.team().id().equals(root.team().id()))) {
                    visited.add(y * level.width() + x);
                    queue.add(new Cell(x, y, cell.distance() + 1));
                }
            }
        }
        return result;
    }

    public static float strength(int lilies) {
        return lilies == 0 ? 1F : Math.min(PvzceConstants.ECHO_RESONANCE_RATE,
                PvzceConstants.ECHO_RESONANCE_BASE_RATE
                        + (lilies - 1) * PvzceConstants.ECHO_RESONANCE_RATE_PER_LILY);
    }

    /** Upper plants join through a live, friendly conduit strictly beneath their layer. */
    public static EchoNetworkS2C status(PlantEntity plant, LevelAccess level) {
        PlantEntity root = plant;
        if (!node(root, level)) {
            if (plant.isRemoved() || plant.health() <= 0 || !plant.occupiesCell() || plant.isAsleep(level)) {
                return EchoNetworkS2C.empty(plant.id());
            }
            root = level.plantsAt(plant.gridX(), plant.gridY(), plant.surfaceId()).stream()
                    .filter(p -> node(p, level) && p.capability(EchoConduitCapability.class) != null
                            && PlantPlacement.layerIndex(p.def()) < PlantPlacement.layerIndex(plant.def()) && p.team().id().equals(plant.team().id()))
                    .findFirst().orElse(null);
        }
        var nodes = nodes(root, level);
        var lilies = nodes.keySet().stream().map(p -> p.capability(EchoRelayCapability.class))
                .filter(java.util.Objects::nonNull).toList();
        if (lilies.isEmpty()) {
            return EchoNetworkS2C.empty(plant.id());
        }
        int now = level.tickCount();
        boolean charged = lilies.stream().anyMatch(c -> c.resonanceUntil() > now);
        float rowLimit = (float) lilies.stream().mapToDouble(c -> c.rowRate(now)).max().orElse(1F);
        float rate = Math.min(strength(lilies.size()),
                Math.max(charged ? PvzceConstants.ECHO_RESONANCE_RATE : 1F, rowLimit));
        int charge = rate > 1F ? PvzceConstants.ECHO_CHARGE_VOLLEYS
                : lilies.stream().mapToInt(EchoRelayCapability::charge).min().orElse(0);
        int group = nodes.keySet().stream().mapToInt(PlantEntity::id).min().orElse(0);
        return new EchoNetworkS2C(plant.id(), group, lilies.size(), charge, rate);
    }
}
