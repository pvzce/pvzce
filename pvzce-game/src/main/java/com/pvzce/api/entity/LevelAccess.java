package com.pvzce.api.entity;

import com.pvzce.common.level.WorldPosition;
import com.pvzce.api.content.ProjectileRef;
import com.pvzce.api.util.Identifier;
import com.pvzce.server.Team;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.entity.ZombieEntity;
import java.util.List;
import java.util.Random;

/**
 * The slice of a running level that server-side capabilities are allowed to
 * touch.
 *
 * <p>Capabilities, AI and commands are server-authoritative behaviour; this
 * interface is the single documented bridge between them and the level
 * implementation. {@code LevelServer} is the only production implementation, so
 * keeping the surface small means content cannot accidentally depend on level
 * internals.
 */
public interface LevelAccess {
    int width();

    int height();

    int tickCount();
    default float surfaceHeight(String surface, float x, float y) {
        SceneElementAccess element = sceneAt((int) Math.floor(x), (int) Math.floor(y), surface);
        return element == null ? 0F : element.heightAt(x, width());
    }
    default boolean obstructed(WorldPosition from, WorldPosition to) {
        return false;
    }
    default List<ZombieEntity> enemiesInRow(int row, Team team, String surface) {
        return enemiesInRow(row, team).stream().filter(z -> z.surfaceId().equals(surface)).toList();
    }
    default List<PlantEntity> plantsAt(int x, int y, String surface) {
        return plantsAt(x, y).stream().filter(p -> p.surfaceId().equals(surface)).toList();
    }
    default PlantEntity plantAt(int x, int y, String surface) {
        return plantsAt(x, y, surface).stream().filter(p -> !p.isRemoved())
                .max(java.util.Comparator.comparingInt(PlantEntity::layer)).orElse(null);
    }


    /** The movement multiplier of the mechanics affecting this zombie right now. */
    default float zombieSpeedMultiplier(ZombieEntity zombie) {
        return 1F;
    }

    /** The level's own {@link Random}; capabilities must never allocate their own. */
    Random random();

    /** Read-only view of the level's game-rule table. */
    GameRuleAccess rules();

    /**
     * Whether the level's own clock says it is night right now.
     *
     * <p>A question about the level rather than about a rule value: "is it night" is
     * {@code DayNightCycle}'s answer, and content that cares (a nocturnal mushroom
     * deciding whether to be asleep) must not re-derive it from {@code day_length} /
     * {@code night_length} and drift from the client's lighting.
     */
    boolean isNight();

    List<ZombieEntity> zombiesInRow(int row);

    /**
     * The zombies in one row that belong to somebody else.
     *
     * <p>The targeting question every plant's attack asks, and the reason it is on the level
     * rather than left to each caller: "who is an enemy" is a property of the <em>teams</em>,
     * and a hypno-shroomed zombie is on the plants' side is not something a shooter can see
     * from a zombie's id. A caller that wants every zombie in the row regardless of side (a
     * count, a renderer, the level's own win check) keeps using {@link #zombiesInRow}.
     */
    List<ZombieEntity> enemiesInRow(int row, Team team);

    /**
     * Every zombie on the board that belongs to somebody else.
     *
     * <p>For the things that are not aiming at a lane: the blover's gust crosses the whole lawn,
     * and "which of these are mine" is the same team question {@link #enemiesInRow} answers. A
     * caller that wants every zombie regardless of side keeps using {@code entities()}.
     */
    List<ZombieEntity> enemiesOf(Team team);

    /** The same blast footprint for damage and statuses, including friendly-fire filtering. */
    default List<ZombieEntity> enemiesInArea(Team sourceTeam, float x, float y, float radius, boolean square) {
        float limit = square ? radius + 0.5F : radius;
        return enemiesOf(sourceTeam).stream().filter(ZombieEntity::isAlive)
                .filter(z -> Math.abs(z.cellX() - x) <= limit && Math.abs(z.cellY() - y) <= limit).toList();
    }

    default List<ZombieEntity> enemiesInArea(Team team, WorldPosition center,
                                            float radius, boolean square) {
        return enemiesInArea(team, center.x(), center.y(), radius, square).stream()
                .filter(z -> reachedByBlast(center, z, radius, square)).toList();
    }
    default void damageArea(com.pvzce.api.content.DamageTypeDef type, WorldPosition center,
                            float radius, int damage, Team team, boolean square) {
        damageArea(type, center.x(), center.y(), radius, damage, team, square);
    }

    /** Bodies reached by a spatial blast. Ground/carrier elevation and intervening decks apply. */
    default boolean reachedByBlast(WorldPosition center,
                                   com.pvzce.api.entity.Entity target, float radius, boolean square) {
        float limit = radius + (square ? 0.5F : 0F);
        float dx = target.cellX() - center.x(), dy = target.cellY() - center.y();
        float dz = target.height() - center.elevation();
        boolean inRange = square ? Math.abs(dx) <= limit && Math.abs(dy) <= limit && Math.abs(dz) <= limit
                : dx * dx + dy * dy + dz * dz <= limit * limit;
        float body = com.pvzce.common.PvzceConstants.COMBAT_BODY_HEIGHT / 2F;
        return inRange && !obstructed(center.offset(0F, 0F, body), target.position().offset(0F, 0F, body));
    }

    default List<PlantEntity> plantsInArea(WorldPosition center, float radius, boolean square) {
        java.util.ArrayList<PlantEntity> result = new java.util.ArrayList<>();
        for (int x = 0; x < width(); x++) for (int y = 0; y < height(); y++)
            for (PlantEntity plant : plantsAt(x, y))
                if (!plant.isRemoved() && reachedByBlast(center, plant, radius, square)) result.add(plant);
        return List.copyOf(result);
    }

    List<PlantEntity> plantsAt(int column, int row);

    /**
     * The shots flying through one cell, as a copy.
     *
     * <p>For the one plant that acts on a shot already in the air rather than on a zombie: a
     * torchwood sets alight whatever crosses it. Everything else in the game either fires,
     * targets or is hit by a projectile; this is the only "touches one in flight" question.
     */
    List<com.pvzce.server.entity.ProjectileEntity> projectilesInCell(int column, int row);

    /** The topmost plant in a cell (what zombies bite and the shovel removes first). */
    PlantEntity plantAt(int column, int row);

    /** Read-only view of the scene element in a cell; {@code null} when out of bounds. */
    SceneElementAccess sceneAt(int column, int row);
    default SceneElementAccess sceneAt(int x, int y, String surface) { return sceneAt(x, y); }
    default List<com.pvzce.server.entity.ProjectileEntity> projectilesInCell(int x, int y, String surface) {
        return projectilesInCell(x, y).stream().filter(p -> p.surfaceId().equals(surface)).toList();
    }


    /**
     * How much further a shot from this plant flies because of the run's rules; 1 when nothing
     * applies.
     *
     * <p>On the level's interface rather than looked up by the capability, because the answer is
     * the level's (which buffs are on) and the capability has nothing to look it up with. The
     * shooters scale the {@link ProjectileRef} they <em>aim</em> with through this, which is what
     * keeps "is a zombie worth firing at" and "will the shot reach it" the same number.
     */
    default float sporeRangeMultiplier(PlantEntity plant) {
        return 1F;
    }

    /** Live weather only; action rates compose it with care and the level's normal rules. */
    default float weatherActionMultiplier(PlantEntity plant) { return 1F; }

    /** A mushroom's reach, shared by targeting and actual damage/projectile geometry. */
    default float weatherRangeMultiplier(PlantEntity plant) { return 1F; }

    /** Zero disables ignition; otherwise scales the resulting burning projectile's damage. */
    default float weatherTorchMultiplier() { return 1F; }

    /** Scales plant-side ash damage without changing the armour-bypass damage type. */
    default float weatherAshMultiplier() { return 1F; }

    /** Cold slow/freeze duration only; butter and other immobilisation keep their own duration. */
    default float weatherColdDurationMultiplier() { return 1F; }

    /** The footprint of a splash projectile that also carries cold. */
    default float weatherIcySplashMultiplier() { return 1F; }

    /** The live kernel-pult butter chance; the default preserves unbuffed shots. */
    default float butterChance(PlantEntity plant, float base) {
        return base;
    }

    /**
     * How many times a plant's volley is repeated: 1, 2 or 3.
     *
     * <p>The other half of the level's say over what a plant fires. Where
     * {@link #sporeRangeMultiplier} lengthens a shot, this multiplies the <em>count</em> - the
     * rhythm levels' energy bar buys it, and at the top of the bar a repeater fires six peas where
     * it fired two. Beside the range multiplier rather than inside it because the two are separate
     * facts about the same volley, and every capability that fires reads both.
     */
    default int projectileCountMultiplier(PlantEntity plant) {
        return 1;
    }

    default void blowFog(int ticks) { }

    void spawnProjectile(ProjectileRef ref, float x, float y, PlantEntity source);

    /**
     * A shot fired by a zombie, travelling left.
     *
     * <p>The mirror of {@link #spawnProjectile}: same projectile, same damage, same hit rules, and
     * the only difference is which way it goes and whose side it is on. It exists as its own
     * method rather than a sign on the plant one because the two are read at different places -
     * a plant's shot scales with the run's buffs and a zombie's does not - and folding them
     * together would mean every reader asking which side a number came from.
     */
    void spawnZombieProjectile(ProjectileRef ref, float x, float y,
                               com.pvzce.server.entity.ZombieEntity source);

    void spawnArcProjectile(ProjectileRef ref, float x, float y, PlantEntity source, ZombieEntity target);

    /** A basketball aimed at a plant, using the same arc solver as plant lobs. */
    default void spawnZombieArcProjectile(ProjectileRef ref, ZombieEntity source, PlantEntity target) {
    }

    void spawnResource(Identifier resourceId, int amount, float x, float y, Team team);

    /**
     * Spawns a resource that came out of a plant.
     *
     * <p>Same as {@link #spawnResource} except for how the drop arrives: a harvested
     * sun pops out of the flower that made it and settles back, rather than falling in
     * from above the lawn. The distinction belongs here rather than in the resource
     * definition because the same sun is both - the sky drops one and a sunflower
     * makes one.
     */
    default void spawnProducedResource(Identifier resourceId, int amount, float x, float y, Team team) {
        spawnProducedResource(resourceId, amount, x, y, team,
                com.pvzce.common.network.packet.EntitySpawnS2C.DEFAULT_SCALE);
    }

    /**
     * The same, for a producer whose drop is drawn at its own size.
     *
     * <p>A small sun-shroom makes a sun worth 15 and the original draws it smaller than the
     * 25 the sky drops, so the drop - not the resource - carries the factor: the producing
     * plant is the only thing that knows which of the two it just made. Presentation only;
     * the amount is what the player is paid.
     *
     * @param scale draw size multiplier on top of the resource's own {@code render_scale}
     */
    void spawnProducedResource(Identifier resourceId, int amount, float x, float y, Team team, float scale);

    default void spawnProducedResource(Identifier resourceId, int amount, float x, float y, Team team,
                                       float scale, String surface) {
        spawnProducedResource(resourceId, amount, x, y, team, scale);
    }

    default void spawnProducedResource(Identifier resourceId, int amount, float x, float y, Team team,
                                       float scale, float driftX, String surface) {
        spawnProducedResource(resourceId, amount, x, y, team, scale, driftX);
    }

    /** A batch drop's explicit sideways motion, keeping each independently collectible. */
    default void spawnProducedResource(Identifier resourceId, int amount, float x, float y, Team team,
                                       float scale, float driftX) {
        spawnProducedResource(resourceId, amount, x, y, team, scale);
    }

    /**
     * Puts a zombie on the field at {@code x} in {@code row}.
     *
     * <p>Returns what it created, like {@link #spawnPlant} does - the wave spawner has to be
     * able to follow the zombie it just released (an opening wave waits for it to die), and
     * an id is the only handle that survives it being removed from the entity list.
     * {@code null} when the id names no registered zombie.
     */
    ZombieEntity spawnZombie(Identifier zombieId, Team team, float x, int row);

    default ZombieEntity spawnZombie(Identifier zombieId, Team team, float x, int row, float healthScale) {
        return spawnZombie(zombieId, team, x, row);
    }

    default ZombieEntity spawnZombie(Identifier id, Team team, float x, int row, float healthScale, String surface) {
        return spawnZombie(id, team, x, row, healthScale);
    }
    default ZombieEntity spawnZombie(Identifier id, Team team, float x, int row, float healthScale, String surface,
                                     com.pvzce.api.entity.attribute.AttributeOverrides attributes) {
        ZombieEntity zombie = spawnZombie(id, team, x, row, healthScale, surface);
        if (zombie != null) zombie.applySpawnAttributes(attributes);
        return zombie;
    }
    default void meltIce(float x, float y, float radius, boolean square, String surface) { meltIce(x, y, radius, square); }
    default void leaveCraters(float x, float y, float radius, boolean square, String surface) { leaveCraters(x, y, radius, square); }
    default void emitRippleAt(int x, int y, float strength, String surface) { emitRippleAt(x, y, strength); }

    /**
     * Opens every container within {@code radius} cells of a point.
     *
     * <p>"Container" is the vase level's vocabulary: one of its scary pots, or one of the player's
     * own garden vases. What comes out is whatever was inside - a card as a packet on the lawn, a
     * zombie, a bundle of sun - exactly as if the player had clicked it open.
     *
     * <p>Its one caller is the jack-in-the-box zombie's blast, which is the original's own rule:
     * a jack-in-the-box that goes off among the pots takes them with it, and that is what makes
     * "which pot do I break first" the level's decision. Everyone else who wants a container open
     * has a player standing there to click it.
     */
    default void breakContainers(float centerX, float centerY, float radius) {
    }

    /**
     * Hits every zombie within {@code radius} cells of a point with one damage type.
     *
     * <p>The type is the caller's declaration of what kind of hit this is, and it is
     * required rather than defaulted: a blast that ignores armour and one that does not
     * look identical at the call site otherwise, which is exactly how "explosions go
     * through a bucket" used to be a fact about the method name instead of about the
     * content.
     */
    default void damageArea(com.pvzce.api.content.DamageTypeDef type, float centerX, float centerY,
                            float radius, int damage, Team sourceTeam) {
        damageArea(type, centerX, centerY, radius, damage, sourceTeam, false);
    }

    /**
     * The same hit, with a choice between a square footprint and a radial one.
     *
     * <p>{@code square} is how the original's ash line is authored: a cherry bomb covers the
     * nine cells around it, so the footprint is a square measured in cells and a zombie
     * straddling a cell edge is either inside it or not. A radial test on the same numbers
     * leaves the diagonal cells' corners outside, which is a hole a zombie can stand in -
     * visible in a 3x3 blast, where the four corners are exactly where the difference
     * shows. Radial is kept for the hits that are really a distance (a boss slam, a melon
     * splash), rather than a set of cells.
     */
    void damageArea(com.pvzce.api.content.DamageTypeDef type, float centerX, float centerY, float radius,
                    int damage, Team sourceTeam, boolean square);

    /**
     * Hits every zombie in one row, across the whole lawn.
     *
     * <p>The jalapeno's shape, and not expressible as a radius: a large enough square reaches
     * the rows beside it (a blast of radius {@code height} covers a board of any width and
     * takes its neighbours with it), while a radius that stops at the row's own edges stops
     * short of a lawn wider than the number someone wrote down. "Every zombie in this row" is
     * the fact, so it is the method.
     */
    void damageRow(com.pvzce.api.content.DamageTypeDef type, int row, int damage, Team sourceTeam);
    default void damageRow(com.pvzce.api.content.DamageTypeDef type, int row, int damage, Team team, String surface) {
        damageRow(type, row, damage, team);
    }
    default void meltIceRow(int row, String surface) { meltIceRow(row); }


    /**
     * Hits every zombie in one column, from the house to the road.
     *
     * <p>{@link #damageRow}'s twin, and the rhythm levels' other attack: a column is the shape that
     * reaches a lane's whole length the way a row reaches its whole width, and it is what makes the
     * two halves of the keyboard two different weapons rather than two colours of the same one.
     */
    void damageColumn(com.pvzce.api.content.DamageTypeDef type, int column, int damage,
                      Team sourceTeam);

    /**
     * Turns the ground a blast covered into craters.
     *
     * <p>What the original's doom shroom leaves behind: the lawn its blast covered stops being
     * lawn - nothing may be planted there - until the level's own {@code crater_recovery} has
     * run out. Only bare ground craters; water, roofs and anything already a hole are left
     * alone.
     *
     * <p>The footprint is read exactly as {@link #damageArea} reads it, so the hole is where the
     * damage was: a blast that covers seven cells across leaves seven cells of hole, and a
     * smaller bomb leaves a smaller one. The two are the same shape because they are the same
     * fact.
     */
    void leaveCraters(float centerX, float centerY, float radius, boolean square);

    /**
     * Takes the ice off the lawn a fire blast covered.
     *
     * <p>The other half of the zamboni's trail: ice is terrain with a clock (the level's
     * {@code ice_melt} rule), and fire ends that clock early. Read with the same footprint
     * {@link #leaveCraters} uses, so what burns is what the blast reached.
     *
     * <p>Its own entry point rather than a flag on {@code leaveCraters} because the two are
     * opposite statements about the same cells - a hole is the lawn gone, a melt is the lawn
     * coming back - and only fire makes the second one.
     */
    void meltIce(float centerX, float centerY, float radius, boolean square);

    /** The same, for a blast whose shape is one whole row rather than a circle (the jalapeno). */
    void meltIceRow(int row);

    void emitEffect(String particle, float x, float y, Identifier sound);

    default void emitEffect(String particle, WorldPosition position,
                            String surface, Identifier sound) {
        emitEffect(particle, position, surface, sound, 1F, 1F);
    }
    default void emitEffect(String particle, WorldPosition position,
                            String surface, Identifier sound, float volume, float pitch) {
        emitEffect(particle, position.x(), position.projectedY(), sound, volume, pitch);
    }


    /** Publishes an already-decided equipment transfer for the client to animate. */
    default void emitMagnetItem(int plantId, Identifier item, float x, float y,
                               int startTick, int pullTicks, int holdTicks) { }
    default void emitMagnetItem(int plantId, Identifier item, WorldPosition origin,
                               int startTick, int pullTicks, int holdTicks) {
        emitMagnetItem(plantId, item, origin.x(), origin.y(), startTick, pullTicks, holdTicks);
    }

    /**
     * The same, with the volume and pitch the sound is played at.
     *
     * <p>For a capability that fires the same event repeatedly within the client's own
     * repeat-folding window and wants each one heard - a bowled nut caroming through a crowd hits
     * every 100 ms, and the sound engine drops a repeat of the <em>same</em> event inside 130 ms.
     * Alternating two ids gets past that; a nudge on the pitch is what keeps a long chain from
     * reading as a loop.
     */
    void emitEffect(String particle, float x, float y, Identifier sound, float volume, float pitch);

    /**
     * Disturbs a liquid surface at a world position.
     *
     * <p>Ripples are presentation only: the server decides that something happened
     * and the client animates it, exactly like {@link #emitEffect}. A capability or
     * an entity that enters water calls this rather than touching the renderer,
     * which keeps the simulated world free of any rendering dependency.
     *
     * @param liquid   content id of the liquid to disturb, e.g. {@code pvzce:water};
     *                 an unknown or empty id means "no ripple"
     * @param strength 0..1, how hard the surface is disturbed
     */
    void emitRipple(Identifier liquid, float x, float y, float strength);

    /** Disturbs whatever liquid is at a cell, if any. */
    void emitRippleAt(int cellX, int cellY, float strength);

    void addEntity(Entity entity);

    /** A zombie walked off the left edge and stayed there long enough to lose the level. */
    void zombieReachedLeft(ZombieEntity zombie);

    /**
     * A zombie's body reached zero health and it was removed.
     *
     * <p>The level, not the zombie, decides what a death is worth: the coin drop is
     * level data ({@code rewards.coin_drop_chance}), and an entity has no business
     * reading the level's reward block. Mirrors {@link #zombieReachedLeft}, the
     * other "the simulation did something the level may care about" callback.
     */
    void zombieDied(ZombieEntity zombie);

    /**
     * Drops {@code count} of the level's own coins at a world position.
     *
     * <p>Which coin that is comes from the level's reward block
     * ({@code rewards.coin_drop}), so a capability can pay out for something the level
     * considers worth paying for - a bowling ricochet, say - without knowing the
     * currency. Mirrors {@link #zombieDied}: the level decides what an event is worth,
     * the ability only reports that it happened.
     */
    void dropCoin(float x, float y, int count);

    /**
     * Read-only view of a scene element used by placement and movement checks.
     *
     * <p>There is no {@code accepts(feet)} here any more: what a plant may be
     * planted on is answered by tags ({@code #c:water}, {@code #c:plantable}, ...)
     * through {@code PlantPlacement}, not by asking the element about a stack
     * class string.
     */
    interface SceneElementAccess {
        Identifier id();

        String surfaceClass();

        float heightAt(float worldX, int levelWidth);
    }

    /** Read-only view of the level's rule table. */
    interface GameRuleAccess {
        int getInt(Identifier id);

        float getFloat(Identifier id);

        boolean getBoolean(Identifier id);
    }
}
