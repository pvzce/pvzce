package com.pvzce.client.particle;

import com.pvzce.client.PvzceClient;
import com.pvzce.common.PvzceConstants;

import java.util.ArrayList;
import java.util.List;

/** Minimal effect renderer: colored world-space quads with a short lifetime. */
public final class ParticleEngine {
    /** Authored gravity, in world cells per second squared. */
    public static final float GRAVITY_PER_SECOND = 0.15F * com.pvzce.common.PvzceConstants.TICKS_PER_SECOND;
    /** Longest step applied in one frame, so a stall cannot teleport particles. */
    public static final float MAX_STEP_SECONDS = 0.1F;

    private static final class Particle {
        String type;
        float x;
        float y;
        float vx;
        float vy;
        /** Elapsed lifetime in seconds. */
        float age;
        /** Authored lifetime in ticks at 60tps. */
        int life;
        float r;
        float g;
        float b;
    }

    private final List<Particle> particles = new ArrayList<>();

    public void spawn(String type, float x, float y) {
        Particle particle = new Particle();
        particle.type = type;
        particle.x = x;
        particle.y = y;
        particle.age = 0F;
        particle.life = 20;
        switch (type) {
            case "pvzce:hit_spark" -> {
                particle.life = 12;
                particle.r = 1F;
                particle.g = 0.9F;
                particle.b = 0.2F;
                particle.vx = 0.4F;
                particle.vy = 0.3F;
            }
            case "pvzce:ash_smoke" -> {
                particle.life = 28;
                particle.r = 0.25F;
                particle.g = 0.25F;
                particle.b = 0.25F;
                particle.vx = 0.1F;
                particle.vy = 0.2F;
            }
            case "pvzce:sun_glow", "pvzce:sparkle" -> {
                particle.life = 16;
                particle.r = 1F;
                particle.g = 0.85F;
                particle.b = 0.1F;
                particle.vy = 0.3F;
            }
            case "pvzce:splash", "pvzce:muzzle" -> {
                particle.life = 14;
                particle.r = 0.4F;
                particle.g = 0.8F;
                particle.b = 1F;
                particle.vx = 0.3F;
            }
            case "pvzce:chomp", "pvzce:bite" -> {
                particle.life = 14;
                particle.r = 0.9F;
                particle.g = 0.2F;
                particle.b = 0.2F;
            }
            case "pvzce:dirt", "pvzce:plant" -> {
                particle.life = 18;
                particle.r = 0.4F;
                particle.g = 0.75F;
                particle.b = 0.3F;
                particle.vy = 0.25F;
            }
            default -> {
                particle.life = 10;
                particle.r = 0.9F;
                particle.g = 0.4F;
                particle.b = 0.2F;
            }
        }
        particles.add(particle);
        while (particles.size() > 256) {
            particles.remove(0);
        }
    }

    /**
     * Advances every particle by {@code seconds}.
     *
     * <p>Velocities are authored per second and lifetimes were authored in ticks at
     * 60tps, so the step must be wall-clock based. The engine used to add a fixed
     * {@code 1/60} per <em>frame</em> and count age in frames, which made particles
     * twice as fast at the default 120 FPS cap, four times as fast at 240 FPS, and
     * slow on a 30 FPS machine. Age is kept in seconds and converted back to ticks
     * only for the authoring-friendly {@code life} field.
     */
    public void tick(float seconds) {
        if (seconds <= 0F) {
            return;
        }
        // A long frame (window drag, breakpoint) must not teleport particles.
        float dt = Math.min(seconds, MAX_STEP_SECONDS);
        for (Particle particle : particles) {
            particle.age += dt;
            particle.x += particle.vx * dt;
            particle.y += particle.vy * dt;
            particle.vy -= GRAVITY_PER_SECOND * dt;
        }
        particles.removeIf(p -> p.age >= p.life / (float) PvzceConstants.TICKS_PER_SECOND);
    }

    public void render(PvzceClient client) {
        for (Particle particle : particles) {
            float progress = Math.max(0F, Math.min(1F,
                    particle.age / (particle.life / (float) PvzceConstants.TICKS_PER_SECOND)));
            float size = 0.08F + 0.14F * (1F - progress);
            float alpha = 1F - progress;
            client.drawSolid(particle.x - size / 2F, particle.y - size / 2F, size, size, 0.4F,
                    particle.r, particle.g, particle.b, alpha);
        }
    }

    public void clear() {
        particles.clear();
    }

    /** Number of live particles (tests and the F3 overlay). */
    public int count() {
        return particles.size();
    }

    /** Position of a live particle as {x, y}, or {@code null} when out of range. */
    public float[] position(int index) {
        if (index < 0 || index >= particles.size()) {
            return null;
        }
        Particle particle = particles.get(index);
        return new float[]{particle.x, particle.y};
    }
}
