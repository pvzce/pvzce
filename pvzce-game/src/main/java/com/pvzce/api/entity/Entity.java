package com.pvzce.api.entity;

import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceConstants;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared client/server entity state.
 *
 * <p>The server owns the authoritative values and the client applies whatever
 * the network sends into the same fields; both sides therefore agree on grid
 * derivation, layer semantics and animation naming without keeping two parallel
 * copies of the same bookkeeping. Subclasses add the side-specific pieces
 * (authoritative ticking on the server, animation playback on the client).
 */
public abstract class Entity {
    private static final AtomicInteger NEXT_ID = new AtomicInteger(1);

    protected final int id;
    protected final Identifier defId;
    protected float cellX;
    protected float cellY;
    protected float height;
    protected int health;
    protected String animation = EntityAnimations.IDLE;

    /**
     * Cell bounds used by {@link #gridX()}/{@link #gridY()}. Defaults to the
     * standard lawn so entities are usable before a level adopts them, but every
     * level sets its own size through {@link #setGridBounds(int, int)} so custom
     * (larger or smaller) levels are never clamped to the default 9x5 board.
     */
    private int gridWidth = PvzceConstants.DEFAULT_GRID_WIDTH;
    private int gridHeight = PvzceConstants.DEFAULT_GRID_HEIGHT;

    protected Entity(Identifier defId, float cellX, float cellY, int health) {
        this(NEXT_ID.getAndIncrement(), defId, cellX, cellY, health);
    }

    /**
     * Creates an entity with an explicit id. The client uses this to adopt the
     * server-assigned id so both sides agree on entity identity without keeping a
     * separate id map.
     */
    protected Entity(int id, Identifier defId, float cellX, float cellY, int health) {
        this.id = id;
        this.defId = defId;
        this.cellX = cellX;
        this.cellY = cellY;
        this.health = health;
    }

    /** Wire kind; one of the {@link EntityKind} constants. */
    public abstract String entityKind();

    /** Render/logic layer; one of the {@link EntityLayers} constants. */
    public abstract int layer();

    public int id() {
        return id;
    }

    public Identifier defId() {
        return defId;
    }

    public float cellX() {
        return cellX;
    }

    public float cellY() {
        return cellY;
    }

    public void setCellX(float cellX) {
        this.cellX = cellX;
    }

    public void setCellY(float cellY) {
        this.cellY = cellY;
    }

    /** Vertical offset above the cell floor, in world cells. */
    public float height() {
        return height;
    }

    public void setHeight(float height) {
        this.height = height;
    }

    public int health() {
        return health;
    }

    protected void setHealth(int health) {
        this.health = health;
    }

    public String animation() {
        return animation;
    }

    public void setAnimation(String animation) {
        this.animation = animation == null ? EntityAnimations.IDLE : animation;
    }

    /** Adopts the owning level's size so grid derivation stays inside the board. */
    public void setGridBounds(int width, int height) {
        this.gridWidth = Math.max(1, width);
        this.gridHeight = Math.max(1, height);
    }

    public int gridWidth() {
        return gridWidth;
    }

    public int gridHeight() {
        return gridHeight;
    }

    public int gridX() {
        return clamp(Math.floor(cellX), gridWidth);
    }

    public int gridY() {
        return clamp(Math.floor(cellY), gridHeight);
    }

    private static int clamp(double cell, int size) {
        int value = (int) Math.floor(cell);
        return Math.max(0, Math.min(size - 1, value));
    }

    public void damage(int amount) {
        setHealth(Math.max(0, health - amount));
    }
}
