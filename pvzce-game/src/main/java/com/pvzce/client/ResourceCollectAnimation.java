package com.pvzce.client;

import com.pvzce.api.util.Identifier;

/**
 * Client-only fly-to-bank animation for a collected resource drop. The
 * server has already credited the resource; this object only keeps the drop's
 * former world position and icon alive long enough for the HUD to animate it.
 */
public final class ResourceCollectAnimation {
    public static final long DURATION_NANOS = 500_000_000L;

    private final int entityId;
    private final String resourceId;
    private final int amount;
    private final Identifier icon;
    private final float worldX;
    private final float worldY;
    private final float height;
    private final long startNanos;

    public ResourceCollectAnimation(int entityId, String resourceId, int amount, Identifier icon,
                                    float worldX, float worldY, float height) {
        this.entityId = entityId;
        this.resourceId = resourceId;
        this.amount = amount;
        this.icon = icon;
        this.worldX = worldX;
        this.worldY = worldY;
        this.height = height;
        this.startNanos = System.nanoTime();
    }

    public int entityId() {
        return entityId;
    }

    public String resourceId() {
        return resourceId;
    }

    public int amount() {
        return amount;
    }

    public Identifier icon() {
        return icon;
    }

    public float worldX() {
        return worldX;
    }

    public float worldY() {
        return worldY;
    }

    public float height() {
        return height;
    }

    /** 0 at spawn, 1 when the drop has fully arrived at the resource area. */
    public float progress(long nowNanos) {
        return Math.max(0F, Math.min(1F, (nowNanos - startNanos) / (float) DURATION_NANOS));
    }

    public boolean finished(long nowNanos) {
        return nowNanos - startNanos >= DURATION_NANOS;
    }
}
