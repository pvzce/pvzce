package com.pvzce.common.capability.plant;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.ProjectileRef;
import com.pvzce.api.content.capability.PlantCapability;
import com.pvzce.api.entity.LevelAccess;
import com.pvzce.common.PvzceConstants;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.common.nbt.ListTag;
import com.pvzce.common.nbt.Tag;
import com.pvzce.server.entity.PlantEntity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A connected choir, with one wave per member and a shared rest after the last echo. */
public final class EchoRelayCapability implements PlantCapability {
    public static final MapCodec<EchoRelayCapability> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.intRange(1, 7200).optionalFieldOf("interval", PvzceConstants.ECHO_INTERVAL_TICKS)
                    .forGetter(c -> c.interval),
            Codec.intRange(1, 10000).optionalFieldOf("damage", PvzceConstants.ECHO_DAMAGE)
                    .forGetter(c -> c.damage),
            Codec.intRange(1, 10000).optionalFieldOf("linked_damage", PvzceConstants.ECHO_LINKED_DAMAGE)
                    .forGetter(c -> c.linkedDamage)
    ).apply(i, EchoRelayCapability::new));

    private final int interval;
    private final int damage;
    private final int linkedDamage;
    private int readyAt;
    private int resonanceReadyAt;
    private int animationUntil;
    private final List<Pulse> pending = new ArrayList<>();

    private record Pulse(int at, int damage, String animation) {
    }

    public EchoRelayCapability(int interval, int damage, int linkedDamage) {
        this.interval = interval;
        this.damage = damage;
        this.linkedDamage = linkedDamage;
    }

    @Override
    public PlantCapability instantiate() {
        return new EchoRelayCapability(interval, damage, linkedDamage);
    }

    @Override
    public void tick(PlantEntity plant, LevelAccess level) {
        tickPending(plant, level);
        if (level.tickCount() < readyAt) {
            return;
        }
        Map<PlantEntity, Integer> choir = choir(plant, level);
        // Every member shares this gate. Adding a leaf to a choir cannot buy another volley,
        // and a square of four plants cannot keep ringing itself forever.
        if (choir.keySet().stream().anyMatch(p -> level.tickCount() < relay(p).readyAt)
                || !hasTarget(choir, level)) {
            return;
        }
        schedule(choir, level, false, 0, interval);
        tickPending(plant, level);
    }

    /** Orthogonal neighbours only; dead, sleeping and opposing plants never carry a signal. */
    public static Map<PlantEntity, Integer> choir(PlantEntity root, LevelAccess level) {
        Map<PlantEntity, Integer> distances = new LinkedHashMap<>();
        if (!eligible(root, level)) {
            return distances;
        }
        distances.put(root, 0);
        List<PlantEntity> queue = new ArrayList<>();
        queue.add(root);
        for (int index = 0; index < queue.size(); index++) {
            PlantEntity current = queue.get(index);
            for (int[] step : new int[][]{{-1, 0}, {1, 0}, {0, -1}, {0, 1}}) {
                int x = current.gridX() + step[0];
                int y = current.gridY() + step[1];
                if (x < 0 || x >= level.width() || y < 0 || y >= level.height()) {
                    continue;
                }
                for (PlantEntity neighbour : level.plantsAt(x, y)) {
                    if (eligible(neighbour, level) && neighbour.team().id().equals(root.team().id())
                            && !distances.containsKey(neighbour)) {
                        distances.put(neighbour, distances.get(current) + 1);
                        queue.add(neighbour);
                    }
                }
            }
        }
        return distances;
    }

    private static boolean eligible(PlantEntity plant, LevelAccess level) {
        return plant != null && !plant.isRemoved() && plant.health() > 0
                && !plant.isAsleep(level) && relay(plant) != null;
    }

    private static EchoRelayCapability relay(PlantEntity plant) {
        return plant.capability(EchoRelayCapability.class);
    }

    private static boolean hasTarget(Map<PlantEntity, Integer> choir, LevelAccess level) {
        return choir.keySet().stream().anyMatch(plant -> level.enemiesInRow(plant.gridY(), plant.team())
                .stream().anyMatch(z -> !z.isRemoved() && z.isAlive() && z.canBeHitByGround()
                        && z.cellX() >= plant.cellX()));
    }

    /** A level's extra beat has its own clock and does not postpone the ordinary attack. */
    public static boolean resonate(PlantEntity root, LevelAccess level, int damage, int interval) {
        Map<PlantEntity, Integer> choir = choir(root, level);
        if (choir.isEmpty() || !hasTarget(choir, level)
                || choir.keySet().stream().anyMatch(p -> level.tickCount() < relay(p).resonanceReadyAt)) {
            return false;
        }
        schedule(choir, level, true, damage, interval);
        return true;
    }

    private static void schedule(Map<PlantEntity, Integer> choir, LevelAccess level,
                                 boolean resonance, int bonusDamage, int rest) {
        int now = level.tickCount();
        int tail = choir.values().stream().mapToInt(Integer::intValue).max().orElse(0)
                * PvzceConstants.ECHO_RELAY_TICKS;
        for (Map.Entry<PlantEntity, Integer> entry : choir.entrySet()) {
            EchoRelayCapability capability = relay(entry.getKey());
            int amount = resonance ? bonusDamage
                    : choir.size() > 1 ? capability.linkedDamage : capability.damage;
            int at = now + entry.getValue() * PvzceConstants.ECHO_RELAY_TICKS;
            capability.pending.add(new Pulse(at, amount,
                    resonance ? "resonate" : entry.getValue() == 0 ? "shoot" : "echo"));
            if (resonance) {
                capability.resonanceReadyAt = now + rest;
            } else {
                capability.readyAt = now + rest + tail;
            }
        }
    }

    @Override
    public boolean holdsFire(PlantEntity plant) {
        return true;
    }

    @Override
    public boolean hasPendingWork(PlantEntity plant) {
        return !pending.isEmpty() || animationUntil > 0;
    }

    @Override
    public void tickPending(PlantEntity plant, LevelAccess level) {
        if (animationUntil > 0 && level.tickCount() >= animationUntil) {
            plant.setState("idle");
            animationUntil = 0;
        }
        for (int i = 0; i < pending.size();) {
            Pulse pulse = pending.get(i);
            if (pulse.at() > level.tickCount()) {
                i++;
                continue;
            }
            pending.remove(i);
            fire(plant, level, pulse.damage(), pulse.animation());
            animationUntil = level.tickCount() + ("resonate".equals(pulse.animation()) ? 39 : 29);
        }
    }

    private static void fire(PlantEntity plant, LevelAccess level, int amount, String animation) {
        plant.setState(animation);
        level.spawnProjectile(new ProjectileRef(PvzceIds.ECHO_WAVE, amount, 1),
                plant.cellX() + PlantShots.MUZZLE_OFFSET_X, plant.cellY(), plant);
        level.emitEffect(PvzceIds.ECHO_RING.toString(), plant.cellX(), plant.cellY(),
                PvzceIds.ECHO_CHIME, 0.25F, "resonate".equals(animation) ? 1.2F : 1F);
    }

    @Override
    public boolean strike(PlantEntity plant, LevelAccess level) {
        if (!eligible(plant, level)) {
            return false;
        }
        schedule(choir(plant, level), level, false, 0, interval);
        tickPending(plant, level);
        return true;
    }

    @Override
    public void save(CompoundTag tag) {
        tag.putInt("readyAt", readyAt);
        tag.putInt("resonanceReadyAt", resonanceReadyAt);
        tag.putInt("animationUntil", animationUntil);
        ListTag pulses = new ListTag();
        for (Pulse pulse : pending) {
            CompoundTag saved = new CompoundTag();
            saved.putInt("at", pulse.at());
            saved.putInt("damage", pulse.damage());
            saved.putString("animation", pulse.animation());
            pulses.add(saved);
        }
        tag.put("pending", pulses);
    }

    @Override
    public void load(CompoundTag tag) {
        readyAt = tag.getInt("readyAt");
        resonanceReadyAt = tag.getInt("resonanceReadyAt");
        animationUntil = tag.getInt("animationUntil");
        pending.clear();
        for (Tag item : tag.getList("pending").values()) {
            if (item instanceof CompoundTag saved) {
                pending.add(new Pulse(saved.getInt("at"), saved.getInt("damage"),
                        saved.getString("animation")));
            }
        }
    }
}
