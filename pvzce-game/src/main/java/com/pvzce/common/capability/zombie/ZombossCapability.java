package com.pvzce.common.capability.zombie;

import com.mojang.serialization.MapCodec;
import com.pvzce.api.content.ZombieStatus;
import com.pvzce.api.content.capability.ZombieCapability;
import com.pvzce.api.entity.LevelAccess;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.PvzceSounds;
import com.pvzce.common.level.mechanic.MowerMechanic;
import com.pvzce.common.level.mechanic.ZombossMechanic.Status;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.server.level.LevelServer;

import java.util.List;

import static com.pvzce.common.PvzceConstants.*;

/** Stationary Zombot: timed attacks, a vulnerable lowered head and one rolling elemental ball.
 * Design reference: original Boss* actions, independently expressed in the 60 tps simulation.
 */
public final class ZombossCapability implements ZombieCapability {
    public static final MapCodec<ZombossCapability> CODEC = MapCodec.unit(ZombossCapability::new);
    private static final List<Identifier> SUMMONS = List.of(
            "conehead_zombie", "buckethead_zombie", "football_zombie", "pole_vaulter_zombie",
            "jack_in_the_box_zombie", "ladder", "zamboni_zombie", "catapult", "pogo_zombie",
            "newspaper_zombie", "door_zombie", "gargantuar").stream().map(PvzceIds::id).toList();

    private String phase = "enter";
    private int age, timer = ZOMBOSS_ENTER_TICKS, actionTicks = ZOMBOSS_ENTER_TICKS, mode;
    private int spawnLeft = ZOMBOSS_OPENING_SPAWN, headClock = ZOMBOSS_FIRST_HEAD;
    private int stompLeft = ZOMBOSS_STOMP_INTERVAL;
    private int bungeeLeft = ZOMBOSS_BUNGEE_INTERVAL;
    private int targetRow, targetColumn;
    /** 0 absent, 1 fire, 2 ice. Independent of the head's current action. */
    private int ballType, ballRow;
    private float ballX;

    @Override public ZombieCapability instantiate() { return new ZombossCapability(); }
    public boolean exposed() { return phase.startsWith("head_") && !phase.equals("head_leave"); }
    @Override public boolean acceptsDamage(ZombieEntity zombie) { return exposed(); }
    @Override public boolean acceptsStatus(ZombieEntity zombie, ZombieStatus status) {
        return exposed() && status != ZombieStatus.BUTTERED;
    }
    @Override public boolean targetableInRow(ZombieEntity zombie, int row) { return exposed(); }
    @Override public boolean canBeHitByGround(ZombieEntity zombie) { return exposed(); }
    @Override public boolean canBeHitByArc(ZombieEntity zombie) { return exposed(); }
    @Override public int layerOverride(ZombieEntity zombie) { return com.pvzce.api.entity.EntityLayers.AIR; }
    @Override public boolean tickMovement(ZombieEntity zombie, LevelAccess level) { return true; }

    public static List<Identifier> summonIds() { return SUMMONS; }

    public Status status(ZombieEntity zombie) {
        return new Status(zombie.health(), zombie.maxHealth(), phase, timer, targetColumn, targetRow,
                ballType, ballX, ballRow, zombie.frozen());
    }

    @Override
    public void tick(ZombieEntity zombie, LevelAccess access) {
        LevelServer level = (LevelServer) access;
        age++;
        if (age == 1) zombie.setAnimation("boss_enter");
        tickBall(level);
        if (zombie.frozen()) return;
        spawnLeft--; headClock--; stompLeft--; bungeeLeft--;
        if (--timer > 0) {
            contact(zombie, level);
            return;
        }
        switch (phase) {
            case "enter", "spawn", "stomp", "rv", "bungee_leave", "head_leave" -> idle(zombie);
            case "bungee" -> begin(zombie, "bungee_wait", 1, "boss_bungee");
            case "bungee_wait" -> {
                boolean stealing = level.entities().stream().anyMatch(e -> e instanceof ZombieEntity thief
                        && thief.isAlive() && thief.capability(BungeeCapability.class) != null);
                if (stealing) timer = 1;
                else begin(zombie, "bungee_leave", ZOMBOSS_BUNGEE_LEAVE_TICKS, "boss_bungee_leave");
            }
            case "head_enter" -> begin(zombie, "head_before", ZOMBOSS_HEAD_BEFORE_TICKS, "boss_head_idle");
            case "head_before" -> {
                targetRow = level.random().nextInt(level.height());
                begin(zombie, "head_spit", ZOMBOSS_SPIT_TICKS,
                        "boss_head_attack_" + (level.height() - targetRow));
            }
            case "head_spit" -> begin(zombie, "head_after", ZOMBOSS_HEAD_AFTER_TICKS, "boss_head_idle");
            case "head_after" -> begin(zombie, "head_leave", ZOMBOSS_HEAD_LEAVE_TICKS, "boss_head_leave");
            case "idle" -> chooseAttack(zombie, level);
            default -> throw new IllegalStateException("Unknown Zomboss phase " + phase);
        }
    }

    private void begin(ZombieEntity zombie, String phase, int duration, String animation) {
        this.phase = phase;
        if (phase.equals("head_enter")) {
            zombie.setCellX(ZOMBOSS_HEAD_X);
            zombie.setCellY(ZOMBOSS_HEAD_Y);
        }
        timer = actionTicks = duration;
        zombie.setAnimation(animation);
    }

    private void idle(ZombieEntity zombie) {
        phase = "idle";
        zombie.setCellX(ZOMBOSS_ANCHOR_X);
        zombie.setCellY(ZOMBOSS_ANCHOR_Y);
        timer = 1;
        zombie.setAnimation("boss_idle");
    }

    private void chooseAttack(ZombieEntity zombie, LevelServer level) {
        int damageMode = zombie.health() <= zombie.maxHealth() * ZOMBOSS_SECOND_DAMAGE ? 2
                : zombie.health() <= zombie.maxHealth() * ZOMBOSS_FIRST_DAMAGE ? 1 : 0;
        if (damageMode > mode) {
            mode = damageMode;
            startRaid(zombie, level, mode == 2);
        } else if (stompLeft <= 0 && findStompRow(level)) {
            stompLeft = ZOMBOSS_STOMP_INTERVAL + level.random().nextInt(ZOMBOSS_CLOCK_VARIATION);
            begin(zombie, "stomp", ZOMBOSS_STOMP_TICKS, "boss_stomp_" + (level.height() - 1 - targetRow));
        } else if (bungeeLeft <= 0) {
            startRaid(zombie, level, level.random().nextBoolean());
        } else if (headClock <= 0) {
            headClock = ZOMBOSS_HEAD_INTERVAL + level.random().nextInt(ZOMBOSS_CLOCK_VARIATION);
            begin(zombie, "head_enter", ZOMBOSS_HEAD_ENTER_TICKS, "boss_head_enter");
        } else if (spawnLeft <= 0) {
            targetRow = level.random().nextInt(level.height());
            int interval = mode == 0 ? ZOMBOSS_SPAWN_INTERVAL
                    : mode == 1 ? ZOMBOSS_SPAWN_HURT_INTERVAL : ZOMBOSS_SPAWN_DAMAGED_INTERVAL;
            spawnLeft = interval - ZOMBOSS_SPAWN_VARIATION / 2 + level.random().nextInt(ZOMBOSS_SPAWN_VARIATION);
            begin(zombie, "spawn", ZOMBOSS_SPAWN_TICKS, "boss_spawn_" + (level.height() - targetRow));
        } else timer = 1;
    }

    private void startRaid(ZombieEntity zombie, LevelServer level, boolean rv) {
        bungeeLeft = ZOMBOSS_BUNGEE_INTERVAL + level.random().nextInt(ZOMBOSS_CLOCK_VARIATION);
        if (rv) {
            targetColumn = level.random().nextInt(level.width() - 2);
            targetRow = level.random().nextInt(level.height() - 1);
            begin(zombie, "rv", ZOMBOSS_RV_TICKS, "boss_rv");
            level.emitEffect("", targetColumn + 1.5F, targetRow + 1F, PvzceIds.id("sfx/projectile/rvthrow"));
        } else begin(zombie, "bungee", ZOMBOSS_BUNGEE_TICKS, "boss_bungee");
    }

    private boolean findStompRow(LevelServer level) {
        List<PlantEntity> front = level.entities().stream()
                .filter(e -> e instanceof PlantEntity p && !p.isRemoved() && p.gridX() >= 5)
                .map(e -> (PlantEntity) e).toList();
        if (front.isEmpty()) return false;
        targetRow = Math.min(level.height() - 2, front.get(level.random().nextInt(front.size())).gridY());
        targetColumn = 5;
        return true;
    }

    /** Contacts occur halfway through the telegraphed action, once per action. */
    private void contact(ZombieEntity zombie, LevelServer level) {
        float fraction = switch (phase) {
            case "spawn" -> 0.6F;
            case "rv" -> ZOMBOSS_RV_CONTACT_FRACTION;
            case "bungee" -> 0.4F;
            case "head_spit" -> 0.42F;
            default -> 0.5F;
        };
        if (timer != actionTicks - Math.round(actionTicks * fraction)) return;
        switch (phase) {
            case "spawn" -> {
                Identifier id = age < ZOMBOSS_NORMAL_UNTIL ? PvzceIds.id("basic_zombie")
                        : age < ZOMBOSS_CONE_UNTIL ? PvzceIds.id("conehead_zombie")
                        : age < ZOMBOSS_BUCKET_UNTIL ? PvzceIds.id("buckethead_zombie")
                        : SUMMONS.get(level.random().nextInt(SUMMONS.size()));
                level.spawnZombie(id, zombie.team(), ZOMBOSS_SUMMON_X, targetRow);
            }
            case "head_spit" -> {
                ballType = level.random().nextBoolean() ? 1 : 2;
                ballX = ZOMBOSS_SUMMON_X;
                ballRow = targetRow;
                level.emitEffect("", ballX, ballRow + 0.5F, PvzceSounds.ZOMBIE_BOSS_BOULDER);
            }
            case "stomp" -> crush(level, 5, level.width(), targetRow, targetRow + 2);
            case "rv" -> crush(level, targetColumn, targetColumn + 3, targetRow, targetRow + 2);
            case "bungee" -> {
                for (int i = 0; i < ZOMBOSS_BUNGEE_COUNT; i++) {
                    ZombieEntity thief = level.spawnZombie(PvzceIds.id("bungee_zombie"), zombie.team(),
                            ZOMBOSS_SUMMON_X, level.random().nextInt(level.height()));
                    thief.capability(BungeeCapability.class).targetColumn(level.random().nextInt(level.width()));
                }
            }
            default -> { }
        }
    }

    private static void crush(LevelServer level, int x0, int x1, int y0, int y1) {
        for (var entity : level.entities()) {
            if (entity instanceof PlantEntity plant && !plant.isRemoved()
                    && plant.gridX() >= x0 && plant.gridX() < x1
                    && plant.gridY() >= y0 && plant.gridY() < y1) plant.remove();
        }
        level.emitEffect("pvzce:explosion_pow", (x0 + x1) / 2F, (y0 + y1) / 2F,
                PvzceSounds.ZOMBIE_GARGANTUAR_THUMP);
    }

    private void tickBall(LevelServer level) {
        if (ballType == 0) return;
        ballX -= ZOMBOSS_BALL_SPEED / TICKS_PER_SECOND;
        for (var entity : level.entities()) {
            if (entity instanceof PlantEntity plant && !plant.isRemoved() && plant.gridY() == ballRow
                    && Math.abs(plant.cellX() - ballX) <= ZOMBOSS_BALL_RADIUS) plant.remove();
        }
        MowerMechanic.Rig rig = level.mechanicStateOrNull(PvzceIds.MECHANIC_MOWER, MowerMechanic.Rig.class);
        if (rig != null) rig.crush(ballRow, ballX, ZOMBOSS_BALL_RADIUS);
        if (ballX < -1F) ballType = 0;
    }

    @Override public void onScreenFreeze(ZombieEntity zombie, LevelAccess level) {
        if (ballType == 1) destroyBall(level);
    }

    @Override public void onRowFire(ZombieEntity zombie, int row, LevelAccess level) {
        if (ballType == 2 && ballRow == row) destroyBall(level);
        if (exposed()) zombie.clearStatus(ZombieStatus.IMMOBILIZED);
    }

    private void destroyBall(LevelAccess level) {
        level.emitEffect("pvzce:explosion_pow", ballX, ballRow + 0.5F, PvzceSounds.EFFECT_FROZEN);
        ballType = 0;
    }

    @Override public int deathDurationTicks() { return ZOMBOSS_DEATH_TICKS; }

    @Override public void onDeath(ZombieEntity zombie, LevelAccess level) {
        phase = "death";
        ballType = 0;
        zombie.clearStatus(ZombieStatus.IMMOBILIZED);
        zombie.clearStatus(ZombieStatus.SLOW);
    }

    @Override public void save(CompoundTag tag) {
        tag.putString("phase", phase);
        tag.putInt("age", age); tag.putInt("timer", timer);
        tag.putInt("actionTicks", actionTicks); tag.putInt("mode", mode);
        tag.putInt("spawnLeft", spawnLeft); tag.putInt("headClock", headClock);
        tag.putInt("stompLeft", stompLeft); tag.putInt("bungeeLeft", bungeeLeft);
        tag.putInt("targetRow", targetRow); tag.putInt("targetColumn", targetColumn);
        tag.putInt("ballType", ballType); tag.putInt("ballRow", ballRow); tag.putFloat("ballX", ballX);
    }

    @Override public void load(CompoundTag tag) {
        if (!tag.contains("phase")) return;
        phase = tag.getString("phase"); age = tag.getInt("age"); timer = tag.getInt("timer");
        actionTicks = tag.getInt("actionTicks"); mode = tag.getInt("mode");
        spawnLeft = tag.getInt("spawnLeft"); headClock = tag.getInt("headClock");
        stompLeft = tag.getInt("stompLeft"); bungeeLeft = tag.getInt("bungeeLeft");
        targetRow = tag.getInt("targetRow"); targetColumn = tag.getInt("targetColumn");
        ballType = tag.getInt("ballType"); ballRow = tag.getInt("ballRow"); ballX = tag.getFloat("ballX");
    }
}
