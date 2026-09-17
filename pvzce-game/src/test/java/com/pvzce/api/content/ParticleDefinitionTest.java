package com.pvzce.api.content;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.pvzce.api.util.Identifier;
import com.pvzce.client.particle.ParticleEngine;
import com.pvzce.common.PvzceParticles;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.resource.PvzceDataLoader;
import com.pvzce.common.tag.TestContent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Particle definitions and the engine that plays them.
 *
 * <p>Both halves fail silently when they are wrong - a missing definition draws
 * nothing at all and a broken curve draws something plausible but wrong - so the
 * numbers that shape an effect are pinned here rather than eyeballed on screen.
 */
class ParticleDefinitionTest {
    private static ParticleDef parse(String json) {
        return ParticleDef.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
    }

    @Test
    void defaultsAreUsableWithoutAnyOptionalField() {
        ParticleDef def = parse("""
                {"id":"test:plain","look":{"texture":"test:textures/particles/plain"}}
                """);

        assertEquals(Identifier.of("test", "plain"), def.id());
        assertEquals(ParticleDef.DEFAULT_LIFETIME, def.look().lifetime(), 0.0001F);
        assertEquals(ParticleDef.DEFAULT_SCALE, def.look().scale(), 0.0001F);
        // A definition with no frames is one static sprite, never an animation.
        assertFalse(def.look().animated());
        assertEquals(List.of(Identifier.of("test", "textures/particles/plain")), def.look().allFrames());
        // Alpha fades out by default, which is what almost every effect wants.
        assertEquals(1F, def.look().alphaAt(0F), 0.0001F);
        assertEquals(0F, def.look().alphaAt(1F), 0.0001F);
        assertEquals(1, def.count());
        assertFalse(def.additive());
    }

    /**
     * The straight fade is the two endpoints; a curved one has to be a table.
     *
     * <p>The original's fades are not straight lines - a puff swells and then
     * vanishes, a glow pulses - and the converter emits a table only for those.
     */
    @Test
    void alphaCurveOverridesTheTwoEndpoints() {
        ParticleDef straight = parse("""
                {"id":"test:fade","look":{"texture":"t:t","alpha_from":1,"alpha_to":0}}
                """);
        // Halfway through a straight fade from 1 to 0.
        assertEquals(0.5F, straight.look().alphaAt(0.5F), 0.0001F);

        ParticleDef curved = parse("""
                {"id":"test:curve","look":{"texture":"t:t","alpha_from":1,"alpha_to":0,
                  "alpha_curve":[[0,0.2],[0.5,1.0],[1,0]]}}
                """);
        assertEquals(0.2F, curved.look().alphaAt(0F), 0.0001F);
        assertEquals(1.0F, curved.look().alphaAt(0.5F), 0.0001F);
        assertEquals(0.0F, curved.look().alphaAt(1F), 0.0001F);
        // Interpolated between the table's own points.
        assertEquals(0.6F, curved.look().alphaAt(0.25F), 0.0001F);
    }

    @Test
    void alphaAndScaleAreClampedOutsideTheTable() {
        ParticleDef def = parse("""
                {"id":"test:held","look":{"texture":"t:t",
                  "alpha_curve":[[0.5,1.0],[1.0,0.0]]}}
                """);
        // Before the table starts the first value holds, rather than falling back to
        // the implicit start and showing a fade-in nobody authored.
        assertEquals(1.0F, def.look().alphaAt(0F), 0.0001F);
        assertEquals(1.0F, def.look().alphaAt(0.5F), 0.0001F);
        assertEquals(0.0F, def.look().alphaAt(1.2F), 0.0001F);
    }

    @Test
    void scaleCurveMultipliesTheBaseSize() {
        ParticleDef growing = parse("""
                {"id":"test:grow","look":{"texture":"t:t","scale":0.5,
                  "scale_curve":[[0,0.2],[1,2.0]]}}
                """);
        assertEquals(0.5F, growing.look().scale(), 0.0001F);
        assertEquals(0.2F, growing.look().scaleAt(0F), 0.0001F);
        assertEquals(2.0F, growing.look().scaleAt(1F), 0.0001F);
        // Halfway between 0.2 and 2.0.
        assertEquals(1.1F, growing.look().scaleAt(0.5F), 0.0001F);
    }

    @Test
    void colorDefaultsToWhiteAndIsTrimmedToThreeChannels() {
        ParticleDef white = parse("""
                {"id":"test:white","look":{"texture":"t:t"}}
                """);
        assertEquals(1F, white.look().colorArray()[0], 0.0001F);

        ParticleDef tinted = parse("""
                {"id":"test:tint","look":{"texture":"t:t","color":[0.25,0.5,0.75,0.9]}}
                """);
        assertEquals(3, tinted.look().color().size(), "a fourth channel is dropped");
        assertEquals(0.5F, tinted.look().colorArray()[1], 0.0001F);
    }

    /** The registry has to know every particle; nothing else can resolve an id. */
    @Test
    void builtInParticlesLoadFromTheDataPack() throws Exception {
        TestContent.loadBuiltInContentAndTags();

        PvzceDataLoader.RegistryData<?> particles = PvzceDataLoader.CONTENT_REGISTRIES.stream()
                .filter(data -> "particles".equals(data.contentPath()))
                .findFirst()
                .orElse(null);
        assertNotNull(particles, "the particle registry must be in the content table");
        assertEquals("particle", particles.idPath());

        assertFalse(BuiltInRegistries.PARTICLES.keySet().isEmpty(),
                "the shipped particle definitions must load");
    }

    /**
     * The explosion flash has to grow.
     *
     * <p>{@code Pow.xml} authors two sizes - ``.2 .5,7`` - and the converter read the
     * three numbers as (value, time) keyframes, which flattened the ramp into "0.2 for
     * the whole life". The blast was therefore drawn at its *starting* size and never
     * expanded, which is what "只有一个很小的贴图" was: the curve was missing, not the
     * engine (it has carried {@code scale_curve} all along).
     *
     * <p>Asserted on the shipped definition rather than on a literal, so the guard is
     * against the data losing the ramp again.
     */
    @Test
    void theExplosionFlashGrowsOverItsLife() throws Exception {
        TestContent.loadBuiltInContentAndTags();
        ParticleDef pow = BuiltInRegistries.PARTICLES.get(PvzceParticles.EXPLOSION_POW);
        assertNotNull(pow, "pvzce:pow must be defined");

        float start = pow.look().scale() * pow.look().scaleAt(0F);
        float end = pow.look().scale() * pow.look().scaleAt(1F);
        assertTrue(end > start * 2F,
                "the flash must expand: " + start + " -> " + end + " cells");
    }

    /**
     * The head a lawn mower throws tumbles.
     *
     * <p>The original writes {@code ParticleSpinSpeed [-720 720]} for it, and the converter
     * kept only the midpoint of a range - zero, because the range is symmetric - so the head
     * crossed the lawn without turning at all: a solid object sliding left, which is what
     * "the mowed zombie's head looks wrong" was. The half-width travels as
     * {@code spin_spread} now and the engine rolls a speed per particle.
     */
    @Test
    void theMowedZombieHeadTumbles() throws Exception {
        TestContent.loadBuiltInContentAndTags();
        ParticleDef head = BuiltInRegistries.PARTICLES.get(PvzceParticles.MOWERED_ZOMBIE_HEAD);
        assertNotNull(head, "pvzce:mowered_zombie_head must be defined");

        float spin = Math.abs(head.look().spin());
        float spread = head.look().spinSpread();
        assertTrue(spread > 0F, "the head must be able to roll, not just face one way");
        assertTrue(spin + spread >= 180F,
                "and a mower throws it hard enough to read as a tumble: " + (spin + spread) + " deg/s");
    }

    /**
     * A hat knocked off a zombie actually falls to the lawn.
     *
     * <p>Two numbers decide whether the player sees anything at all, and both were wrong: the
     * piece was spawned at the zombie's *head* while its ground line stayed the default 0.42
     * cells below the spawn point, so it "landed" in mid-air just under the head and skittered
     * there for the half second it lived - a hat that vanishes rather than a hat that falls.
     * The ground line is the definition's own {@code ground_offset} now.
     */
    @Test
    void aKnockedOffHatFallsToTheLawn() throws Exception {
        TestContent.loadBuiltInContentAndTags();
        ParticleDef cone = BuiltInRegistries.PARTICLES.get(
                Identifier.of("pvzce", "zombie_traffic_cone"));
        assertNotNull(cone, "the cone a Conehead drops must be defined");

        // A zombie in row 0 stands at cell y 0.5; its head is about half a cell above that,
        // which is where ArmorCapability emits the debris.
        float head = 1.05F;
        float lawn = 0.14F;
        ParticleEngine engine = new ParticleEngine();
        engine.spawn(cone.id().toString(), 3F, head);

        // Still in the air half a second later: the drop has to be watchable, not a flash.
        tickSeconds(engine, 0.5F);
        assertEquals(1, engine.count(), "the hat is still falling after half a second");

        // And it comes to rest on the grass under the zombie, not on an invisible line just
        // below the head. Checked while it is still alive: its lifetime is 1.1s.
        tickSeconds(engine, 0.4F);
        assertEquals(1, engine.count(), "still on screen at 0.9s");
        float[] resting = engine.position(0);
        assertNotNull(resting);
        assertEquals(lawn, resting[1], 0.12F, "a fallen hat lies on the lawn: y=" + resting[1]);
    }

    /** Advances the engine in 60ths of a second, the rate its ages are authored against. */
    private static void tickSeconds(ParticleEngine engine, float seconds) {
        int steps = Math.round(seconds * 60F);
        for (int i = 0; i < steps; i++) {
            engine.tick(1F / 60F);
        }
    }

    /**
     * The engine must not invent particles for an id that does not exist.
     *
     * <p>It used to answer every unknown name with a red square, which made a typo
     * look like a real (if ugly) effect.
     */
    @Test
    void spawningAnUnknownEffectDrawsNothing() {
        ParticleEngine engine = new ParticleEngine();
        engine.spawn("pvzce:no_such_particle", 1F, 1F);
        assertEquals(0, engine.count());
        engine.spawn("not an id", 1F, 1F);
        assertEquals(0, engine.count());
        engine.spawn("", 1F, 1F);
        assertEquals(0, engine.count());
    }

    /**
     * One spawn makes as many particles as the definition asks for.
     *
     * <p>A definition with a spread may roll below its nominal count, so the
     * assertion is the authored range rather than an exact number.
     */
    @Test
    void oneSpawnMakesTheDefinitionsParticleCount() throws Exception {
        TestContent.loadBuiltInContentAndTags();
        ParticleDef def = BuiltInRegistries.PARTICLES.keySet().stream()
                .map(BuiltInRegistries.PARTICLES::get)
                .filter(java.util.Objects::nonNull)
                .filter(candidate -> candidate.count() >= 2)
                .findFirst()
                .orElseThrow();

        ParticleEngine engine = new ParticleEngine();
        engine.spawn(def.id().toString(), 3F, 4F);
        int low = Math.max(1, def.count() - def.countSpread());
        int high = def.count() + def.countSpread();
        assertTrue(engine.count() >= low && engine.count() <= high,
                def.id() + " spawned " + engine.count() + ", expected " + low + ".." + high);

        float[] position = engine.position(0);
        assertNotNull(position);
        // Particles are born at the anchor; motion only happens on tick.
        assertEquals(3F, position[0], 0.0001F);
        assertEquals(4F, position[1], 0.0001F);
    }

    /**
     * A particle disappears when its lifetime is up.
     *
     * <p>Age is wall-clock seconds, not frames: the previous engine counted frames, so
     * lifetimes ran at double speed on a 120 FPS machine.
     */
    /**
     * A particle disappears once its own lifetime has elapsed.
     *
     * <p>The shortest shipped effect is used rather than a fixed span, because the
     * shipped set includes a five-minute daisy that is meant to outlive any test.
     */
    @Test
    void particlesExpireAfterTheirLifetime() throws Exception {
        TestContent.loadBuiltInContentAndTags();
        ParticleEngine engine = new ParticleEngine();
        // An unregistered test id spawns nothing at all.
        ParticleDef shortLived = parse("""
                {"id":"test:short","look":{"texture":"t:t","life":0.2}}
                """);
        engine.spawn(shortLived.id().toString(), 0F, 0F);
        assertEquals(0, engine.count(), "an unregistered test id spawns nothing");

        ParticleDef shortest = BuiltInRegistries.PARTICLES.keySet().stream()
                .map(BuiltInRegistries.PARTICLES::get)
                .filter(java.util.Objects::nonNull)
                .min(java.util.Comparator.comparingDouble(def -> def.look().lifetime()))
                .orElseThrow();
        engine.spawn(shortest.id().toString(), 0F, 0F);
        assertTrue(engine.count() > 0, "the shortest shipped effect must spawn");
        // Steps are clamped to 0.1s, so advance in frames rather than one huge step,
        // for a little longer than the shortest lifetime.
        int steps = (int) Math.ceil(shortest.look().lifetime() / ParticleEngine.MAX_STEP_SECONDS) + 2;
        for (int step = 0; step < steps; step++) {
            engine.tick(ParticleEngine.MAX_STEP_SECONDS);
        }
        assertEquals(0, engine.count(), "particles must not outlive their lifetime");
    }

    /** A long frame is clamped, so a window drag cannot teleport a burst off-screen. */
    @Test
    void oneTickNeverMovesAParticleFurtherThanTheCap() throws Exception {
        TestContent.loadBuiltInContentAndTags();
        ParticleDef moving = BuiltInRegistries.PARTICLES.keySet().stream()
                .map(BuiltInRegistries.PARTICLES::get)
                .filter(def -> def != null && def.motion().speed() > 0F)
                .findFirst()
                .orElse(null);
        assertNotNull(moving, "at least one shipped particle must move");

        ParticleEngine engine = new ParticleEngine();
        engine.spawn(moving.id().toString(), 0F, 0F);
        engine.tick(5F);
        float[] position = engine.position(0);
        assertNotNull(position, "a clamped step must not expire the particle immediately");
        float travelled = Math.abs(position[0]) + Math.abs(position[1]);
        assertTrue(travelled <= moving.motion().speed() * ParticleEngine.MAX_STEP_SECONDS + 1F,
                "a 5 second frame moved the particle " + travelled + " cells");
    }

    @Test
    void clearRemovesEverythingAndInvalidateForgetsResolutions() throws Exception {
        TestContent.loadBuiltInContentAndTags();
        ParticleEngine engine = new ParticleEngine();
        for (Identifier id : BuiltInRegistries.PARTICLES.keySet()) {
            engine.spawn(id.toString(), 0F, 0F);
        }
        assertTrue(engine.count() > 0);
        engine.clear();
        assertEquals(0, engine.count());
        engine.invalidate();
        // Resolving again after an invalidate must still work.
        Identifier first = BuiltInRegistries.PARTICLES.keySet().iterator().next();
        engine.spawn(first.toString(), 0F, 0F);
        assertTrue(engine.count() > 0);
    }

    private static ParticleDef firstDefinition() {
        for (Identifier id : BuiltInRegistries.PARTICLES.keySet()) {
            ParticleDef def = BuiltInRegistries.PARTICLES.get(id);
            if (def != null) {
                return def;
            }
        }
        throw new IllegalStateException("no particle definitions loaded");
    }

}
