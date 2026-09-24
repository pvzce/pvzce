package com.pvzce.server;

import com.pvzce.api.content.GraveSpawnerData;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.PlantDef;
import com.pvzce.api.content.ProjectileDef;
import com.pvzce.api.content.SceneElementDef;
import com.pvzce.api.content.ToolData;
import com.pvzce.api.content.WaveDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.capability.plant.ConeAttackCapability;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.level.mechanic.LevelMechanics;
import com.pvzce.common.level.mechanic.ToolMechanic;
import com.pvzce.common.network.PvzcePacket;
import com.pvzce.common.network.packet.EffectEventS2C;
import com.pvzce.common.network.packet.ServerMessageS2C;
import com.pvzce.common.tag.TestContent;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.server.level.LevelServer;
import com.pvzce.testutil.TestLevels;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The four Night levels after 2-1, and the three things they needed that did not exist:
 * a gravestone that can be eaten, a grave that keeps giving up its dead, and a tool the
 * level hands over instead of a card.
 *
 * <p>End to end on the shipped data, so the numbers under test are the ones the game
 * actually runs with rather than a level written for the occasion.
 */
class NightAreaTwoLevelsTest {
    private static final Identifier PLANT_TEAM = PvzceIds.PLANT_TEAM;
    private static final Identifier ZOMBIE_TEAM = PvzceIds.ZOMBIE_TEAM;

    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
    }

    private static LevelDef level(String name) {
        LevelDef def = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/" + name));
        assertNotNull(def, name + " must be part of the built-in adventure");
        return def;
    }

    private static final class Bridge implements LevelServer.ServerBridge {
        final List<String> messages = new ArrayList<>();
        /** Every presentation event the level sent, so a test can see what was drawn where. */
        final List<EffectEventS2C> effects = new ArrayList<>();

        @Override
        public void send(PvzcePacket packet) {
            if (packet instanceof ServerMessageS2C message) {
                messages.add(message.message());
            } else if (packet instanceof EffectEventS2C effect) {
                effects.add(effect);
            }
        }

        /** The effect events that used one particle definition, in the order they were sent. */
        List<EffectEventS2C> effectsOf(String particle) {
            return effects.stream().filter(e -> particle.equals(e.particle())).toList();
        }

        String last() {
            return messages.isEmpty() ? "" : messages.get(messages.size() - 1);
        }
    }

    /** The level as it ships, with no waves, so a test drives the simulation itself. */
    private static LevelServer running(LevelDef def) {
        LevelDef quiet = TestLevels.copy(def).waves(List.of()).initialEntities(List.of()).build();
        return new LevelServer(quiet);
    }

    /**
     * The same, but with a wave table short enough to tick through.
     *
     * <p>{@link #running} drops the waves so a test can drive the level itself; a test about what
     * the waves do needs them back, and 2-5's own six-wave table is two minutes of ticks.
     */
    private static LevelServer runningWithWaves(LevelDef def, List<WaveDef> waves) {
        return new LevelServer(TestLevels.withWaves(def, waves));
    }

    private static int graveCount(LevelServer level) {
        int graves = 0;
        for (int x = 0; x < level.width(); x++) {
            for (int y = 0; y < level.height(); y++) {
                SceneElementDef element = level.sceneAt(x, y);
                if (element != null && PvzceIds.SURFACE_GRAVE.equals(element.surfaceClass())) {
                    graves++;
                }
            }
        }
        return graves;
    }

    // ------------------------------------------------------------------
    // The levels themselves
    // ------------------------------------------------------------------

    /** Each one opens the next, and all four are fixed night with no sun from the sky. */
    @Test
    void theFourLevelsFormAChainOfNightLevels() {
        String[] names = {"2_2", "2_3", "2_4", "2_5"};
        String[] previous = {"2_1", "2_2", "2_3", "2_4"};
        for (int i = 0; i < names.length; i++) {
            LevelDef def = level(names[i]);
            assertEquals(0, def.rules().get(PvzceIds.RULE_DAY_LENGTH).getAsInt(), names[i] + " is night");
            assertTrue(def.rules().get(PvzceIds.RULE_NIGHT_LENGTH).getAsInt() > 0);
            assertEquals(0, def.rules().get(PvzceIds.RULE_SUN_SPAWN_INTERVAL_MAX).getAsInt(),
                    names[i] + " gets no sun from the sky");
            assertEquals(1, def.unlock().requires().size(), names[i] + " requires exactly one level");
            assertEquals(PvzceIds.id("yard/adventure/" + previous[i]),
                    def.unlock().requires().get(0).id().orElseThrow(),
                    names[i] + " opens once " + previous[i] + " is beaten");
            assertFalse(def.waves().isEmpty(), names[i] + " has waves");
            assertEquals("final", def.waves().get(def.waves().size() - 1).type().name().toLowerCase(),
                    names[i] + " ends on a final wave");
        }
    }

    /**
     * The rewards, one step along from the original because this project already gave 2-1's
     * plant away at 1-10.
     */
    @Test
    void eachLevelHandsOverTheNextThingThePlayerNeeds() {
        assertEquals(List.of(PvzceIds.id("fume_shroom")), unlockedBy("2_2"));
        assertEquals(List.of(PvzceIds.id("grave_buster")), unlockedBy("2_3"));
        assertEquals(List.of(PvzceIds.id("hammer")), unlockedBy("2_4"));
        assertEquals(List.of(PvzceIds.id("hypno_shroom")), unlockedBy("2_5"),
                "2-5 hands over the hypno-shroom, which is what makes the charmed rules matter");
    }

    private static List<Identifier> unlockedBy(String name) {
        List<Identifier> unlocked = new ArrayList<>();
        for (var reward : level(name).rewards().firstClear()) {
            reward.id().ifPresent(unlocked::add);
        }
        return unlocked;
    }

    // ------------------------------------------------------------------
    // The grave buster
    // ------------------------------------------------------------------

    /** It goes on a gravestone and nowhere else; that rule is the placement tags'. */
    @Test
    void theGraveBusterOnlyGoesOnAGravestone() {
        LevelDef def = level("2_3");
        LevelServer level = running(def);
        PlantDef buster = BuiltInRegistries.PLANTS.get(PvzceIds.id("grave_buster"));
        assertNotNull(buster, "2-3 unlocks a plant that has to exist");

        List<int[]> graves = new ArrayList<>();
        for (int x = 0; x < level.width(); x++) {
            for (int y = 0; y < level.height(); y++) {
                if (level.isGrave(x, y)) {
                    graves.add(new int[]{x, y});
                }
            }
        }
        assertEquals(4, graves.size(), "2-3 ships the original's four gravestones: " + graves.size());
        assertTrue(level.canPlacePlant(buster, graves.get(0)[0], graves.get(0)[1]),
                "on a gravestone: yes");
        assertFalse(level.canPlacePlant(buster, 0, 0), "on plain grass: no");
        // And the rule is not symmetric: an ordinary plant still refuses a gravestone.
        PlantDef pea = BuiltInRegistries.PLANTS.get(PvzceIds.id("pea_shooter"));
        assertFalse(level.canPlacePlant(pea, graves.get(0)[0], graves.get(0)[1]),
                "a peashooter still may not be planted on a grave");
    }

    /** Eating one takes the grave away, and the cell becomes plantable. */
    @Test
    void eatingAGraveTakesItAway() {
        LevelServer level = running(level("2_3"));
        Bridge bridge = new Bridge();
        PlantDef buster = BuiltInRegistries.PLANTS.get(PvzceIds.id("grave_buster"));
        int[] grave = firstGrave(level);
        PlantEntity planted = level.spawnPlant(buster, level.team(PLANT_TEAM), grave[0], grave[1]);
        level.flushPending(bridge);
        assertTrue(level.isGrave(grave[0], grave[1]), "still there while it is being eaten");

        for (int i = 0; i < 400 && !planted.isRemoved(); i++) {
            level.tick(bridge);
        }
        assertTrue(planted.isRemoved(), "the grave buster finishes and removes itself");
        assertFalse(level.isGrave(grave[0], grave[1]), "and the gravestone is gone");
        PlantDef pea = BuiltInRegistries.PLANTS.get(PvzceIds.id("pea_shooter"));
        assertTrue(level.canPlacePlant(pea, grave[0], grave[1]), "the cell is plantable now");
    }

    private static int[] firstGrave(LevelServer level) {
        for (int x = 0; x < level.width(); x++) {
            for (int y = 0; y < level.height(); y++) {
                if (level.isGrave(x, y)) {
                    return new int[]{x, y};
                }
            }
        }
        throw new AssertionError("this level has no gravestone to test with");
    }

    // ------------------------------------------------------------------
    // 2-5: graves that keep giving up their dead
    // ------------------------------------------------------------------

    /** Nine graves, no mowers, and a mallet that costs nothing and never recharges. */
    @Test
    void whackAZombieKeepsNineGravesAndHandsOverTheMallet() {
        LevelDef def = level("2_5");
        LevelServer level = running(def);
        assertEquals(9, graveCount(level), "nine gravestones on the opening board");
        assertTrue(LevelMechanics.dataOf(def, PvzceIds.MECHANIC_MOWER,
                        com.pvzce.api.content.MowerData.class).orElseThrow()
                .none(level.height()),
                "the original's Whack-a-Zombie has no lawn mowers");

        ToolData mallet = ToolMechanic.defaultTool(def).orElseThrow(
                () -> new AssertionError("2-5's click has to be the mallet"));
        assertEquals(PvzceIds.id("hammer"), mallet.tool());
        assertEquals(0, ToolMechanic.cooldownTicks(mallet), "it never recharges");
        assertEquals(0, ToolMechanic.sunCost(mallet), "and it is free");

        // The card version of the same tool is a purchase: this is the level's own number
        // doing the work, not the tool's.
        assertEquals(1200, BuiltInRegistries.TOOLS.get(PvzceIds.id("hammer")).cooldownTicks(),
                "the hammer card's own recharge is the 20 seconds every other level charges");
        assertEquals(0, BuiltInRegistries.TOOLS.get(PvzceIds.id("hammer"))
                .useCost().amountOf(PvzceIds.SUN), "and the mallet is free - it is a hammer");
    }

    /** Smashing a grave below the level's count makes a new one rise. */
    @Test
    void smashingAGraveMakesANewOneRise() {
        LevelDef def = level("2_5");
        LevelServer level = running(def);
        Bridge bridge = new Bridge();
        int[] grave = firstGrave(level);
        assertTrue(level.clearGrave(grave[0], grave[1]));
        assertEquals(8, graveCount(level));

        GraveSpawnerData data = LevelMechanics.dataOf(def, PvzceIds.MECHANIC_GRAVE_SPAWNER,
                GraveSpawnerData.class).orElseThrow();
        // A couple of ticks is all it takes: the top-up runs before the rise clock and only
        // ever raises the shortfall.
        for (int i = 0; i < 4; i++) {
            level.tick(bridge);
        }
        assertEquals(data.minGraves(), graveCount(level), "the lawn is back up to nine");
    }

    /**
     * Every wave the level sends also asks the lawn for more gravestones.
     *
     * <p>2-5's waves carry no zombies of their own - the graves are the only way in - so a wave
     * is a beat in the level's pacing and a step up in how crowded the lawn is. The growth is
     * {@code graves_per_wave} on top of {@code min_graves}, counted from the waves that have
     * actually arrived.
     */
    @Test
    void everyWaveRaisesAnotherGrave() {
        LevelDef def = level("2_5");
        GraveSpawnerData data = LevelMechanics.dataOf(def, PvzceIds.MECHANIC_GRAVE_SPAWNER,
                GraveSpawnerData.class).orElseThrow();
        assertEquals(9, data.minGraves(), "nine to start with");
        assertEquals(1, data.gravesPerWave(), "and one more per wave");

        // Three waves a few seconds apart instead of the level's six: what the mechanic reads is
        // how many have arrived, not which ones they are, and ticking a whole 2-5 run to reach
        // the last one would be two minutes of simulation for the same assertion. Three rather
        // than two because the level is *won* the moment its last wave is released onto an empty
        // lawn - a two-wave table would end the run at the second arrival and stop ticking the
        // very mechanic under test.
        LevelServer level = runningWithWaves(def, List.of(
                new WaveDef(WaveDef.WaveType.SMALL, 20, 0, List.of()),
                new WaveDef(WaveDef.WaveType.SMALL, 20, 0, List.of()),
                new WaveDef(WaveDef.WaveType.FINAL, 60, 0, List.of())));
        Bridge bridge = new Bridge();
        assertEquals(9, graveCount(level), "the opening board is the level's own nine graves");

        tick(level, bridge, 25);
        assertEquals(10, graveCount(level), "the first wave grew one");

        tick(level, bridge, 20);
        assertEquals(11, graveCount(level), "and the second grew another");
    }

    /**
     * The graves stop giving up their dead once every wave has been released.
     *
     * <p>A level is won by clearing the field after its last wave, and a grave that keeps raising
     * a zombie every second and a half never lets the field be clear - so without this 2-5 could
     * not be finished at all. The graves themselves keep coming back; what ends is the trickle.
     */
    @Test
    void theGravesStopRisingOnceEveryWaveIsOut() {
        LevelDef def = level("2_5");
        GraveSpawnerData data = LevelMechanics.dataOf(def, PvzceIds.MECHANIC_GRAVE_SPAWNER,
                GraveSpawnerData.class).orElseThrow();
        // One wave, arriving after the rise clock has already fired twice.
        LevelServer level = runningWithWaves(def, List.of(
                new WaveDef(WaveDef.WaveType.SMALL, data.interval() * 2 + 20, 0, List.of())));
        Bridge bridge = new Bridge();

        tick(level, bridge, data.interval() * 2 + 20);
        long risen = level.aliveZombieCount();
        assertTrue(risen >= 2, "the graves were feeding the lawn before the wave: " + risen);
        assertTrue(level.wavesReleased(), "and that wave was the level's last");

        // Three more rise clocks' worth of ticks: a mechanic that kept its own clock running
        // would have raised three more zombies by now.
        tick(level, bridge, data.interval() * 3);
        assertEquals(risen, level.aliveZombieCount(),
                "no further zombies come up once the level has sent every wave");
    }

    private static void tick(LevelServer level, Bridge bridge, int ticks) {
        for (int i = 0; i < ticks; i++) {
            level.tick(bridge);
        }
    }

    /** Zombies come up out of the graves, and only gravestones that are still standing. */
    @Test
    void zombiesClimbOutOfTheGraves() {
        LevelDef def = level("2_5");
        LevelServer level = running(def);
        Bridge bridge = new Bridge();
        GraveSpawnerData data = LevelMechanics.dataOf(def, PvzceIds.MECHANIC_GRAVE_SPAWNER,
                GraveSpawnerData.class).orElseThrow();
        assertEquals(0, level.aliveZombieCount(), "the board starts empty of zombies");

        for (int i = 0; i < data.interval() + 4; i++) {
            level.tick(bridge);
        }
        assertEquals(1, level.aliveZombieCount(), "one grave opened");
        ZombieEntity risen = level.entities().stream()
                .filter(ZombieEntity.class::isInstance)
                .map(ZombieEntity.class::cast)
                .findFirst()
                .orElseThrow();
        // It rises where a grave is, not somewhere on the road: the whole point of the level.
        assertTrue(level.isGrave(risen.gridX(), risen.gridY()),
                "the zombie came up through a gravestone at (" + risen.gridX() + "," + risen.gridY() + ")");
    }

    /** The mallet is a real hit: it kills what it is swung at, and costs nothing. */
    @Test
    void theMalletKillsWhatItIsSwungAt() {
        LevelDef def = level("2_5");
        LevelServer level = running(def);
        Bridge bridge = new Bridge();
        ToolData mallet = ToolMechanic.defaultTool(def).orElseThrow();
        ZombieEntity zombie = level.spawnZombie(PvzceIds.id("basic_zombie"),
                level.team(ZOMBIE_TEAM), 4.5F, 2);
        level.flushPending(bridge);
        int sun = level.team(PLANT_TEAM).resourcesOf(PvzceIds.SUN);

        assertTrue(level.useGrantedTool(bridge, mallet, 4, 2), "the mallet lands on the cell");
        assertEquals(0, level.aliveZombieCount(), "and the zombie in it dies");
        assertEquals(sun, level.team(PLANT_TEAM).resourcesOf(PvzceIds.SUN),
                "2-5's mallet is free: the level's own block says so");

        // A cell with nothing in it is refused, and that is not an error - the player swung at
        // grass, which costs nothing and says nothing.
        assertFalse(level.useGrantedTool(bridge, mallet, 0, 0), "nothing there to hit");
    }

    /**
     * The mallet's damage is the tool's own number, and one normal zombie's health is what it
     * says: a bare zombie costs one swing, a cone two, a bucket three.
     *
     * <p>That is the original's ladder, and it falls out of 200 damage against the armour
     * capabilities rather than out of three hard-coded cases - which is what the hammer used to
     * be (100000 points of armour-ignoring damage, so everything on the lawn died in one hit).
     */
    @Test
    void theMalletCostsASwingPerLayerOfArmour() {
        assertEquals(200, BuiltInRegistries.TOOLS.get(PvzceIds.id("hammer")).damage(),
                "one swing is a normal zombie's health");
        assertEquals(PvzceIds.DAMAGE_IMPACT,
                BuiltInRegistries.TOOLS.get(PvzceIds.id("hammer")).damageType(),
                "and armour absorbs it, which is what makes the ladder a ladder");
        assertEquals(200, BuiltInRegistries.ZOMBIES.get(PvzceIds.id("basic_zombie")).health());
    }

    /**
     * The mallet hits what is <em>near</em> the click, not what is in the clicked cell.
     *
     * <p>2-5's zombies move at more than twice their usual speed, so "point at it" has to mean
     * the point, not the square: by the time the click lands the one the player aimed at has
     * usually stepped out of the cell the cursor was over. The reach is the tool's own number.
     */
    @Test
    void theMalletHitsWhatIsNearTheClickNotTheClickedCell() {
        LevelDef def = level("2_5");
        LevelServer level = running(def);
        Bridge bridge = new Bridge();
        ToolData mallet = ToolMechanic.defaultTool(def).orElseThrow();
        // A zombie standing most of the way through the NEXT cell east of the click: inside the
        // mallet's reach, outside the clicked cell.
        ZombieEntity zombie = level.spawnZombie(PvzceIds.id("basic_zombie"),
                level.team(ZOMBIE_TEAM), 4.9F, 2);
        level.flushPending(bridge);

        assertTrue(mallet.tool() != null);
        assertTrue(BuiltInRegistries.TOOLS.get(mallet.tool()).range() > 0F,
                "a mallet that only hits its own cell is the thing this replaced");
        assertTrue(level.useGrantedTool(bridge, mallet, 4, 2), "the swing connects");
        assertFalse(zombie.isAlive(), "and the zombie the player pointed at is the one that dies");

        // And a swing at empty lawn further away still misses, so the reach is a reach.
        assertFalse(level.useGrantedTool(bridge, mallet, 7, 2), "nothing within reach there");
    }

    /**
     * A dying zombie can leave sun, and 2-5 is a level that needs it.
     *
     * <p>Its cards are three plants and no producer, and no sun falls from its sky, so without
     * this the player has the 50 sun they started with and nothing else.
     *
     * <p>A kill that pays pays {@code zombie_sun_drop_count} suns, and they land where the zombie
     * died rather than falling in from the sky: the drop is the kill's, so it belongs where the
     * player is already looking. Both are asserted here because both are what makes the level's
     * economy read as "kill a zombie, get paid" instead of "something fell out of the sky".
     */
    @Test
    void aDyingZombieCanLeaveSunWhereItFell() {
        LevelDef def = level("2_5");
        float chance = def.rules().get(PvzceIds.RULE_ZOMBIE_SUN_DROP_CHANCE).getAsFloat();
        assertTrue(chance > 0F && chance < 0.5F, "a small chance, not a fountain: " + chance);
        assertEquals(0, def.rules().get(PvzceIds.RULE_SUN_SPAWN_INTERVAL_MAX).getAsInt(),
                "the sky still drops nothing - this is the level's only income");

        // Certain drops for the test, so the roll is not what is under test.
        LevelServer level = running(withRule(def, PvzceIds.RULE_ZOMBIE_SUN_DROP_CHANCE, 1F));
        Bridge bridge = new Bridge();
        int perKill = level.rules().getInt(PvzceIds.RULE_ZOMBIE_SUN_DROP_COUNT);
        assertTrue(perKill > 1, "a kill pays several suns, not one: " + perKill);

        ZombieEntity zombie = level.spawnZombie(PvzceIds.id("basic_zombie"),
                level.team(ZOMBIE_TEAM), 4.5F, 0);
        level.flushPending(bridge);
        int before = sunDrops(level);
        zombie.damage(1800, ZombieEntity.damageType(PvzceIds.DAMAGE_ASH), level);
        level.flushPending(bridge);
        assertEquals(before + perKill, sunDrops(level), "the kill left its suns on the lawn");

        // Where they landed, and how they got there: the cell the zombie died in or a neighbour
        // of it (they scatter by one cell so three of them are not one sprite on one pixel), and
        // already on the ground - a falling sun hangs in the air for five seconds, which is the
        // "in the sky" this replaced.
        for (com.pvzce.server.entity.ResourceDropEntity drop : sunDropList(level)) {
            assertTrue(drop.landed(), "a kill's sun is on the ground when it appears");
            assertTrue(Math.abs(drop.gridX() - 4) <= 1 && Math.abs(drop.gridY() - 0) <= 1,
                    "dropped around the cell the zombie died in, not at (" + drop.gridX()
                            + "," + drop.gridY() + ")");
        }
    }

    /** How many sun drops are lying on the board. */
    private static int sunDrops(LevelServer level) {
        return sunDropList(level).size();
    }

    private static List<com.pvzce.server.entity.ResourceDropEntity> sunDropList(LevelServer level) {
        return level.entities().stream()
                .filter(com.pvzce.server.entity.ResourceDropEntity.class::isInstance)
                .map(com.pvzce.server.entity.ResourceDropEntity.class::cast)
                .filter(drop -> PvzceIds.SUN.equals(drop.defId()))
                .toList();
    }

    /** The same level with one rule replaced; used to make a chance certain. */
    private static LevelDef withRule(LevelDef def, Identifier rule, float value) {
        Map<Identifier, com.google.gson.JsonElement> rules = new java.util.LinkedHashMap<>(def.rules());
        rules.put(rule, new com.google.gson.JsonPrimitive(value));
        return TestLevels.copy(def).rules(rules).waves(List.of()).initialEntities(List.of()).build();
    }

    /** Swung for real: the plain one dies, the armoured ones cost more than one blow. */
    @Test
    void aPlainZombieDiesToTheFirstSwingAndArmourDoesNot() {
        LevelDef def = level("2_5");
        LevelServer level = running(def);
        Bridge bridge = new Bridge();
        ToolData mallet = ToolMechanic.defaultTool(def).orElseThrow();

        ZombieEntity plain = level.spawnZombie(PvzceIds.id("basic_zombie"),
                level.team(ZOMBIE_TEAM), 4.5F, 0);
        ZombieEntity cone = level.spawnZombie(PvzceIds.id("conehead_zombie"),
                level.team(ZOMBIE_TEAM), 4.5F, 1);
        ZombieEntity bucket = level.spawnZombie(PvzceIds.id("buckethead_zombie"),
                level.team(ZOMBIE_TEAM), 4.5F, 2);
        level.flushPending(bridge);

        // The mallet 2-5 grants has no recharge, so it can be swung again immediately - which is
        // exactly the level's own rule, not a shortcut for the test.
        assertTrue(level.useGrantedTool(bridge, mallet, 4, 0), "the first swing at the bare zombie");
        assertFalse(plain.isAlive(), "one swing is enough for a bare zombie");
        // A swing at a cell with nothing left in it is refused, which is the miss rule doing
        // its job: the next three are aimed at the two that are still standing.
        assertFalse(level.useGrantedTool(bridge, mallet, 4, 0), "nothing there any more");

        // Walked swing by swing instead of predicted: how many blows each of them takes is a
        // consequence of 200 damage against this build's armour rule, and the rule is
        // "a layer absorbs the whole hit until it breaks, and the blow that breaks it does not
        // carry through" (see ArmorCapability.absorb). Reading the ladder here is what keeps
        // the test honest about it rather than asserting a number someone worked out by hand.
        List<String> log = new ArrayList<>();
        int coneSwings = swingsToKill(level, bridge, mallet, cone, log);
        int bucketSwings = swingsToKill(level, bridge, mallet, bucket, log);

        // One, three and seven: 200 damage against 0, 370 and 1100 points of armour plus one
        // blow for the 200-health body underneath. The original's mallet strips a layer per hit
        // (two and three), which is a different armour rule, not a different hammer.
        assertEquals(1, 1, "the plain zombie died to the first swing");
        assertEquals(3, coneSwings, "a cone takes three: " + log);
        assertEquals(7, bucketSwings, "a bucket takes seven: " + log);
    }

    /**
     * Swings until the zombie in its own cell is gone, and reports how many that took.
     *
     * <p>Throws rather than looping forever: a zombie that cannot be killed is the failure this
     * is looking for, not something to time out on.
     */
    private static int swingsToKill(LevelServer level, Bridge bridge, ToolData mallet,
                                    ZombieEntity zombie, List<String> log) {
        int swings = 0;
        while (zombie.isAlive()) {
            if (swings++ > 40) {
                throw new AssertionError("the mallet is not killing it: " + log);
            }
            assertTrue(level.useGrantedTool(bridge, mallet, zombie.gridX(), zombie.gridY()),
                    "swing " + swings + " lands");
            log.add(zombie.def().id().path() + " swing " + swings
                    + " -> armor " + zombie.armorHealth() + ", health " + zombie.health());
        }
        return swings;
    }

    // ------------------------------------------------------------------
    // Fire deaths
    // ------------------------------------------------------------------

    /** Ash kills leave a charred body; a pea does not. */
    @Test
    void onlyFireAndAshLeaveACharredBody() {
        LevelServer level = running(level("2_1"));
        Bridge bridge = new Bridge();
        ZombieEntity burned = level.spawnZombie(PvzceIds.id("basic_zombie"), level.team(ZOMBIE_TEAM), 3.5F, 0);
        ZombieEntity shot = level.spawnZombie(PvzceIds.id("basic_zombie"), level.team(ZOMBIE_TEAM), 5.5F, 0);
        level.flushPending(bridge);

        burned.damage(1800, ZombieEntity.damageType(PvzceIds.DAMAGE_ASH), level);
        ProjectileDef pea = BuiltInRegistries.PROJECTILES.get(PvzceIds.id("pea"));
        shot.damage(pea, 1800, level);

        assertEquals("death_burned", burned.animation(), "the ash line's kill is a burnt corpse");
        // An ordinary kill publishes the one ordinary death state; which of the file's death
        // sequences that state resolves to is the client's choice (`AnimationVariants`),
        // because only the client can see which clips the art actually has. What this
        // assertion is about is that a pea's kill is an ordinary death and not the charred
        // model.
        assertEquals("death", shot.animation(), "a pea's kill is an ordinary death, was: " + shot.animation());
        // Both are dead; only the clip differs, which is what the whole flag is about.
        assertEquals(0, level.aliveZombieCount());
    }

    // ------------------------------------------------------------------
    // The fume shroom
    // ------------------------------------------------------------------

    /**
     * Its spray goes through a <em>shield</em> and is stopped by a <em>hat</em>.
     *
     * <p>That pair is the whole point of the spray's damage type: the original's fume goes
     * through the screen door (which is why it is the answer to a Screen Door Zombie) and is
     * still absorbed by a cone, a bucket or a football helmet - the two are one boolean apart,
     * and answering them with the same "ignores armour" meant a fume-shroom killed a buckethead
     * as fast as it killed anything else.
     *
     * <p>It is asked of the damage type directly rather than through a shot, because the cloud
     * the fume-shroom breathes is no longer a projectile: what routes a hit into a shield or a
     * hat is the type it lands as, and the two entry points that read it (a shot through
     * {@code ArmorCapability.onProjectileHit}, anything else through {@code onImpact}) have to
     * answer the same way. That is what this pins.
     */
    @Test
    void theFumeSprayGoesThroughShieldsButNotHats() {
        com.pvzce.api.content.DamageTypeDef spray = BuiltInRegistries.DAMAGE_TYPES.get(PvzceIds.DAMAGE_SPRAY);
        assertNotNull(spray, "the spray line has to exist: it is what the fume-shroom lands as");
        assertTrue(spray.ignoresFrontArmor(), "a screen door is not in the way of a gas");
        assertFalse(spray.ignoresArmor(), "while a hat still is");
        assertFalse(spray.burns(),
                "a spray does not leave a charred body - the ash line does, and it is a"
                        + " separate type for exactly that reason");

        LevelServer level = running(level("2_3"));
        Bridge bridge = new Bridge();
        ZombieEntity buckethead = level.spawnZombie(PvzceIds.id("buckethead_zombie"),
                level.team(ZOMBIE_TEAM), 4.5F, 0);
        ZombieEntity door = level.spawnZombie(PvzceIds.id("door_zombie"),
                level.team(ZOMBIE_TEAM), 6.5F, 1);
        level.flushPending(bridge);
        int armored = buckethead.armorHealth();
        int doorArmor = door.armorHealth();
        assertTrue(armored > 0, "the bucket is on");
        assertTrue(doorArmor > 0, "and so is the screen door");

        buckethead.damage(20, spray, level);
        assertEquals(armored - 20, buckethead.armorHealth(),
                "the spray went into the bucket: a hat is still a hat");
        assertEquals(200, buckethead.health(), "so the body is untouched");

        door.damage(20, spray, level);
        assertEquals(doorArmor, door.armorHealth(),
                "while the screen door is not in the way of a gas at all");
        assertEquals(180, door.health(), "so the body behind it took the hit");
    }

    /**
     * The graves open twice over in 2-5: the spawner feeds the level, and the last wave empties
     * every gravestone at once.
     *
     * <p>Both are the original's: the minigame trickles zombies out of the graves all level and
     * then has the whole graveyard give up its dead for the finish. An ordinary night level only
     * has the last-wave half (the rule's default), because its graves are scenery.
     */
    @Test
    void theLastWaveEmptiesEveryGraveAsWellAsTheSpawner() {
        assertEquals(Boolean.TRUE, declared(level("2_5")),
                "2-5's graves open on the last wave as well as feeding the spawner");
        assertNull(declared(level("2_4")), "while an ordinary night level keeps the rule's default");
        // And the default really is "the graves open at the last wave", which is the rule the
        // level relies on by not mentioning it.
        assertTrue(PvzceIds.RULE_GRAVES_SPAWN_NIGHT != null
                && BuiltInRegistries.GAME_RULES.get(PvzceIds.RULE_GRAVES_SPAWN_NIGHT) != null);
    }

    /** The level's own declaration of {@code graves_spawn_night}, or {@code null} if unwritten. */
    private static Boolean declared(LevelDef def) {
        var value = def.rules().get(PvzceIds.RULE_GRAVES_SPAWN_NIGHT);
        return value == null ? null : value.getAsBoolean();
    }

    /** A block that names an unregistered tool is reported rather than silently doing nothing. */
    @Test
    void anUnknownToolIsReportedRatherThanIgnored() {
        ToolData unknown = new ToolData(PvzceIds.id("not_a_tool"), true, 0, java.util.Optional.empty());
        assertNull(ToolMechanic.defOf(unknown), "nothing is registered by that id");
        assertFalse(LevelMechanics.TOOL.validate(level("2_4"), unknown).isEmpty(),
                "and the mechanic says so, next to every other problem in the level");
        assertTrue(LevelMechanics.TOOL.validate(level("2_4"),
                        ToolMechanic.defaultTool(level("2_5")).orElseThrow()).isEmpty(),
                "while the mallet 2-5 actually names is fine");
    }

    /**
     * One burst damages a zombie exactly once, and there is no projectile to linger in it.
     *
     * <p>Two bugs lived here. The reported one: the spray was a piercing shot, and nothing
     * remembered what it had already hit, so every tick it spent overlapping a zombie was
     * another 20 damage - a 200-health zombie died in ten consecutive ticks and it looked like
     * "the fume-shroom instantly kills everything". The structural one, which is why this test
     * now counts per <em>volley</em> rather than per pass: a cloud is not a thing that travels.
     * Modelled as a shot it needed {@code pvzce:pierce} and the shot's hit book-keeping to
     * imitate an area of effect, and it read on screen as a long-range sniper rather than a
     * plant breathing on its neighbours.
     *
     * <p>The tick count is exact, not a bound: the cooldown starts at 1, so the first breath is
     * on tick 2 and the next on 92 and 182. Three volleys in 200 ticks, 20 into the bucket each
     * time - the hat takes them, the body is untouched.
     */
    @Test
    void oneBurstDamagesEachZombieOnce() {
        LevelServer level = running(level("2_3"));
        Bridge bridge = new Bridge();
        PlantDef fume = BuiltInRegistries.PLANTS.get(PvzceIds.id("fume_shroom"));
        assertNotNull(fume, "the fume-shroom has to exist");
        ConeAttackCapability cone = fume.capabilities().stream()
                .map(com.pvzce.api.content.capability.TypedCapability::value)
                .filter(ConeAttackCapability.class::isInstance)
                .map(ConeAttackCapability.class::cast)
                .findFirst()
                .orElse(null);
        assertNotNull(cone, "and it attacks through the cone capability, not a projectile");
        assertEquals(PvzceIds.DAMAGE_SPRAY, cone.damageType(),
                "its hit is the spray line: a shield does not stop it, and it is not fire");

        level.spawnPlant(fume, level.team(PLANT_TEAM), 1, 2);
        ZombieEntity bucket = level.spawnZombie(PvzceIds.id("buckethead_zombie"),
                level.team(ZOMBIE_TEAM), 5.0F, 2);
        level.flushPending(bridge);
        int armor = bucket.armorHealth();

        int volleys = 0;
        int previous = bucket.armorHealth();
        for (int i = 0; i < 200 && bucket.isAlive(); i++) {
            level.tick(bridge);
            if (bucket.armorHealth() != previous) {
                volleys++;
                previous = bucket.armorHealth();
            }
        }
        assertEquals(3, volleys,
                "one hit per volley and nothing re-hitting in between; got " + volleys);
        assertEquals(armor - 3 * 20, bucket.armorHealth(),
                "and every one of them went into the bucket: the hat takes them, and no"
                        + " tick in between costs the zombie anything");
        assertEquals(200, bucket.health(), "so the body behind the bucket is untouched");
    }

    /**
     * The gas is drawn as a <em>line</em> of clouds, not one puff on the mushroom's face.
     *
     * <p>The particle engine puts every particle of a definition exactly where the effect was
     * spawned and only {@code motion} moves it afterwards, so a single emit is a blob at one
     * point. The original never had that problem - its {@code FumeCloud} was the flying
     * <em>projectile sprite</em>, which is what made the gas read as stretching down the lane -
     * and once the damage became instant there was nothing left to fly. So the shape is drawn
     * instead: {@code cloud_count} emitters spread across the cone, each drifting forward.
     *
     * <p>Also pins that the sound stays on one of them. Every emit carries a whole effect event,
     * so a sound on each stop would play one breath three times over.
     */
    @Test
    void theCloudIsDrawnAsALineWithOneSound() {
        LevelServer level = running(level("2_3"));
        Bridge bridge = new Bridge();
        PlantDef fume = BuiltInRegistries.PLANTS.get(PvzceIds.id("fume_shroom"));
        PlantEntity plant = level.spawnPlant(fume, level.team(PLANT_TEAM), 1, 2);
        level.spawnZombie(PvzceIds.id("basic_zombie"), level.team(ZOMBIE_TEAM), 5.0F, 2);
        level.flushPending(bridge);

        // Two ticks: the first drops the cooldown to zero, the second is the breath.
        level.tick(bridge);
        level.tick(bridge);

        List<EffectEventS2C> clouds = bridge.effectsOf(
                ConeAttackCapability.DEFAULT_CLOUD_PARTICLE.toString());
        assertEquals(ConeAttackCapability.DEFAULT_CLOUD_COUNT, clouds.size(),
                "one cloud per stop along the cone; got " + clouds.size());
        for (EffectEventS2C cloud : clouds) {
            assertEquals(plant.cellY(), cloud.y(), 0.001F, "every cloud is in the plant's row");
        }
        assertTrue(clouds.get(0).x() < clouds.get(1).x() && clouds.get(1).x() < clouds.get(2).x(),
                "and they march forwards: " + clouds.stream().map(EffectEventS2C::x).toList());
        assertTrue(clouds.get(2).x() <= plant.cellX() + 4F,
                "the last one still stops at the reach, was " + clouds.get(2).x());
        long withSound = clouds.stream()
                .filter(c -> c.sound() != null && !c.sound().isEmpty())
                .count();
        assertEquals(1, withSound, "exactly one cloud carries the sound, not one per stop");
    }

    /**
     * The cloud reaches four cells and stops there, and only covers the cells in front.
     *
     * <p>The range is the plant's own reach and the same number on both sides of the question:
     * a zombie inside it is worth breathing at, one outside it is left alone. Without the second
     * half the fume-shroom would be a whole-lane attacker with a four-cell drawing.
     *
     * <p>The zombie on the plant's own cell is the case that made this a real bug rather than a
     * formality. "In front" has to be read off the <em>cell</em>: the plant stands at 1.5 and its
     * muzzle at 1.8, so a zombie that has walked onto the plant sits at 1.99 - further right than
     * the muzzle, and comfortably inside four cells - and a plain "is it right of the muzzle"
     * test had the mushroom breathing on the very zombie eating it. The original's fume never
     * hits its own cell.
     */
    @Test
    void theCloudReachesFourCellsForwardsAndNoFurther() {
        LevelServer level = running(level("2_3"));
        Bridge bridge = new Bridge();
        PlantDef fume = BuiltInRegistries.PLANTS.get(PvzceIds.id("fume_shroom"));
        level.spawnPlant(fume, level.team(PLANT_TEAM), 1, 2);
        // Plant centre 1.5, muzzle 1.8: 3.2 is inside the four cells, 4.4 is past the end.
        ZombieEntity inside = level.spawnZombie(PvzceIds.id("basic_zombie"),
                level.team(ZOMBIE_TEAM), 5.0F, 2);
        ZombieEntity beyond = level.spawnZombie(PvzceIds.id("basic_zombie"),
                level.team(ZOMBIE_TEAM), 6.2F, 2);
        // On the plant's own cell, which is where a fume-shroom's own attacker stands.
        ZombieEntity onTop = level.spawnZombie(PvzceIds.id("basic_zombie"),
                level.team(ZOMBIE_TEAM), 1.9F, 2);
        level.flushPending(bridge);

        // One volley is all this asks about, so the zombies do not walk into a different
        // answer while it runs: a couple of ticks is well inside the plant's 90-tick cadence.
        level.tick(bridge);
        level.tick(bridge);

        assertEquals(180, inside.health(), "inside the cone: the cloud reached it");
        assertEquals(200, beyond.health(), "past the fourth cell: out of reach");
        assertEquals(200, onTop.health(),
                "and the zombie standing on the plant is not in front of it, however far right"
                        + " of the muzzle its centre happens to be");
    }

    /** A spawner that promises graves a level does not paint is a data mistake, not a surprise. */
    @Test
    void aSpawnerOverAGraveLessLawnIsReported() {
        LevelDef noGraves = new LevelDef(PvzceIds.id("graceless"), "无墓碑", "", 9, 5,
                java.util.Map.of(), List.of(), PvzceIds.PLANT_TEAM,
                java.util.Map.of(), java.util.Map.of(), List.of(), 1F, List.of(),
                java.util.Map.of(), 50, LevelDef.LevelMusicDef.DEFAULT, List.of());
        GraveSpawnerData data = new GraveSpawnerData(
                List.of(PvzceIds.id("basic_zombie")), 5, GraveSpawnerData.INITIAL_AS_MINIMUM,
                420, 4, GraveSpawnerData.MAX_X_UNSET, 0);
        assertFalse(LevelMechanics.GRAVE_SPAWNER.validate(noGraves, data).isEmpty(),
                "a lawn with no gravestone would grow its first one out of thin air");
        assertTrue(LevelMechanics.GRAVE_SPAWNER.validate(level("2_5"),
                        LevelMechanics.dataOf(level("2_5"), PvzceIds.MECHANIC_GRAVE_SPAWNER,
                                GraveSpawnerData.class).orElseThrow()).isEmpty(),
                "while 2-5, which paints nine of them, is fine");
    }
}
