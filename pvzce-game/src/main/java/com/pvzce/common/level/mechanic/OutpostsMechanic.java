package com.pvzce.common.level.mechanic;

import com.mojang.serialization.MapCodec;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.PlantDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceConstants;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.PvzceParticles;
import com.pvzce.common.PvzceSounds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.level.SceneBoard;
import com.pvzce.common.level.WorldPosition;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PacketStruct;
import com.pvzce.common.network.packet.MechanicSyncS2C;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.server.level.LevelServer;
import java.util.ArrayList;
import java.util.List;

/** Server-owned occupation, one-time capture rewards and bounded spatial artillery. */
public final class OutpostsMechanic implements LevelMechanic<OutpostPlan> {
    public record PointStatus(int progress, boolean owned, int charges) {
    }

    public record Status(List<PointStatus> points) {
        public static final PacketStruct.Codec<Status> CODEC = PacketStruct.<Status>builder()
                .list(Status::points, (point, buf) -> {
                    buf.writeInt(point.progress());
                    buf.writeBoolean(point.owned());
                    buf.writeInt(point.charges());
                }, buf -> new PointStatus(buf.readInt(), buf.readBoolean(), buf.readInt()))
                .build(v -> new Status(castPoints(v.get(0))));

        public Status {
            points = List.copyOf(points);
        }

        @SuppressWarnings("unchecked")
        private static List<PointStatus> castPoints(Object points) {
            return (List<PointStatus>) points;
        }
    }

    private static final class PointProgress {
        int progress;
        boolean owned;
        boolean rewarded;
        int charges;
    }

    private static List<PointProgress> progress(LevelServer level, OutpostPlan data) {
        return level.mechanicState(PvzceIds.MECHANIC_OUTPOSTS, () -> {
            List<PointProgress> points = new ArrayList<>();
            for (OutpostPlan.Point point : data.points()) {
                points.add(new PointProgress());
            }
            return points;
        });
    }

    public static OutpostPlan plan(LevelServer level) {
        return LevelMechanics.data(level.def(), PvzceIds.MECHANIC_OUTPOSTS, OutpostPlan.class);
    }

    public static Status status(LevelServer level) {
        OutpostPlan data = plan(level);
        return new Status(data == null ? List.of() : progress(level, data).stream()
                .map(p -> new PointStatus(p.progress, p.owned, p.charges)).toList());
    }

    @Override
    public MapCodec<OutpostPlan> codec() {
        return OutpostPlan.CODEC;
    }

    @Override
    public void tick(LevelServer level, OutpostPlan data) {
        if (level.isPreparing()) {
            return;
        }
        List<PointProgress> points = progress(level, data);
        boolean dirty = false;
        for (int i = 0; i < data.points().size(); i++) {
            OutpostPlan.Point point = data.points().get(i);
            PointProgress p = points.get(i);
            PlantEntity anchor = level.plantAt(point.x(), point.y(), point.surface());
            boolean defended = anchor != null && !anchor.isRemoved() && anchor.occupiesCell()
                    && anchor.team().equals(level.team(PvzceIds.PLANT_TEAM));
            boolean contested = level.entities().stream().anyMatch(entity -> entity instanceof ZombieEntity zombie
                    && zombie.isAlive() && zombie.surfaceId().equals(point.surface())
                    && LevelServer.isEnemyOf(zombie.team(), level.team(PvzceIds.PLANT_TEAM))
                    && Math.abs(zombie.cellX() - point.x() - 0.5F) <= PvzceConstants.OUTPOST_CONTEST_RADIUS_CELLS
                    && zombie.gridY() == point.y());
            if (!defended) {
                dirty |= p.owned || p.progress != 0;
                p.progress = 0;
                p.owned = false;
            } else if (!p.owned && !contested && level.currentWave() >= point.fromWave()) {
                p.progress++;
                if (p.progress >= point.captureTicks()) {
                    p.owned = true;
                    if (!p.rewarded) {
                        p.rewarded = true;
                        p.charges = point.charges();
                        level.team(PvzceIds.PLANT_TEAM).addResource(PvzceIds.SUN, point.supplySun());
                    }
                    dirty = true;
                }
            }
        }
        if (dirty || level.tickCount() % PvzceConstants.TICKS_PER_SECOND == 0) {
            sync(level);
        }
    }

    private static boolean hasAnchor(LevelServer level, OutpostPlan.Point point) {
        PlantEntity plant = level.plantAt(point.x(), point.y(), point.surface());
        return plant != null && !plant.isRemoved() && plant.occupiesCell()
                && plant.team().equals(level.team(PvzceIds.PLANT_TEAM));
    }

    @Override
    public boolean canPlacePlant(LevelServer level, OutpostPlan data, PlantDef plant, int x, int y,
                                 String surface) {
        List<PointProgress> points = progress(level, data);
        return data.allowsPlacement(x, y, surface,
                i -> points.get(i).owned && hasAnchor(level, data.points().get(i)));
    }

    public static void grantStageSupply(LevelServer level) {
        OutpostPlan data = plan(level);
        if (data == null) {
            return;
        }
        List<PointProgress> points = progress(level, data);
        for (int i = 0; i < points.size(); i++) {
            if (points.get(i).owned && hasAnchor(level, data.points().get(i))) {
                level.team(PvzceIds.PLANT_TEAM).addResource(PvzceIds.SUN, data.points().get(i).supplySun());
            }
        }
    }

    public static boolean canFinish(LevelServer level) {
        OutpostPlan data = plan(level);
        return data != null && data.goal().isPresent() && StagesMechanic.isReady(level)
                && (level.def().waves().isEmpty() || level.currentWave() >= level.def().waves().size()
                        && level.currentWaveFullyReleased()) && level.hostileZombieCount() == 0
                && !data.points().isEmpty() && progress(level, data).stream().allMatch(p -> p.owned)
                && data.points().stream().allMatch(point -> hasAnchor(level, point));
    }

    public static boolean fire(LevelServer level, int index, int x, int y, String surface) {
        OutpostPlan data = plan(level);
        if (data == null || index < 0 || index >= data.points().size()
                || level.isPreparing() || level.isRoundClearPending()
                || !level.sceneBoard().exists(surface, x, y)) {
            return false;
        }
        OutpostPlan.Point point = data.points().get(index);
        PointProgress p = progress(level, data).get(index);
        if (!p.owned || !hasAnchor(level, point) || point.charges() <= 0) {
            return false;
        }
        boolean finishing = data.goal().filter(goal -> goal.x() == x && goal.y() == y
                && goal.surface().equals(surface)).isPresent() && canFinish(level);
        if (!finishing && p.charges <= 0) {
            return false;
        }
        if (!finishing) {
            p.charges--;
        }
        WorldPosition target = new WorldPosition(x + 0.5F, y + 0.5F,
                level.surfaceHeight(surface, x + 0.5F, y + 0.5F));
        level.damageArea(BuiltInRegistries.DAMAGE_TYPES.get(PvzceIds.DAMAGE_ASH), target,
                point.radius(), point.damage(), level.team(PvzceIds.PLANT_TEAM), false);
        level.emitEffect(PvzceParticles.EXPLOSION_POW.toString(), target, surface, PvzceSounds.EFFECT_EXPLOSION);
        if (finishing) {
            level.declareVictory();
        }
        sync(level);
        return true;
    }

    private static void sync(LevelServer level) {
        level.send(MechanicSyncS2C.of(PvzceIds.MECHANIC_OUTPOSTS, Status.CODEC, status(level)));
    }

    @Override
    public void sendState(LevelServer level, OutpostPlan data, LevelServer.ServerBridge bridge) {
        bridge.send(MechanicSyncS2C.of(PvzceIds.MECHANIC_OUTPOSTS, Status.CODEC, status(level)));
    }

    @Override
    public void collectSave(LevelServer level, OutpostPlan data, CompoundTag root) {
        CompoundTag saved = new CompoundTag();
        List<PointProgress> points = progress(level, data);
        for (int i = 0; i < points.size(); i++) {
            PointProgress p = points.get(i);
            CompoundTag point = new CompoundTag();
            point.putInt("Progress", p.progress);
            point.putByte("Owned", (byte) (p.owned ? 1 : 0));
            point.putByte("Rewarded", (byte) (p.rewarded ? 1 : 0));
            point.putInt("Charges", p.charges);
            saved.put(Integer.toString(i), point);
        }
        root.put("Outposts", saved);
    }

    @Override
    public void applySave(LevelServer level, OutpostPlan data, CompoundTag root) {
        CompoundTag saved = root.getCompound("Outposts");
        List<PointProgress> points = progress(level, data);
        for (int i = 0; i < points.size(); i++) {
            CompoundTag point = saved.getCompound(Integer.toString(i));
            PointProgress p = points.get(i);
            p.progress = Math.max(0, Math.min(data.points().get(i).captureTicks(), point.getInt("Progress")));
            p.owned = point.getInt("Owned") != 0;
            p.rewarded = point.getInt("Rewarded") != 0;
            p.charges = Math.max(0, Math.min(data.points().get(i).charges(), point.getInt("Charges")));
        }
    }

    @Override
    public List<String> validate(LevelDef def, OutpostPlan data) {
        List<String> errors = new ArrayList<>();
        SceneBoard board = SceneBoard.forLevel(def);
        if (data.baseMaxX() < 0 || data.baseMaxX() >= def.width()) {
            errors.add("Outpost base_max_x must name a board column");
        }
        for (OutpostPlan.Point point : data.points()) {
            if (!board.exists(point.surface(), point.x(), point.y()) || point.captureTicks() <= 0
                    || point.supplySun() < 0 || point.charges() < 0 || point.damage() < 0
                    || point.fromWave() < 0 || point.fromWave() > def.waves().size()
                    || point.unlocksMaxX() < data.baseMaxX() || point.unlocksMaxX() >= def.width()
                    || !Float.isFinite(point.radius()) || point.radius() <= 0F) {
                errors.add("Invalid outpost: " + point.name());
            }
        }
        if (data.goal().isPresent()) {
            OutpostPlan.Goal goal = data.goal().get();
            if (!board.exists(goal.surface(), goal.x(), goal.y()) || data.points().isEmpty()
                    || data.points().stream().noneMatch(point -> point.charges() > 0)) {
                errors.add("Outpost goal requires a supported cell and an artillery point");
            }
        }
        return errors;
    }
}
