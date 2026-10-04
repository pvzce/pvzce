package com.pvzce.common.level;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.SceneElementDef;
import com.pvzce.api.content.SceneSurfaceDef;
import com.pvzce.api.content.SurfaceProfile;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceConstants;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.core.SceneCells;
import com.pvzce.common.network.packet.SceneSyncS2C;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared scene state. Covering a surface changes its material, never its foundation or elevation. */
public final class SceneBoard {
    public static final String DEFAULT_SURFACE = "pvzce:ground";
    public record Address(String surface, int x, int y) {}
    public record Cell(Identifier base, Identifier overlay, SurfaceProfile profile, float thickness) {
        public Identifier element() { return overlay == null ? base : overlay; }
    }
    private final int width;
    private final int height;
    private final Map<String, SceneGrid<Cell>> surfaces = new LinkedHashMap<>();
    private final Map<String, String> surfaceNames = new LinkedHashMap<>();

    public SceneBoard(int width, int height) {
        this.width = width;
        this.height = height;
        surfaces.put(DEFAULT_SURFACE, SceneGrid.create(width, height,
                new Cell(PvzceIds.GRASS, null, SurfaceProfile.FLAT, 0F)));
    }

    public static SceneBoard forLevel(LevelDef level) {
        SceneBoard board = new SceneBoard(level.width(), level.height());
        // Legacy backdrop geometry is resolved only while importing old levels. Runtime picking,
        // effects and simulation all consume the resulting surface profile.
        boolean pool = level.background().map(id -> id.path().contains("background3")
                || id.path().contains("background4")).orElse(false);
        for (var cell : SceneCells.parse(level.scene(), level.width(), level.height())) {
            SceneElementDef element = BuiltInRegistries.SCENE_ELEMENTS.get(cell.value());
            if (element == null) continue;
            board.set(cell.x(), cell.y(), element);
            if (pool && element.isLiquid() && element.profile().isEmpty()) {
                Cell old = board.cell(DEFAULT_SURFACE, cell.x(), cell.y());
                board.put(DEFAULT_SURFACE, cell.x(), cell.y(), new Cell(old.base(), old.overlay(),
                        new SurfaceProfile(PvzceConstants.POOL_SURFACE_ORIGIN_Y, 0F,
                                PvzceConstants.POOL_SURFACE_CELL_HEIGHT - 1F, -Float.MAX_VALUE, Float.MAX_VALUE), 0F));
            }
        }
        for (SceneSurfaceDef surface : level.surfaces()) {
            if (DEFAULT_SURFACE.equals(surface.id().toString()) || board.surfaces.containsKey(surface.id().toString())) {
                throw new IllegalArgumentException("Duplicate scene surface " + surface.id());
            }
            board.surfaceNames.put(surface.id().toString(), surface.name());
            board.surfaces.put(surface.id().toString(), SceneGrid.create(level.width(), level.height(), null));
            for (var cell : SceneCells.parse(surface.scene(), level.width(), level.height())) {
                SceneElementDef element = BuiltInRegistries.SCENE_ELEMENTS.get(cell.value());
                if (element == null) continue;
                Cell old = board.cell(surface.id().toString(), cell.x(), cell.y());
                Identifier base = element.overlay() ? old == null ? PvzceIds.GRASS : old.base() : cell.value();
                board.put(surface.id().toString(), cell.x(), cell.y(),
                        new Cell(base, element.overlay() ? cell.value() : null, surface.profile(), surface.thickness()));
            }
        }
        return board;
    }

    public int width() { return width; }
    public int height() { return height; }
    public boolean inBounds(int x, int y) { return x >= 0 && x < width && y >= 0 && y < height; }
    public String surfaceName(String surface) { return surfaceNames.getOrDefault(surface, ""); }
    public List<String> surfaceIds() { return List.copyOf(surfaces.keySet()); }
    public Cell cell(String surface, int x, int y) {
        SceneGrid<Cell> grid = surfaces.get(surface);
        return grid == null ? null : grid.get(x, y);
    }
    public boolean exists(String surface, int x, int y) { return cell(surface, x, y) != null; }
    public SceneElementDef get(int x, int y) { return get(DEFAULT_SURFACE, x, y); }
    public SceneElementDef get(String surface, int x, int y) {
        Cell cell = cell(surface, x, y);
        return cell == null ? null : BuiltInRegistries.SCENE_ELEMENTS.get(cell.element());
    }
    public float elevationAt(String surface, float x, float y) {
        int cx = Math.max(0, Math.min(width - 1, (int) Math.floor(x)));
        int cy = Math.max(0, Math.min(height - 1, (int) Math.floor(y)));
        Cell cell = cell(surface, cx, cy);
        return cell == null ? 0F : cell.profile().at(x, y);
    }
    /** Highest support below a world point; also determines which deck can cover its art. */
    public String surfaceBelow(WorldPosition point, String fallback) {
        String result = fallback;
        float best = -Float.MAX_VALUE;
        for (String id : surfaceIds()) {
            Cell cell = cell(id, (int) Math.floor(point.x()), (int) Math.floor(point.y()));
            if (cell == null) continue;
            float elevation = cell.profile().at(point.x(), point.y());
            if (elevation <= point.elevation() + 0.02F && elevation > best) { result = id; best = elevation; }
        }
        return result;
    }

    public List<String> surfacesBottomFirst() {
        return surfaceIds().stream().sorted(java.util.Comparator.comparingDouble(this::sortElevation)).toList();
    }

    private float sortElevation(String surface) {
        Cell center = cell(surface, width / 2, height / 2);
        // Sparse bridges need not occupy the board centre. Their profile still describes
        // their elevation there, rather than the missing cell's zero-height fallback.
        if (center == null) {
            center = surfaces.get(surface).cells().stream().map(SceneGrid.Cell::value)
                    .filter(java.util.Objects::nonNull).findFirst().orElse(null);
        }
        return center == null ? 0F : center.profile().at(width / 2F, height / 2F);
    }

    public WorldPosition ground(String surface, float x, float y) {
        return new WorldPosition(x, y, elevationAt(surface, x, y));
    }
    public void set(int x, int y, SceneElementDef element) { set(DEFAULT_SURFACE, x, y, element); }
    public void set(String surface, int x, int y, SceneElementDef element) {
        Cell old = cell(surface, x, y);
        if (old == null || element == null) return;
        put(surface, x, y, element.overlay()
                ? new Cell(old.base(), element.id(), old.profile(), old.thickness())
                : new Cell(element.id(), null, DEFAULT_SURFACE.equals(surface) && !old.base().equals(element.id())
                        ? element.profileFor(width) : old.profile(), old.thickness()));
    }
    public void clearOverlay(String surface, int x, int y) {
        Cell old = cell(surface, x, y);
        if (old != null) put(surface, x, y, new Cell(old.base(), null, old.profile(), old.thickness()));
    }
    public void put(String surface, int x, int y, Cell cell) {
        if (inBounds(x, y)) surfaces.computeIfAbsent(surface, id -> SceneGrid.create(width, height, null)).set(x, y, cell);
    }
    public void apply(SceneSyncS2C.Cell cell) {
        if (!cell.surfaceName().isEmpty()) surfaceNames.put(cell.surfaceId(), cell.surfaceName());
        put(cell.surfaceId(), cell.x(), cell.y(), new Cell(Identifier.parse(cell.baseId()),
                cell.elementId().equals(cell.baseId()) ? null : Identifier.parse(cell.elementId()),
                cell.profile(), cell.thickness()));
    }
    public List<SceneSyncS2C.Cell> snapshot() {
        List<SceneSyncS2C.Cell> result = new ArrayList<>();
        for (String surface : surfaceIds()) {
            for (var cell : surfaces.get(surface).cells()) {
                if (cell.value() != null) result.add(packetCell(surface, cell.x(), cell.y()));
            }
        }
        return List.copyOf(result);
    }
    public SceneSyncS2C.Cell packetCell(String surface, int x, int y) {
        Cell cell = cell(surface, x, y);
        return new SceneSyncS2C.Cell(x, y, cell.element().toString(), surface,
                cell.base().toString(), cell.profile(), cell.thickness(), surfaceName(surface));
    }

    /** First entry into a foundation/deck, including thin decks and vertical trajectories. */
    public java.util.Optional<WorldPosition> firstObstruction(WorldPosition from, WorldPosition to) {
        float earliest = Float.POSITIVE_INFINITY;
        for (String surface : surfaceIds()) {
            int left = Math.max(0, (int) Math.floor(Math.min(from.x(), to.x())));
            int right = Math.min(width - 1, (int) Math.floor(Math.max(from.x(), to.x())));
            int bottom = Math.max(0, (int) Math.floor(Math.min(from.y(), to.y())));
            int top = Math.min(height - 1, (int) Math.floor(Math.max(from.y(), to.y())));
            for (int cx = left; cx <= right; cx++) for (int cy = bottom; cy <= top; cy++) {
                Cell cell = cell(surface, cx, cy);
                if (cell == null) continue;
                float[] interval = {0F, 1F};
                if (!clip(from.x(), to.x() - from.x(), cx, cx + 1F, interval)
                        || !clip(from.y(), to.y() - from.y(), cy, cy + 1F, interval)) continue;
                // A clamped affine profile is linear between its min/max crossing points.
                List<Float> cuts = new ArrayList<>(List.of(interval[0], interval[1]));
                SurfaceProfile profile = cell.profile();
                float raw = profile.elevation() + profile.slopeX() * from.x() + profile.slopeY() * from.y();
                float delta = profile.slopeX() * (to.x() - from.x()) + profile.slopeY() * (to.y() - from.y());
                if (Math.abs(delta) > 0.000001F) {
                    for (float bound : new float[]{profile.min(), profile.max()}) {
                        float t = (bound - raw) / delta;
                        if (t > interval[0] && t < interval[1]) cuts.add(t);
                    }
                }
                cuts.sort(Float::compare);
                for (int i = 1; i < cuts.size(); i++) {
                    float a = cuts.get(i - 1), b = cuts.get(i);
                    float qa = relativeHeight(from, to, profile, a);
                    float qb = relativeHeight(from, to, profile, b);
                    if (DEFAULT_SURFACE.equals(surface)) {
                        if (!clip(qa, (qb - qa) / Math.max(0.000001F, b - a),
                                -Float.MAX_VALUE, -0.01F, new float[]{0F, b - a})) continue;
                        float t = qa < -0.01F ? a : a + (-0.01F - qa) * (b - a) / (qb - qa);
                        earliest = Math.min(earliest, t);
                    } else if (cell.thickness() > 0F) {
                        float[] local = {0F, b - a};
                        if (clip(qa, (qb - qa) / Math.max(0.000001F, b - a),
                                -cell.thickness(), -Math.min(.01F, cell.thickness() / 2F), local)) earliest = Math.min(earliest, a + local[0]);
                    } else if (qa >= 0F && qb < 0F || qa < 0F && qb >= 0F) {
                        earliest = Math.min(earliest, a + qa * (b - a) / (qa - qb));
                    }
                }
            }
        }
        if (!Float.isFinite(earliest)) return java.util.Optional.empty();
        return java.util.Optional.of(new WorldPosition(
                from.x() + (to.x() - from.x()) * earliest,
                from.y() + (to.y() - from.y()) * earliest,
                from.elevation() + (to.elevation() - from.elevation()) * earliest));
    }

    public boolean obstructed(WorldPosition from, WorldPosition to) {
        return firstObstruction(from, to).isPresent();
    }

    private static float relativeHeight(WorldPosition from, WorldPosition to, SurfaceProfile profile, float t) {
        return from.elevation() + (to.elevation() - from.elevation()) * t
                - profile.at(from.x() + (to.x() - from.x()) * t, from.y() + (to.y() - from.y()) * t);
    }

    /** Clips a parameter interval against one dimension of a slab. */
    private static boolean clip(float origin, float delta, float min, float max, float[] interval) {
        if (Math.abs(delta) < 0.000001F) return origin >= min && origin < max;
        float a = (min - origin) / delta, b = (max - origin) / delta;
        interval[0] = Math.max(interval[0], Math.min(a, b));
        interval[1] = Math.min(interval[1], Math.max(a, b));
        return interval[0] <= interval[1];
    }
}
