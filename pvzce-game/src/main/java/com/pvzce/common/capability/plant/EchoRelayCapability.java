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

/** A finite relay which stores three beats, then accelerates its own attack clock. */
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
    private float restRemaining;
    private int relayUntil;
    private int clockTick = -1;
    private int charge;
    private int resonanceUntil;
    private int rowBoostTick = -1;
    private float rowBoostRate = 1F;
    private int animationUntil;
    private final List<Pulse> pending = new ArrayList<>();

    private record Pulse(int at, int damage, boolean resonating, boolean root) {
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
        advance(plant, level);
        if (restRemaining > 0F || level.tickCount() < relayUntil) {
            return;
        }
        Map<PlantEntity, Integer> choir = choir(plant, level);
        // Advance each member once, before checking the shared gate. Tick order must not give
        // a late member a second clock step or delay a whole choir by one member's iteration.
        for (PlantEntity member : choir.keySet()) {
            relay(member).advance(member, level);
        }
        if (choir.isEmpty() || choir.keySet().stream().anyMatch(p -> relay(p).restRemaining > 0F
                || level.tickCount() < relay(p).relayUntil) || !hasTarget(choir, level)) {
            return;
        }
        schedule(choir, level);
        tickPending(plant, level);
    }

    private void advance(PlantEntity plant, LevelAccess level) {
        int now = level.tickCount();
        if (clockTick == now) {
            return;
        }
        clockTick = now;
        // Accumulate only the current tick's rate. Multiplying elapsed time by a changing
        // rate would retroactively award a burst of attacks, then stall when haste ends.
        if (now > relayUntil) {
            restRemaining = Math.max(0F, restRemaining - plant.actionRate(resonanceRate(now)));
        }
    }

    private float resonanceRate(int now) {
        return Math.max(now < resonanceUntil ? PvzceConstants.ECHO_RESONANCE_RATE : 1F,
                rowBoostTick == now ? rowBoostRate : 1F);
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

    /** Lend this tick's haste to an intersecting choir; moving the row leaves no stale buff. */
    public static void boost(Map<PlantEntity, Integer> choir, LevelAccess level, float rate) {
        for (PlantEntity plant : choir.keySet()) {
            EchoRelayCapability capability = relay(plant);
            capability.rowBoostTick = level.tickCount();
            capability.rowBoostRate = rate;
        }
    }

    private static void schedule(Map<PlantEntity, Integer> choir, LevelAccess level) {
        int now = level.tickCount();
        int tail = choir.values().stream().mapToInt(Integer::intValue).max().orElse(0)
                * PvzceConstants.ECHO_RELAY_TICKS;
        // Take the least-charged member: a newly attached plant cannot mint a full charge.
        int charge = choir.keySet().stream().mapToInt(p -> relay(p).charge).min().orElse(0);
        int resonanceUntil = choir.keySet().stream().mapToInt(p -> relay(p).resonanceUntil).max().orElse(0);
        boolean ownResonance = now < resonanceUntil;
        if (choir.size() > 1 && !ownResonance) {
            charge++;
        }
        boolean charged = charge >= PvzceConstants.ECHO_CHARGE_VOLLEYS;
        for (Map.Entry<PlantEntity, Integer> entry : choir.entrySet()) {
            EchoRelayCapability capability = relay(entry.getKey());
            capability.charge = charged ? 0 : charge;
            capability.resonanceUntil = charged ? now + tail + PvzceConstants.ECHO_RESONANCE_TICKS
                    : resonanceUntil;
            int amount = choir.size() > 1 ? capability.linkedDamage : capability.damage;
            capability.pending.add(new Pulse(now + entry.getValue() * PvzceConstants.ECHO_RELAY_TICKS,
                    amount, capability.resonanceRate(now) > 1F, entry.getValue() == 0));
            capability.restRemaining = capability.interval;
            capability.relayUntil = now + tail;
            capability.clockTick = now;
        }
    }

    @Override
    public boolean holdsFire(PlantEntity plant) {
        return true;
    }

    @Override
    public boolean hasPendingWork(PlantEntity plant) {
        return !pending.isEmpty() || animationUntil > 0 || charge > 0 || resonanceUntil > 0;
    }

    @Override
    public void tickPending(PlantEntity plant, LevelAccess level) {
        int now = level.tickCount();
        if (now >= resonanceUntil) {
            resonanceUntil = 0;
        }
        if (animationUntil > 0 && now >= animationUntil) {
            animationUntil = 0;
        }
        if (animationUntil == 0) {
            plant.setState(resonanceRate(now) > 1F ? "charged" : charge > 0 ? "charge_" + charge : "idle");
        }
        for (int i = 0; i < pending.size();) {
            Pulse pulse = pending.get(i);
            if (pulse.at() > now) {
                i++;
                continue;
            }
            pending.remove(i);
            String animation = pulse.resonating() ? "resonate"
                    : (pulse.root() ? "shoot" : "echo") + (charge > 0 ? "_charge_" + charge : "");
            fire(plant, level, pulse.damage(), animation);
            animationUntil = now + (pulse.resonating() ? 39 : 29);
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
        schedule(choir(plant, level), level);
        tickPending(plant, level);
        return true;
    }

    @Override
    public void save(CompoundTag tag) {
        tag.putFloat("restRemaining", restRemaining);
        tag.putInt("relayUntil", relayUntil);
        tag.putInt("clockTick", clockTick);
        tag.putInt("charge", charge);
        tag.putInt("resonanceUntil", resonanceUntil);
        tag.putInt("rowBoostTick", rowBoostTick);
        tag.putFloat("rowBoostRate", rowBoostRate);
        tag.putInt("animationUntil", animationUntil);
        ListTag pulses = new ListTag();
        for (Pulse pulse : pending) {
            CompoundTag saved = new CompoundTag();
            saved.putInt("at", pulse.at());
            saved.putInt("damage", pulse.damage());
            saved.putInt("resonating", pulse.resonating() ? 1 : 0);
            saved.putInt("root", pulse.root() ? 1 : 0);
            pulses.add(saved);
        }
        tag.put("pending", pulses);
    }

    @Override
    public void load(CompoundTag tag) {
        // Old saves used an absolute readyAt and an animation string for promised pulses.
        clockTick = tag.contains("clockTick") ? tag.getInt("clockTick") : -1;
        restRemaining = Math.max(0F, tag.getFloat("restRemaining"));
        relayUntil = tag.contains("relayUntil") ? tag.getInt("relayUntil") : tag.getInt("readyAt");
        charge = Math.max(0, Math.min(PvzceConstants.ECHO_CHARGE_VOLLEYS - 1, tag.getInt("charge")));
        resonanceUntil = tag.getInt("resonanceUntil");
        rowBoostTick = tag.contains("rowBoostTick") ? tag.getInt("rowBoostTick") : -1;
        rowBoostRate = Math.max(1F, tag.getFloat("rowBoostRate"));
        animationUntil = tag.getInt("animationUntil");
        pending.clear();
        for (Tag item : tag.getList("pending").values()) {
            if (item instanceof CompoundTag saved) {
                pending.add(new Pulse(saved.getInt("at"), saved.getInt("damage"),
                        saved.getInt("resonating") != 0 || "resonate".equals(saved.getString("animation")),
                        saved.getInt("root") != 0 || "shoot".equals(saved.getString("animation"))));
            }
        }
    }
}
