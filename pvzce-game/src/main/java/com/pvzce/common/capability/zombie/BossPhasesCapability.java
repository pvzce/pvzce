package com.pvzce.common.capability.zombie;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.BossPhaseDef;
import com.pvzce.api.content.capability.ZombieCapability;
import com.pvzce.api.entity.LevelAccess;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceSounds;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.server.entity.ZombieEntity;

import java.util.List;

/**
 * Boss phase machine: at each configured HP fraction the boss summons a wave of
 * zombies and/or casts an ability (ground slam, charge).
 */
public final class BossPhasesCapability implements ZombieCapability {
    public static final float SLAM_RADIUS = 1.5F;
    public static final int SLAM_DAMAGE = 300;
    public static final int CHARGE_TICKS = 120;

    private final List<BossPhaseDef> phases;
    /** Cells behind itself an imp is dropped at (kept for symmetry with HammerCapability). */
    private final float summonXSpawn;

    private int nextPhaseIndex;

    public BossPhasesCapability(List<BossPhaseDef> phases, float summonXSpawn) {
        this.phases = List.copyOf(phases);
        this.summonXSpawn = summonXSpawn;
    }

    public static final MapCodec<BossPhasesCapability> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            BossPhaseDef.CODEC.listOf().optionalFieldOf("phases", List.of())
                    .forGetter(BossPhasesCapability::phases),
            Codec.FLOAT.optionalFieldOf("summon_x_offset", 0.6F)
                    .forGetter(BossPhasesCapability::summonXSpawn)
    ).apply(i, BossPhasesCapability::new));

    public List<BossPhaseDef> phases() {
        return phases;
    }

    public float summonXSpawn() {
        return summonXSpawn;
    }

    @Override
    public ZombieCapability instantiate() {
        return new BossPhasesCapability(phases, summonXSpawn);
    }

    @Override
    public void tick(ZombieEntity zombie, LevelAccess level) {
        int maxHp = Math.max(1, zombie.def().health());
        while (nextPhaseIndex < phases.size()) {
            BossPhaseDef phase = phases.get(nextPhaseIndex);
            if (zombie.health() > maxHp * phase.atHp()) {
                break;
            }
            nextPhaseIndex++;
            int row = level.random().nextInt(Math.max(1, level.height()));
            for (Identifier summon : phase.summons()) {
                level.spawnZombie(summon, zombie.team(), level.width() + summonXSpawn, row);
            }
            switch (phase.ability()) {
                case "slam" -> {
                    level.damageArea(zombie.cellX(), zombie.cellY(), SLAM_RADIUS, SLAM_DAMAGE, zombie.team());
                    level.emitEffect("pvzce:ash_smoke", zombie.cellX(), zombie.cellY(),
                            zombie.def().sounds().special().orElse(PvzceSounds.ZOMBIE_BOSS_BOULDER));
                }
                case "charge" -> zombie.setSpeedBoost(CHARGE_TICKS);
                default -> {
                }
            }
        }
    }

    @Override
    public void save(CompoundTag tag) {
        tag.putInt("nextPhaseIndex", nextPhaseIndex);
    }

    @Override
    public void load(CompoundTag tag) {
        nextPhaseIndex = tag.getInt("nextPhaseIndex");
    }
}
