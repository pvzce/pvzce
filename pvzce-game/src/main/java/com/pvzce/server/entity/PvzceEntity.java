package com.pvzce.server.entity;

import com.pvzce.api.entity.Entity;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.common.network.packet.EntitySpawnS2C;
import com.pvzce.common.network.packet.EntityUpdateS2C;
import com.pvzce.server.Team;
import com.pvzce.server.level.LevelServer;

/**
 * Server-authoritative entity base (plants, zombies, projectiles, sun drops).
 *
 * <p>All state that the client also needs lives in {@link Entity}; this class
 * only adds what is server-only: the owning team, the removal flag, the tick
 * entry point and symmetric save/restore.
 */
public abstract class PvzceEntity extends Entity {
    /**
     * Which side this entity is on.
     *
     * <p>Not final, because a zombie's side can change: the hypno-shroom charms one into
     * fighting for the plants, and "whose side is this" then has to change on the entity the
     * level already holds - removing and re-spawning it would break every reference to it (a
     * projectile in flight, the opening wave that is waiting for it to die, its own position).
     */
    protected Team team;
    protected boolean removed;

    /**
     * The tick a mutant blast dealt this entity its killing damage, or -1.
     *
     * <p>Not saved and not sent: it lives exactly as long as the question "did this death come
     * from a blast" needs an answer. A zombie dies <em>inside</em> the blast's own damage call,
     * so the flag is still set when its death reaches the mutation hooks; a plant dies at the end
     * of the same tick, and the tick number is what makes "the same tick" precise there. A
     * survivor is unmarked again by {@code MutantBlast} immediately after the damage, so a later
     * death from a pea is still an ordinary death.
     */
    private int blastDeathTick = -1;

    protected PvzceEntity(Identifier defId, Team team, float cellX, float cellY, int health) {
        super(defId, cellX, cellY, health);
        this.team = team;
    }

    public abstract void tick(LevelServer level);

    /** Marks this entity as killed by a blast on {@code tick}; see {@link #blastDeathTick}. */
    public void markBlastDeath(int tick) {
        this.blastDeathTick = tick;
    }

    /** Forgets the mark: the entity survived the blast after all. */
    public void clearBlastDeath() {
        this.blastDeathTick = -1;
    }

    /** True when the killing damage came from a blast on the level's current tick. */
    public boolean diedToBlast(int tick) {
        return blastDeathTick >= 0 && blastDeathTick == tick;
    }

    public Team team() {
        return team;
    }

    /**
     * Moves this entity to another side.
     *
     * <p>The one thing a charm does. Nothing else is reset here - what a side change means for
     * a given entity (a zombie's statuses, its speed boost, the animation it is playing) is the
     * caller's business, because only the caller knows what it is changing.
     */
    public void setTeam(Team team) {
        this.team = team;
    }

    public boolean isRemoved() {
        return removed;
    }

    public void remove() {
        removed = true;
    }

    @Override
    public void damage(int amount) {
        setHealth(Math.max(0, health() - amount));
        if (health() <= 0) {
            removed = true;
        }
    }

    public EntitySpawnS2C spawnPacket() {
        return new EntitySpawnS2C(id(), entityKind(), defId().toString(),
                team == null ? "" : team.id().toString(),
                cellX(), cellY(), layer(), health(), animation(), height(), armor(),
                chilled(), renderScale());
    }

    public EntityUpdateS2C updatePacket() {
        return new EntityUpdateS2C(id(), cellX(), cellY(), health(), animation(), height(),
                armor(), chilled(), charmed(), frozen(), teamIdForUpdate());
    }

    /**
     * Whether this entity has been turned against its own side; zombies override it.
     *
     * <p>Only a zombie can be charmed, so the base answer is false and the flag is one boolean
     * on the one update packet rather than a status list every entity would have to carry.
     */
    public boolean charmed() {
        return false;
    }

    /**
     * The side to publish in an update: the entity's own, and only while it is worth saying.
     *
     * <p>Streamed every tick rather than diffed against what was last sent. It is one short
     * string on a packet that already carries twelve fields and goes out at 20 Hz for a handful
     * of entities, and the alternative - the level remembering what it last told the client
     * about each entity's side - is state that exists only to save a few bytes and can get out
     * of step after a reconnect.
     */
    protected String teamIdForUpdate() {
        return team == null ? EntityUpdateS2C.NO_TEAM : team.id().toString();
    }

    /**
     * Whether this entity is currently under a slowing effect.
     *
     * <p>Only the zombie overrides it, and only this side can answer: a status is server
     * state. The client draws the frozen look from it, which is why it travels with the
     * entity's other visible state rather than being guessed at from the animation.
     */
    public boolean chilled() {
        return false;
    }

    /**
     * Whether this entity is held solid right now (the ice-shroom's freeze).
     *
     * <p>Distinct from {@link #chilled()}, which is "moving slower": a frozen zombie does not
     * move at all and its animation is stopped, and the client draws both from this flag. Only
     * the zombie overrides it.
     */
    public boolean frozen() {
        return false;
    }

    /**
     * How much bigger than its art this entity is drawn, on top of the definition's own
     * {@code render_scale}.
     *
     * <p>{@link EntitySpawnS2C#DEFAULT_SCALE} for everything that is drawn at the size its
     * definition declares - which is every entity whose size is content rather than state.
     * A produced sun is the exception: the same resource is worth 15 and drawn small out of
     * a small sun-shroom, so the <em>drop</em> carries the factor.
     */
    public float renderScale() {
        return EntitySpawnS2C.DEFAULT_SCALE;
    }

    /**
     * Remaining armour, or {@link EntitySpawnS2C#NO_ARMOR} for anything that wears none.
     *
     * <p>Only the zombie overrides this. It travels because the client draws a worn cone or
     * bucket from it and cannot work it out from the body's health - armour absorbs damage
     * the body never sees.
     */
    public int armor() {
        return EntitySpawnS2C.NO_ARMOR;
    }

    /**
     * Per-entity state that is not part of its content definition. Every entity
     * implements this so save/restore is symmetric by construction - previously
     * zombies round-tripped fully while plants silently lost their timers,
     * stacking height and sub-cell position.
     */
    public abstract CompoundTag saveState();

    /** Restores a tag produced by {@link #saveState()} on a freshly created entity. */
    public abstract void restoreState(CompoundTag tag);

    /** Writes the fields every entity shares; subclasses call this first. */
    protected CompoundTag saveBaseState() {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", defId().toString());
        tag.putFloat("x", cellX());
        tag.putFloat("y", cellY());
        tag.putFloat("height", height());
        tag.putInt("health", health());
        tag.putString("animation", animation());
        return tag;
    }

    /** Reads the fields written by {@link #saveBaseState()}. */
    protected void restoreBaseState(CompoundTag tag) {
        setCellX(tag.getFloat("x"));
        setCellY(tag.getFloat("y"));
        setHeight(tag.getFloat("height"));
        setHealth(tag.getInt("health"));
        setAnimation(tag.getString("animation"));
    }

    /**
     * Reads everything {@link #saveBaseState()} wrote <em>except</em> the position.
     *
     * <p>For the glove: a moved plant is re-spawned at its new cell and then has its state
     * put back, and restoring {@code x}/{@code y} as well would move it straight back to
     * where it came from - which is exactly what happened, with the id copied as well so
     * the cell it landed in looked empty.
     *
     * <p>Height is left alone too: it belongs to the new cell's terrain and the plant's
     * new place in its stack, both of which {@code spawnPlant} has already worked out.
     */
    protected void restoreStateWithoutPosition(CompoundTag tag) {
        setHealth(tag.getInt("health"));
        setAnimation(tag.getString("animation"));
    }
}
