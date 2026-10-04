package com.pvzce.common.level;

/** A board-plane position and absolute elevation. Render depth is a separate quantity. */
public record WorldPosition(float x, float y, float elevation) {
    public WorldPosition offset(float dx, float dy, float dz) {
        return new WorldPosition(x + dx, y + dy, elevation + dz);
    }

    /** Orthographic board projection, before the camera's pixel scale. */
    public float projectedY() { return y + elevation; }
}
