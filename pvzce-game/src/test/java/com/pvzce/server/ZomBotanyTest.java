package com.pvzce.server;

import com.pvzce.api.content.PlantDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.tag.TestContent;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.server.level.LevelServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ZomBotany four: zombies that shoot back, wear armour, and go off when they die.
 *
 * <p>Their art is composed rather than converted (see {@code tools/make_zombotany_assets.py}), so
 * one thing worth pinning is that the composition produced a file the engine can actually play -
 * every state the capabilities publish has to have a clip, or the zombie stands still while
 * shooting. The rest is behaviour: a pea head really does damage a plant, a wall-nut head really
 * does have to be chewed through, and a jalapeno head really is bounded by the plant ceiling.
 */
class ZomBotanyTest {
    private static final Identifier PLANT_TEAM = PvzceIds.PLANT_TEAM;
    private static final Identifier ZOMBIE_TEAM = PvzceIds.ZOMBIE_TEAM;

    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
    }

    private static LevelServer lawn() {
        java.util.Map<Identifier, List<String>> scene = new java.util.LinkedHashMap<>();
        List<String> grass = new java.util.ArrayList<>();
        for (int y = 0; y < 5; y++) {
            for (int x = 0; x < 9; x++) {
                grass.add(x + "," + y);
            }
        }
        scene.put(PvzceIds.GRASS, grass);
        var base = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/1_1"));
        return new LevelServer(new com.pvzce.api.content.LevelDef(
                base.id(), base.name(), base.description(), 9, 5, scene, base.teams(),
                base.winTeam(), base.rules(), base.envVars(), List.of(),
                base.waveIntervalEndMultiplier(), base.slots(), base.unlockResources(),
                base.initialSun(), base.music(), base.initialEntities()));
    }

    private static ZombieEntity spawn(LevelServer level, String id, float x, int row) {
        ZombieEntity zombie = level.spawnZombie(Identifier.withDefaultNamespace(id),
                level.team(ZOMBIE_TEAM), x, row);
        level.flushPending(packet -> { });
        assertNotNull(zombie, id + " has to be spawnable");
        return zombie;
    }

    private static PlantEntity place(LevelServer level, String id, int x, int y) {
        PlantDef def = BuiltInRegistries.PLANTS.get(Identifier.withDefaultNamespace(id));
        assertNotNull(def);
        PlantEntity plant = level.spawnPlant(def, level.team(PLANT_TEAM), x, y);
        level.flushPending(packet -> { });
        assertNotNull(plant);
        return plant;
    }

    private static void tick(LevelServer level, int ticks) {
        for (int i = 0; i < ticks; i++) {
            level.tick(packet -> { });
        }
    }

    /** All four are registered with their own art, and each has the piece that makes it itself. */
    @Test
    void allFourAreContent() {
        assertTrue(hasCapability("zombotany_pea_zombie",
                com.pvzce.common.capability.zombie.ZombieShooterCapability.class));
        assertTrue(hasCapability("zombotany_gatling_zombie",
                com.pvzce.common.capability.zombie.ZombieShooterCapability.class));
        assertTrue(hasCapability("zombotany_wallnut_zombie",
                com.pvzce.common.capability.zombie.ArmorCapability.class));
        assertTrue(hasCapability("zombotany_jalapeno_zombie",
                com.pvzce.common.capability.zombie.DeathBlastCapability.class));

        var gatling = BuiltInRegistries.ZOMBIES.get(
                Identifier.withDefaultNamespace("zombotany_gatling_zombie"));
        var pea = BuiltInRegistries.ZOMBIES.get(
                Identifier.withDefaultNamespace("zombotany_pea_zombie"));
        int gatlingCount = gatling.capabilities().stream()
                .filter(capability -> capability.value()
                        instanceof com.pvzce.common.capability.zombie.ZombieShooterCapability)
                .map(capability -> (com.pvzce.common.capability.zombie.ZombieShooterCapability)
                        capability.value())
                .mapToInt(com.pvzce.common.capability.zombie.ZombieShooterCapability::count)
                .max().orElse(0);
        int peaCount = pea.capabilities().stream()
                .filter(capability -> capability.value()
                        instanceof com.pvzce.common.capability.zombie.ZombieShooterCapability)
                .map(capability -> (com.pvzce.common.capability.zombie.ZombieShooterCapability)
                        capability.value())
                .mapToInt(com.pvzce.common.capability.zombie.ZombieShooterCapability::count)
                .max().orElse(0);
        assertTrue(gatlingCount > peaCount,
                "the gatling head's whole identity is firing more than the pea head: "
                        + gatlingCount + " against " + peaCount);
    }

    private static boolean hasCapability(String zombie, Class<?> type) {
        var def = BuiltInRegistries.ZOMBIES.get(Identifier.withDefaultNamespace(zombie));
        assertNotNull(def, zombie + " has to be registered");
        return def.capabilities().stream().anyMatch(capability -> type.isInstance(capability.value()));
    }

    /**
     * Every state these zombies can be put into has a clip.
     *
     * <p>The one failure the composed art could produce without anybody noticing: a capability
     * publishes {@code shoot}, the file has no such clip, and the client falls back to {@code idle}
     * with a log line - so a zombie fires peas while standing perfectly still. The synthesis script
     * copies {@code eat} into {@code shoot} for exactly this reason, and this is the assertion that
     * keeps it honest.
     */
    @Test
    void everyStateTheyCanBePutIntoHasAClip() throws Exception {
        String[] states = {"idle", "walk", "eat", "shoot", "death", "death2", "death_water"};
        for (String zombie : new String[]{"zombotany_pea_zombie", "zombotany_wallnut_zombie",
                "zombotany_gatling_zombie", "zombotany_jalapeno_zombie"}) {
            var fileId = com.pvzce.common.core.EntityArt.animationFile(
                    Identifier.withDefaultNamespace(zombie));
            assertNotNull(fileId, zombie + " has to resolve to an animation file");
            String resource = "/assets/" + fileId.namespace() + "/animations/" + fileId.path()
                    + ".json";
            try (var stream = ZomBotanyTest.class.getResourceAsStream(resource)) {
                assertNotNull(stream, "missing animation file " + resource);
                var json = com.google.gson.JsonParser.parseReader(
                        new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8))
                        .getAsJsonObject();
                var clips = json.getAsJsonObject("animations").keySet();
                for (String state : states) {
                    assertTrue(clips.contains(state),
                            zombie + " has no '" + state + "' clip, so that state would silently"
                                    + " play as idle; it has " + clips);
                }
                var bones = json.getAsJsonObject("model").getAsJsonArray("bones");
                boolean hasPlantHead = false;
                for (var bone : bones) {
                    if ("plant_head".equals(bone.getAsJsonObject().get("name").getAsString())) {
                        hasPlantHead = true;
                        assertEquals("head",
                                bone.getAsJsonObject().get("parent").getAsString(),
                                "the plant has to be parented to the head, or it would not follow"
                                        + " it");
                    }
                }
                assertTrue(hasPlantHead, zombie + " has to carry the plant head bone");

                // The plant *is* the head, so the zombie's own face is hidden in every clip. An
                // earlier version only laid the plant on top and left the eyes and jaw showing
                // under it, which is what the report saw as "two heads".
                var clipsJson = json.getAsJsonObject("animations");
                for (String state : clipsJson.keySet()) {
                    var tracks = clipsJson.getAsJsonObject(state).getAsJsonObject("bones");
                    for (String hidden : new String[]{"head", "hair", "jaw", "tongue"}) {
                        var track = tracks.getAsJsonObject(hidden);
                        assertNotNull(track, zombie + "/" + state + " has no '" + hidden
                                + "' track, so its rest pose would draw the zombie's face");
                        assertFalse(track.getAsJsonObject("visible").get("0.0").getAsBoolean(),
                                zombie + "/" + state + " still draws its own '" + hidden + "'");
                    }
                }
                // And the plant rides the hidden head's own visibility: it is there while the
                // zombie is alive and falls with the head on death.
                for (String state : new String[]{"idle", "walk", "eat", "shoot"}) {
                    assertTrue(plantHeadVisible(clipsJson, state),
                            zombie + "/" + state + " has to draw the plant head");
                }
                assertFalse(plantHeadVisible(clipsJson, "death"),
                        zombie + "/death must let the plant head fall with the head");
            }
        }
    }

    private static boolean plantHeadVisible(com.google.gson.JsonObject clips, String state) {
        return clips.getAsJsonObject(state).getAsJsonObject("bones")
                .getAsJsonObject("plant_head").getAsJsonObject("visible")
                .get("0.0").getAsBoolean();
    }

    /** A peashooter-headed zombie shoots the plant in its lane. */
    @Test
    void thePeaHeadZombieShootsThePlantInFrontOfIt() {
        LevelServer level = lawn();
        PlantEntity target = place(level, "pea_shooter", 3, 2);
        spawn(level, "zombotany_pea_zombie", 7F, 2);
        int full = target.health();

        tick(level, 400);

        assertTrue(target.health() < full || target.isRemoved(),
                "the plant has to have been shot: " + target.health() + " of " + full);
    }

    @Test
    void zombiePeasPassOverLowPlantsAndHitThePlantBehindThem() {
        LevelServer level = lawn();
        PlantEntity low = place(level, "puff_shroom", 4, 2);
        PlantEntity tall = place(level, "wall_nut", 2, 2);
        spawn(level, "zombotany_pea_zombie", 7F, 2);
        int lowHealth = low.health();
        int tallHealth = tall.health();

        tick(level, 400);

        assertEquals(lowHealth, low.health(), "the pea travels above the sleeping puff-shroom");
        assertTrue(tall.health() < tallHealth, "the same pea still damages the wall-nut behind it");
    }

    /** The wall-nut head has to be chewed through before the body is touched. */
    @Test
    void theWallNutHeadIsArmourAndNotHealth() {
        LevelServer level = lawn();
        ZombieEntity zombie = spawn(level, "zombotany_wallnut_zombie", 5F, 2);
        int body = zombie.health();
        assertTrue(zombie.hasArmor(), "and it starts wearing the nut");

        // One ordinary pea's worth: far less than the nut.
        zombie.damage(20, ZombieEntity.damageType(PvzceIds.DAMAGE_PROJECTILE), level);
        assertEquals(body, zombie.health(), "the body is untouched while the nut is on");
        assertTrue(zombie.hasArmor(), "and one pea does not remove it");
    }

    /**
     * The jalapeno head's blast is bounded by the plant ceiling.
     *
     * <p>The same rule the blast mutations were given: half an ordinary plant, so that a zombie
     * dying to one pea cannot take a peashooter with it. A bombing zombie whose blast killed the
     * plants outright would make the whole line unplayable.
     */
    @Test
    void theJalapenoHeadIsBounded() {
        LevelServer level = lawn();
        PlantEntity victim = place(level, "pea_shooter", 3, 2);
        int full = victim.health();
        ZombieEntity zombie = spawn(level, "zombotany_jalapeno_zombie", 3.5F, 2);

        zombie.damage(10_000, ZombieEntity.damageType(PvzceIds.DAMAGE_ASH), level);
        level.flushPending(packet -> { });
        assertFalse(zombie.isAlive(), "the fixture has to actually die");

        assertFalse(victim.isRemoved(),
                "the plant next to it must survive one bomb - that is what stops the chain");
        assertEquals(full - com.pvzce.common.level.mutation.MutantBlast.plantDamage(),
                victim.health(), "and it takes exactly the ceiling");
    }
}
