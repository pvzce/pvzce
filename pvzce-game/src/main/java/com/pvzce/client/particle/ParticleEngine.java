package com.pvzce.client.particle;

import com.pvzce.api.content.ParticleDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.renderer.RenderSystem;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.util.MathUtil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * World-space effect particles, driven by {@link ParticleDef} content.
 *
 * <p>Every particle is one textured quad. Effects that carry a fade curve or a
 * frame sequence animate themselves from the wall clock, and the whole pass is
 * batched by blend mode so a glow does not force a state change per particle.
 *
 * <p>This replaced a renderer that could only draw a solid coloured square from a
 * hardcoded switch on the effect name: 81 particle sprites and 112 emitter scripts
 * of original art were sitting unused because there was no way to describe a
 * textured particle at all.
 *
 * <p>Particle motion is measured in world cells and seconds. Sizes are in cells too,
 * so a particle keeps its proportions when the board is rescaled.
 */
public final class ParticleEngine {
    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("PVZCE/Particles");
    /** Longest step applied in one frame, so a stall cannot teleport particles. */
    public static final float MAX_STEP_SECONDS = 0.1F;
    /** Hard cap on live particles; the oldest are dropped first. */
    public static final int MAX_PARTICLES = 1024;
    /** Particles draw above entities but below GUI text. */
    private static final float PARTICLE_Z = 0.5F;

    private static final class Particle {
        ParticleDef def;
        ParticleDef.ParticleLook look;
        ParticleDef.ParticleMotion motion;
        float x;
        float y;
        float vx;
        float vy;
        /** World y of the ground line this particle stops at, or NaN when it ignores it. */
        float groundY;
        /** Elapsed lifetime in seconds. */
        float age;
        float lifetime;
        float scale;
        float angle;
        float spin;
        float alphaFrom;
        float alphaTo;
    }

    private final List<Particle> particles = new ArrayList<>();
    private final Map<Identifier, ParticleDef> resolved = new HashMap<>();
    private final Random random = new Random();
    private long lastMissingLogNanos;

    /** Spawns one burst of {@code type} at a world position. */
    public void spawn(String type, float x, float y) {
        spawn(type, x, y, 1F);
    }

    /**
     * Spawns {@code type} at a world position.
     *
     * @param amount multiplier on the definition's own particle count, for effects
     *               whose size scales with what caused them (a bigger splash)
     */
    public void spawn(String type, float x, float y, float amount) {
        ParticleDef def = definition(type);
        if (def == null) {
            return;
        }
        Identifier id = Identifier.tryParse(type);
        if (id == null) {
            return;
        }
        int count = def.count() + (def.countSpread() > 0 ? random.nextInt(def.countSpread() * 2 + 1)
                - def.countSpread() : 0);
        count = Math.max(1, Math.round(count * Math.max(0.1F, amount)));
        for (int i = 0; i < count; i++) {
            particles.add(instantiate(def, x, y));
        }
        while (particles.size() > MAX_PARTICLES) {
            particles.remove(0);
        }
    }

    private Particle instantiate(ParticleDef def, float x, float y) {
        if (Boolean.getBoolean("pvzce.traceEffects")) {
            LOGGER.info("particle trace: spawn {} at {},{}", def.id(), x, y);
        }
        ParticleDef.ParticleLook look = def.look();
        ParticleDef.ParticleMotion motion = def.motion();
        Particle particle = new Particle();
        particle.def = def;
        particle.look = look;
        particle.motion = motion;
        particle.x = x;
        particle.y = y;
        particle.age = 0F;
        particle.lifetime = Math.max(0.02F, look.lifetime());
        float speed = motion.speed() + spread(motion.speedSpread());
        float angle = (float) Math.toRadians(motion.angle() + spread(motion.angleSpread()));
        particle.vx = (float) Math.cos(angle) * speed;
        particle.vy = (float) Math.sin(angle) * speed;
        // Fixed at birth: the ground belongs to where the particle came from, not to
        // where it has drifted to.
        // Relative, not absolute: a thrown cone or a zombie's arm is spawned in its own cell
        // and has to land on the lawn *under that cell*, whatever row that is. How far below
        // is the definition's own number, because the spawn point is not always the ground -
        // a hat leaves the zombie's head (see ParticleMotion#groundOffset).
        particle.groundY = motion.bounce() ? y - motion.groundOffset() : Float.NaN;
        particle.scale = Math.max(0.01F, look.scale() + spread(look.scaleSpread()));
        particle.angle = look.randomSpin() ? random.nextFloat() * 360F : 0F;
        // Rolled here with the rest of the birth state: the original's emitters name a
        // *range* of spin speeds ([-720 720] for a head the lawn mower threw), and taking
        // the midpoint of a symmetric range is zero - a solid object that slides instead of
        // tumbling. See ParticleLook#spinSpread.
        particle.spin = look.spin() + spread(look.spinSpread());
        return particle;
    }

    private float spread(float amount) {
        return amount <= 0F ? 0F : (random.nextFloat() * 2F - 1F) * amount;
    }

    /**
     * The definition behind an effect id.
     *
     * <p>An unknown id is reported once per second rather than per spawn: a missing
     * definition used to be silently invisible, and the emitter that referenced it was
     * impossible to find from the symptom.
     */
    private ParticleDef definition(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        Identifier id = Identifier.tryParse(type);
        if (id == null) {
            return null;
        }
        ParticleDef cached = resolved.get(id);
        if (cached != null) {
            return cached;
        }
        ParticleDef def = BuiltInRegistries.PARTICLES.get(id);
        if (def == null) {
            long now = System.nanoTime();
            if (now - lastMissingLogNanos > 1_000_000_000L) {
                lastMissingLogNanos = now;
                LOGGER.warn("Effect '{}' has no particle definition; nothing is drawn."
                        + " Add data/<ns>/particles/<name>.json.", id);
            }
            return null;
        }
        resolved.put(id, def);
        return def;
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
            ParticleDef.ParticleMotion motion = particle.motion;
            particle.x += particle.vx * dt;
            particle.y += particle.vy * dt;
            particle.vy -= motion.gravity() * dt;
            if (motion.drag() > 0F) {
                float damping = Math.max(0F, 1F - motion.drag() * dt);
                particle.vx *= damping;
                particle.vy *= damping;
            }
            particle.angle += particle.spin * dt;
            if (motion.bounce() && particle.y < particle.groundY && particle.vy < 0F) {
                particle.y = particle.groundY;
                particle.vy = 0F;
                // The ground is the only thing here that touches horizontal speed. Without
                // this the particle keeps its launch vx forever once it lands and slides
                // off the board in a straight line - which is exactly what a thrown head
                // did, ending up at the screen's left edge instead of dropping where it
                // fell. Definitions that want the old skitter keep the default of 1.
                particle.vx *= motion.groundFriction();
            }
        }
        particles.removeIf(particle -> particle.age >= particle.lifetime);
    }

    /**
     * Draws every live particle.
     *
     * <p>Two passes so the blend state changes at most twice per frame: ordinary
     * particles first, then the additive ones. Without the split, a single glow in the
     * middle of a burst would flip the blend mode once per particle.
     */
    public void render(PvzceClient client) {
        if (particles.isEmpty()) {
            return;
        }
        renderPass(client, false);
        renderPass(client, true);
        RenderSystem.blendNormal();
    }

    private void renderPass(PvzceClient client, boolean additive) {
        boolean active = false;
        for (Particle particle : particles) {
            if (particle.def.additive() != additive) {
                continue;
            }
            if (!active) {
                if (additive) {
                    RenderSystem.blendAdditive();
                }
                active = true;
            }
            renderOne(client, particle);
        }
    }

    private void renderOne(PvzceClient client, Particle particle) {
        ParticleDef.ParticleLook look = particle.look;
        float progress = Math.min(1F, particle.age / particle.lifetime);
        float alpha = MathUtil.clamp01(look.alphaAt(progress) * fadeIn(progress));
        if (alpha <= 0.001F) {
            return;
        }
        float size = particle.scale * look.scaleAt(progress);
        if (size <= 0F) {
            return;
        }
        float[] tint = look.colorArray();
        Identifier texture = frameTexture(look, progress);
        float half = size / 2F;
        float x = particle.x;
        float y = particle.y;

        if (particle.angle == 0F) {
            client.drawTexture(texture, x - half, y - half, size, size, PARTICLE_Z,
                    tint[0], tint[1], tint[2], alpha);
            return;
        }
        // A rotated particle needs all four corners placed; the axis-aligned path
        // above stays because almost every particle is unrotated.
        double radians = Math.toRadians(particle.angle);
        float cos = (float) Math.cos(radians) * half;
        float sin = (float) Math.sin(radians) * half;
        client.drawTextureQuad(texture,
                x - cos + sin, y - sin - cos,
                x + cos + sin, y + sin - cos,
                x + cos - sin, y + sin + cos,
                x - cos - sin, y - sin + cos,
                0F, 1F, 1F, 1F, 1F, 0F, 0F, 0F,
                PARTICLE_Z, tint[0], tint[1], tint[2], alpha);
    }

    /**
     * A short fade-in over the first frames.
     *
     * <p>Spawning at full alpha makes a burst read as a flash of hard-edged squares
     * for one frame; the original's emitters ramp up too.
     */
    private static float fadeIn(float progress) {
        final float ramp = 0.08F;
        return progress >= ramp ? 1F : progress / ramp;
    }

    private Identifier frameTexture(ParticleDef.ParticleLook look, float progress) {
        List<Identifier> frames = look.allFrames();
        if (!look.animated()) {
            return look.texture();
        }
        int index = (int) (progress * look.lifetime() * look.framesPerSecond());
        if (look.loop()) {
            index %= frames.size();
        } else {
            index = Math.min(index, frames.size() - 1);
        }
        return frames.get(Math.max(0, index));
    }

    public void clear() {
        particles.clear();
    }

    /** Forgets resolved definitions; called on a resource reload. */
    public void invalidate() {
        resolved.clear();
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

    /** The definition a live particle uses, or {@code null} when out of range. */
    public Identifier definitionId(int index) {
        if (index < 0 || index >= particles.size()) {
            return null;
        }
        return particles.get(index).def.id();
    }
}
