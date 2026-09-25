package com.pvzce.server;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.MowerData;
import com.pvzce.api.content.PlantDef;
import com.pvzce.api.content.ToolDef;
import com.pvzce.api.content.WaveDef;
import com.pvzce.api.content.ZombieDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.capability.zombie.SubmergeCapability;
import com.pvzce.common.capability.zombie.VaultCapability;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.core.EntityArt;
import com.pvzce.common.level.mechanic.LevelMechanics;
import com.pvzce.common.tag.TestContent;
import com.pvzce.common.tag.PvzceTags;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pool's five levels, as facts rather than as balance.
 *
 * <p>What belongs here is the shape of the area: the board is the backyard's six lanes with
 * the middle two under water, the levels chain off 2-10 and pay out the four cards and the
 * tool the pool introduces, the water rows carry the pool cleaner and the floatie zombies
 * while the lawn carries the walkers, and the bonus level is a fixed deck. The wave
 * *tables* are balance data and are not judged here - {@code WavePacingMetricsTest} is what
 * measures those.
 */
class PoolAreaLevelsTest {
    private static final List<String> POOL_LEVELS = List.of("3_1", "3_2", "3_3", "3_4", "3_5");
    private static final List<Integer> WATER_ROWS = List.of(2, 3);
    private static final List<Integer> GRASS_ROWS = List.of(0, 1, 4, 5);

    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
    }

    private static LevelDef pool(String name) {
        LevelDef def = BuiltInRegistries.LEVELS.get(
                Identifier.withDefaultNamespace("yard/adventure/" + name));
        assertNotNull(def, name + " must be a shipped level");
        return def;
    }

    /** The board is the original's backyard: nine columns, six lanes, the middle two wet. */
    @Test
    void everyPoolLevelIsTheBackyardsSixLaneBoard() {
        for (String name : POOL_LEVELS) {
            LevelDef def = pool(name);
            assertEquals(9, def.width(), name + " is nine columns wide");
            assertEquals(6, def.height(), name + " is six lanes tall");

            List<String> water = def.scene().get(Identifier.withDefaultNamespace("water"));
            assertNotNull(water, name + " must paint its pool");
            assertEquals(18, water.size(), name + " has two full rows of water");
            for (String cell : water) {
                int row = Integer.parseInt(cell.split(",")[1]);
                assertTrue(WATER_ROWS.contains(row), name + ": water belongs in rows 2 and 3, not " + cell);
            }

            List<String> grass = def.scene().get(Identifier.withDefaultNamespace("grass"));
            assertNotNull(grass, name + " must paint its lawn");
            assertEquals(36, grass.size(), name + " keeps four rows of lawn");
            for (String cell : grass) {
                int row = Integer.parseInt(cell.split(",")[1]);
                assertTrue(GRASS_ROWS.contains(row), name + ": lawn belongs outside the pool, not " + cell);
            }
        }
    }

    /**
     * The pool is played on the pool's backdrop, with the shaders left on.
     *
     * <p>The other two areas turn them off, and copying that here would flatten the water into
     * the baked fallback frames - the one place in the game where the liquid pass is the point.
     */
    @Test
    void thePoolUsesThePoolBackdropWithTheLiquidPassOn() {
        for (String name : POOL_LEVELS) {
            LevelDef def = pool(name);
            assertEquals(Identifier.withDefaultNamespace("textures/gui/screen/level/background3"),
                    def.background().orElseThrow(), name + " plays on the pool's own backdrop");
            assertFalse(def.disableShaders(), name + " must keep the water shader");
            assertEquals(List.of("pvzce:grass"), def.hiddenSceneElements(),
                    name + " hides the lawn its backdrop already draws");
        }
    }

    /** The chain: 3-1 opens off 2-10, and each level after it off the one before. */
    @Test
    void thePoolChainsOffTheNightFinale() {
        assertEquals(List.of("pvzce:yard/adventure/2_10"), requires(pool("3_1")));
        assertEquals(List.of("pvzce:yard/adventure/3_1"), requires(pool("3_2")));
        assertEquals(List.of("pvzce:yard/adventure/3_2"), requires(pool("3_3")));
        assertEquals(List.of("pvzce:yard/adventure/3_3"), requires(pool("3_4")));
        assertEquals(List.of("pvzce:yard/adventure/3_4"), requires(pool("3_5")));
    }

    /** What the pool hands over: two plants before the tool, then the tool, then the last. */
    @Test
    void thePoolPaysOutItsOwnFourCardsAndTheWateringCan() {
        assertEquals(List.of("pvzce:squash"), unlocks(pool("3_1")));
        assertEquals(List.of("pvzce:threepeater"), unlocks(pool("3_2")));
        assertEquals(List.of("pvzce:tangle_kelp"), unlocks(pool("3_3")));
        assertEquals(List.of("pvzce:watering_can"), unlocks(pool("3_4")));
        assertEquals(List.of("pvzce:jalapeno"), unlocks(pool("3_5")));
    }

    /**
     * The water lanes have the pool cleaner and the lawn has mowers.
     *
     * <p>The original's own arrangement, and the reason {@code MowerData} grew a kind: a lawn
     * mower driving across the pool is the sort of thing a player notices immediately.
     */
    @Test
    void theWaterRowsCarryPoolCleanersAndTheLawnCarriesMowers() {
        for (String name : POOL_LEVELS) {
            LevelDef def = pool(name);
            MowerData mowers = LevelMechanics
                    .dataOf(def, PvzceIds.MECHANIC_MOWER, MowerData.class)
                    .orElseThrow(() -> new AssertionError(name + " declares no mower block"));

            assertEquals(GRASS_ROWS, mowers.rowsFor(def.height()).stream()
                    .filter(row -> !WATER_ROWS.contains(row)).toList());
            for (int row : WATER_ROWS) {
                MowerData.MowerKind kind = mowers.kindFor(row);
                assertEquals(Identifier.withDefaultNamespace("pool_cleaner"), kind.kind(),
                        name + " row " + row + " has the pool cleaner");
                assertTrue(kind.sound().isPresent(), "and its own launch sound");
            }
            for (int row : GRASS_ROWS) {
                assertEquals(MowerData.DEFAULT_KIND, mowers.kindFor(row).kind(),
                        name + " row " + row + " keeps the ordinary mower");
            }
        }
    }

    /**
     * Every wave entry says which lanes it arrives in, and no walker is sent into the pool.
     *
     * <p>This is the one that matters: a land zombie spawned into a water lane drowns on its
     * first step, so a level that forgot the lane filter would quietly lose a third of its
     * zombies - and the count would still look right in the file.
     */
    @Test
    void everyEntryIsPinnedToLanesItsZombiesCanActuallyWalk() {
        for (String name : POOL_LEVELS) {
            LevelDef def = pool(name);
            for (int index = 0; index < def.waves().size(); index++) {
                WaveDef wave = def.waves().get(index);
                for (WaveDef.Entry entry : wave.entries()) {
                    assertTrue(entry.restrictedToRows(),
                            name + " wave " + (index + 1) + ": " + entry.id() + " names no lanes");
                    ZombieDef zombie = BuiltInRegistries.ZOMBIES.get(entry.id());
                    assertNotNull(zombie, entry.id() + " must be a registered zombie");
                    boolean water = entry.rows().contains(2);
                    assertEquals(water, zombie.canSwim(),
                            name + " wave " + (index + 1) + ": " + entry.id()
                                    + (water ? " is sent into the pool and must swim"
                                             : " walks the lawn and must not be a swimmer"));
                    assertFalse(entry.rows().isEmpty(), entry.id() + " must name rows");
                }
            }
        }
    }

    /**
     * 3-4 hands the watering can over inside the level, the way 2-5 hands over the mallet.
     *
     * <p>The reward makes it permanent; the level-granted copy is what makes the reward mean
     * anything, since a tool the player has never held is a card they will not think to pick.
     */
    @Test
    void theWateringCanIsInHandInTheLevelThatAwardsIt() {
        LevelDef def = pool("3_4");
        com.pvzce.api.content.ToolData granted = LevelMechanics
                .dataOf(def, PvzceIds.MECHANIC_TOOL, com.pvzce.api.content.ToolData.class)
                .orElseThrow(() -> new AssertionError("3-4 must grant its own tool"));
        assertEquals(Identifier.withDefaultNamespace("watering_can"), granted.tool());
        assertTrue(granted.isDefault(), "a bare click waters, so the level teaches by doing");
    }

    /**
     * 3-5 is the original's "Big Trouble Little Zombie": a conveyor belt, not a chosen deck.
     *
     * <p>The belt deals the four cards the original's own table lists - Peashooter, Cherry Bomb,
     * Wall-nut, Lily Pad - at 25/35/15/25, and caps the lily pads at 18, which is one for every
     * water cell on a 9x6 pool. That cap is the reason the level has no sun: the cards are free,
     * and the only decision left is where they go.
     */
    @Test
    void theBonusLevelIsTheOriginalsConveyorBeltOfLittleZombies() {
        LevelDef def = pool("3_5");
        assertEquals(List.of(), def.slots(), "a belt level pins no cards by hand");
        assertEquals(0, def.initialSun(), "and hands out no sun: the cards are free");
        assertFalse(def.unlockResources().containsKey(PvzceIds.SUN),
                "there is no sun to collect in this level");

        com.pvzce.api.content.LevelBelt belt = LevelMechanics
                .dataOf(def, PvzceIds.MECHANIC_CONVEYOR, com.pvzce.api.content.LevelBelt.class)
                .orElseThrow(() -> new AssertionError("3-5 must deal its own cards"));
        List<String> cards = belt.cards().stream()
                .map(card -> card.card().toString()).toList();
        assertEquals(List.of("pvzce:pea_shooter", "pvzce:cherry_bomb", "pvzce:wall_nut",
                "pvzce:lily_pad"), cards, "the original's own four cards");
        assertEquals(25, belt.cards().get(0).weight());
        assertEquals(35, belt.cards().get(1).weight());
        assertEquals(15, belt.cards().get(2).weight());
        assertEquals(25, belt.cards().get(3).weight());
        assertEquals(18, belt.cards().get(3).maxCount(),
                "one lily pad for every water cell, and not one more");

        // The crowd is the little ones, and 3-5 says so in the content rather than in a rule:
        // how fast a little zombie walks belongs to the little zombie.
        assertFalse(def.rules().containsKey(PvzceIds.RULE_ZOMBIE_SPEED_MULTIPLIER),
                "the level is a crowd, not a speed modifier");
        for (WaveDef wave : def.waves()) {
            for (WaveDef.Entry entry : wave.entries()) {
                assertTrue(entry.id().path().startsWith("mini_"),
                        entry.id() + " must be a little zombie: the original shrinks the normal"
                                + " bodies instead of adding new ones");
            }
        }
    }

    /**
     * The little zombies are the ordinary bodies, drawn smaller - the whole trick of the level.
     *
     * <p>{@code Big Trouble Little Zombie} does not add art for its crowd: it scales the normal
     * zombies to half size, halves their health - armour included, so a little conehead is 100 +
     * 185 and a little football zombie 100 + 700 - and doubles how fast they walk and eat. So
     * every {@code mini_*} borrows its parent's animation file and halves the numbers, which also
     * means a fix to the parent's art fixes the little one.
     *
     * <p>The original's own table puts its little zombies at a <em>quarter</em> of the health
     * (140 for the conehead, 400 for the football zombie). Half is this build's deliberate
     * difficulty call: at a quarter, three peas removed one and the level's crowd was made of
     * paper. The rest of the trick is the original's - half the drawn size, twice the speed and
     * the same hunger.
     *
     * <p>The one thing that is deliberately NOT scaled is the hitbox: a little zombie still fills
     * its lane, because the size is presentation and {@code render_scale} is presentation-only.
     */
    @Test
    void theLittleZombiesAreTheNormalBodiesDrawnSmallerAndHalved() {
        var pairs = Map.of(
                "mini_basic_zombie", "basic_zombie",
                "mini_flag_zombie", "flag_zombie",
                "mini_conehead_zombie", "conehead_zombie",
                "mini_football_zombie", "football_zombie",
                "mini_ducky_tube_zombie", "ducky_tube_zombie",
                "mini_snorkel_zombie", "snorkel_zombie");
        for (var pair : pairs.entrySet()) {
            ZombieDef little = BuiltInRegistries.ZOMBIES.get(PvzceIds.id(pair.getKey()));
            ZombieDef parent = BuiltInRegistries.ZOMBIES.get(PvzceIds.id(pair.getValue()));
            assertNotNull(little, pair.getKey() + " must be a registered zombie");
            assertNotNull(parent, pair.getValue() + " must be a registered zombie");

            assertEquals(EntityArt.animationFile(parent.id()), EntityArt.animationFile(little.id()),
                    pair.getKey() + " must be the parent's own body, borrowed through `animation`");
            assertEquals(0.5F, little.renderScale(),
                    pair.getKey() + " is drawn at half size");
            assertEquals(parent.renderScale(), 1F, pair.getValue() + " itself is untouched");
            assertEquals(parent.health() / 2, little.health(),
                    pair.getKey() + " has half of its parent's health bar");
            assertEquals(parent.moveSpeed() * 2F, little.moveSpeed(), 1e-6F,
                    pair.getKey() + " is twice as fast");
            assertEquals(parent.biteIntervalTicks() / 2, little.biteIntervalTicks(),
                    pair.getKey() + " eats twice as fast");
            assertEquals(parent.biteDamage(), little.biteDamage(), "but bites just as hard");
            assertEquals(parent.canSwim(), little.canSwim(),
                    pair.getKey() + " arrives in the same lanes its parent does");

            // Armour is halved with the body, and both halves divide exactly: 370 is 2 x 185 and
            // 1400 is 2 x 700, so there is nothing to round away.
            int parentArmor = armorDurability(parent);
            int littleArmor = armorDurability(little);
            assertEquals(parentArmor, littleArmor * 2,
                    pair.getKey() + " carries " + littleArmor + " points of armour, which is not"
                            + " half of its parent's " + parentArmor);
            assertEquals(parent.health() + parentArmor, 2 * (little.health() + littleArmor),
                    pair.getKey() + " is half of its parent including the armour");
        }
        // The two readings a player would recognise, as this build's numbers rather than the
        // original's: a little conehead is 285 all in and a little football zombie 800.
        assertEquals(285, 100 + armorDurability(
                BuiltInRegistries.ZOMBIES.get(PvzceIds.id("mini_conehead_zombie"))));
        assertEquals(800, 100 + armorDurability(
                BuiltInRegistries.ZOMBIES.get(PvzceIds.id("mini_football_zombie"))));
    }

    /**
     * Every floatie's art has the water gait its capability asks for, and it IS the water look.
     *
     * <p>{@code pvzce:float} makes a ducky-tube zombie publish {@code swim} while it is in a
     * water cell, and the complaint that produced it was exact: with only the land gait, a
     * floatie crossing the pool looked like it was walking on the surface. The clip is where
     * that is fixed, so the assertions are about its frames:
     *
     * <ul>
     *   <li>the legs are hidden - they are under the waterline in the original's drawing, and
     *       this engine has no water-over-body pass to cover them;</li>
     *   <li>the tube's <em>in-water</em> drawing is the visible one (the tube track switches
     *       image halfway through the source reanim, so both drawings are already in the file);</li>
     *   <li>the land drawing of the same tube is off, or the zombie wears two rings.</li>
     * </ul>
     *
     * <p>A definition that carries the capability without the clip would not fail loudly
     * anywhere: an unknown state falls back to {@code idle}, so the zombie would stand still in
     * the middle of the pool with nothing in the log to say why.
     */
    @Test
    void everyFloatieHasItsSwimClipAndTheSwimClipIsTheWaterLook() throws Exception {
        for (String id : List.of("ducky_tube_zombie", "ducky_tube_conehead_zombie",
                "ducky_tube_buckethead_zombie", "mini_ducky_tube_zombie")) {
            ZombieDef def = BuiltInRegistries.ZOMBIES.get(PvzceIds.id(id));
            assertNotNull(def, id + " must be a registered zombie");
            assertTrue(def.capability(com.pvzce.common.capability.zombie.FloatCapability.class)
                            .isPresent(),
                    id + " floats in the water");

            var json = animationJson(EntityArt.animationFile(def.id()));
            var clips = json.getAsJsonObject("animations");
            var swim = clips.getAsJsonObject("swim");
            assertNotNull(swim, id + " must have a swim clip: `pvzce:float` publishes that state,"
                    + " and a state the art does not define falls back to idle");

            var bones = swim.getAsJsonObject("bones");
            for (String leg : List.of("innerleg_upper", "innerleg_lower", "innerleg_foot",
                    "outerleg_upper", "outerleg_lower", "outerleg_foot")) {
                assertFalse(visibleValues(bones, leg).contains(true),
                        id + " must not walk its legs under water, but " + leg + " is drawn");
            }
            assertFalse(visibleValues(bones, "duckytube").contains(true),
                    id + " must swap the floatie for its in-water drawing");
            assertTrue(visibleValues(bones, "duckytube_inwater").contains(true),
                    id + " must draw the in-water floatie while it swims");
        }
    }

    /**
     * The parked pool cleaner plays its LAND idle, not its in-water one.
     *
     * <p>{@code PoolCleaner.reanim} carries four masks and the first two are the trap: {@code
     * anim_land} is the machine on the poolside - the wheeled body, four wheel tracks, the funnel
     * swaying - and {@code anim_water} is the same machine afloat, with the wheels gone, a
     * different body drawing and a cycling whitewater wake. The cleaner waits on the poolside
     * (both machines share the one anchor half a cell off the board), so its {@code idle} has to
     * be the land pose; pointing it at the water mask put a floating cleaning head on the tiles.
     *
     * <p>The wheels are also why this is safe to loop: their rotation is constant across the
     * whole land range, so looping parks the machine instead of spinning it in place.
     */
    @Test
    void theParkedPoolCleanerPlaysItsLandIdle() throws Exception {
        var json = animationJson(Identifier.withDefaultNamespace("mechanic/pool_cleaner"));
        var idle = json.getAsJsonObject("animations").getAsJsonObject("idle");
        assertNotNull(idle, "the cleaner must have an idle clip");
        var bones = idle.getAsJsonObject("bones");

        for (String onLand : List.of("body_1", "wheel", "wheel_2", "wheel_3", "wheel_4")) {
            assertTrue(visibleValues(bones, onLand).contains(true),
                    "a parked pool cleaner stands on its wheels: " + onLand + " must be drawn");
        }
        for (String afloat : List.of("body_2", "whitewater_1", "whitewater_2", "whitewater_3",
                "bubble")) {
            assertFalse(visibleValues(bones, afloat).contains(true),
                    "and it is not in the water: " + afloat + " must not be drawn");
        }
    }

    /**
     * 3-5 plays the mini-game track, the same one the bowling level uses.
     *
     * <p>Which is what the original does with this level, and the kind of thing that is invisible
     * in every other test: a level with music is a level with music.
     */
    @Test
    void theBonusLevelPlaysTheBowlingTrack() {
        String bonus = pool("3_5").music().cues().get(0).event().orElseThrow().toString();
        LevelDef bowling = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/1_5"));
        assertNotNull(bowling, "the bowling level must exist");
        String expected = bowling.music().cues().get(0).event().orElseThrow().toString();
        assertEquals(expected, bonus, "3-5 shares the bowling level's track");
        assertEquals("pvzce:music/loon_boon", bonus);
    }

    /**
     * The cherry bomb's blast does not outlive its own burst.
     *
     * <p>The explosion the player actually sees is the particles the SERVER emits, not the
     * {@code explode} clip, and those are what "the explosion takes too long" was about: the big
     * blast sprite sat fully opaque for over half a second and the cloud ring for two thirds,
     * against a plant that is off the board half a second after it goes off. The original's blast
     * is a half-second event, so each piece of it is bounded here.
     */
    @Test
    void everyPieceOfTheCherryBlastFitsInsideItsBurst() {
        com.pvzce.api.content.PlantDef cherry =
                BuiltInRegistries.PLANTS.get(PvzceIds.id("cherry_bomb"));
        assertNotNull(cherry);
        var explosive = cherry.capability(
                        com.pvzce.common.capability.plant.ExplosiveCapability.class)
                .orElseThrow(() -> new AssertionError("the cherry bomb is an explosive"));
        assertFalse(explosive.particles().isEmpty(), "it draws something");
        for (Identifier id : explosive.particles()) {
            var particle = BuiltInRegistries.PARTICLES.get(id);
            assertNotNull(particle, id + " must be a registered particle");
            float life = particle.look().lifetime();
            assertTrue(life <= 0.5F,
                    id + " lives " + life + "s, which is longer than a half-second burst");
        }
        assertTrue(explosive.lingerTicks() <= 30,
                "and the plant itself is gone half a second later");
    }

    /** The visible-track values of one bone in one clip, as the JSON spells them. */
    private static List<Boolean> visibleValues(com.google.gson.JsonObject clipBones, String bone) {
        var entry = clipBones.getAsJsonObject(bone);
        assertNotNull(entry, bone + " must have a track in this clip");
        var visible = entry.getAsJsonObject("visible");
        assertNotNull(visible, bone + " must state its visibility");
        List<Boolean> values = new ArrayList<>();
        visible.entrySet().forEach(frame -> values.add(frame.getValue().getAsBoolean()));
        return values;
    }

    /** One animation file, read the way the loader would: from the classpath. */
    private static com.google.gson.JsonObject animationJson(Identifier file) throws Exception {
        assertNotNull(file, "the id must resolve to an animation file");
        String path = "assets/" + file.namespace() + "/animations/" + file.path() + ".json";
        try (var stream = classLoader().getResourceAsStream(path)) {
            assertNotNull(stream, path + " must be shipped");
            return com.google.gson.JsonParser.parseString(
                    new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }

    private static int armorDurability(ZombieDef def) {
        int total = 0;
        for (var entry : def.resolvedCapabilities()) {
            if (entry.value() instanceof com.pvzce.common.capability.zombie.ArmorCapability armor) {
                for (var piece : armor.armor()) {
                    total += Math.max(0, piece.durability());
                }
            }
        }
        return total;
    }

    /**
     * The pool's own zombies exist, swim, and behave like what they are.
     *
     * <p>Three of them arrived with the area and one of those three is a whole new rule - the
     * snorkel, which is only reachable while it is eating.
     */
    @Test
    void thePoolZombiesSwimAndCarryTheirOwnRules() {
        for (String id : List.of("ducky_tube_zombie", "ducky_tube_conehead_zombie",
                "ducky_tube_buckethead_zombie", "snorkel_zombie", "dolphin_rider_zombie")) {
            ZombieDef def = BuiltInRegistries.ZOMBIES.get(PvzceIds.id(id));
            assertNotNull(def, id + " must be a registered zombie");
            assertTrue(def.canSwim(), id + " must be allowed into the water");
            assertNotNull(def.animations().fileId(def.id()),
                    id + " must point at art of its own");
        }
        ZombieDef snorkel = BuiltInRegistries.ZOMBIES.get(PvzceIds.id("snorkel_zombie"));
        assertTrue(snorkel.capability(SubmergeCapability.class).isPresent(),
                "the snorkel swims under the surface");
        ZombieDef dolphin = BuiltInRegistries.ZOMBIES.get(PvzceIds.id("dolphin_rider_zombie"));
        assertTrue(dolphin.capability(VaultCapability.class).isPresent(),
                "the dolphin rider hops the first plant");
    }

    /** The pool's plant and its tool: both new, both content, both usable. */
    @Test
    void theTangleKelpAndTheWateringCanAreContent() {
        PlantDef kelp = BuiltInRegistries.PLANTS.get(PvzceIds.id("tangle_kelp"));
        assertNotNull(kelp, "the tangle kelp must be registered");
        assertTrue(PvzceTags.PLANTS.contains(PvzceTags.WATER_PLANT, kelp.id()),
                "it is a water plant: only the pool can hold it");
        assertTrue(kelp.capability(com.pvzce.common.capability.plant.DragUnderCapability.class)
                        .isPresent(),
                "and it drags what steps on it under");

        ToolDef can = BuiltInRegistries.TOOLS.get(PvzceIds.id("watering_can"));
        assertNotNull(can, "the watering can must be registered");
        assertEquals("pvzce:water", can.effect(), "its effect is the one LevelServer implements");
        assertEquals(-1, can.uses(), "a can is not used up");
        assertTrue(can.cooldownTicks() > 0, "but it has a recharge");
    }

    /**
     * The art the pool arrived with is really in the pack, and its parts are all there.
     *
     * <p>Every file here was generated by `tools/reanim_to_pvzce_all.py` and every texture it
     * names by the same run, so a converter that quietly skipped a sprite would ship a zombie
     * that draws as a magenta checkerboard - which is exactly what the client falls back to when
     * a model cannot be loaded. Reading the bytes is the cheapest place to catch that.
     */
    @Test
    void thePoolArtIsShippedAndComplete() {
        List<String> animated = List.of(
                "zombie/basic/ducky_tube_zombie",
                "zombie/armored/ducky_tube_conehead_zombie",
                "zombie/armored/ducky_tube_buckethead_zombie",
                "zombie/special/snorkel_zombie",
                "zombie/special/dolphin_rider_zombie",
                "mechanic/pool_cleaner",
                "plant/environment/tangle_kelp",
                "tool/watering_can");
        for (String file : animated) {
            String path = "assets/pvzce/animations/" + file + ".json";
            try (var stream = classLoader().getResourceAsStream(path)) {
                assertNotNull(stream, path + " must be shipped");
                var json = com.google.gson.JsonParser.parseString(
                        new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                var bones = json.getAsJsonObject().getAsJsonObject("model").getAsJsonArray("bones");
                assertTrue(bones.size() > 0, path + " must have a model");
                for (var bone : bones) {
                    var parts = bone.getAsJsonObject().getAsJsonArray("parts");
                    if (parts == null) {
                        continue;
                    }
                    for (var part : parts) {
                        String texture = part.getAsJsonObject().get("texture").getAsString();
                        String texturePath = texture.substring(texture.indexOf(':') + 1) + ".png";
                        try (var png = classLoader().getResourceAsStream("assets/pvzce/" + texturePath)) {
                            assertNotNull(png, path + " names a part that is not in the pack: " + texture);
                        }
                    }
                }
            } catch (java.io.IOException e) {
                throw new AssertionError("could not read " + path, e);
            }
        }
    }

    /**
     * The id-to-art contract for everything the pool added: the path the client will look up is
     * the path the converter wrote.
     *
     * <p>{@code EntityArt} is the one answer to "which animation file is this id": a definition
     * that forgets {@code animation_dir} (or spells it differently than the pack's directory)
     * draws a magenta checkerboard with nothing else wrong, and a little zombie whose borrowed
     * {@code animation} is ignored would do the same. Checking the resolution end to end is what
     * catches both - the {@code mini_*} ids resolve to their <em>parent's</em> file on purpose.
     */
    @Test
    void everyNewContentIdResolvesToArtThatExists() {
        for (String id : List.of("ducky_tube_zombie", "ducky_tube_conehead_zombie",
                "ducky_tube_buckethead_zombie", "snorkel_zombie", "dolphin_rider_zombie",
                "mini_basic_zombie", "mini_flag_zombie", "mini_conehead_zombie",
                "mini_football_zombie", "mini_ducky_tube_zombie", "mini_snorkel_zombie",
                "tangle_kelp", "watering_can")) {
            Identifier defId = Identifier.withDefaultNamespace(id);
            Identifier file = EntityArt.animationFile(defId);
            assertNotNull(file, id + " must resolve to an animation file");
            String path = "assets/" + file.namespace() + "/animations/" + file.path() + ".json";
            try (var stream = classLoader().getResourceAsStream(path)) {
                assertNotNull(stream, id + " resolves to " + path + ", which is not shipped");
            } catch (java.io.IOException e) {
                throw new AssertionError("could not read " + path, e);
            }
        }
    }

    private static ClassLoader classLoader() {
        return Thread.currentThread().getContextClassLoader();
    }

    private static List<String> requires(LevelDef def) {
        List<String> ids = new ArrayList<>();
        def.unlock().requires().stream()
                .filter(requirement -> requirement.id().isPresent())
                .forEach(requirement -> ids.add(requirement.id().get().toString()));
        return ids;
    }

    private static List<String> unlocks(LevelDef def) {
        List<String> ids = new ArrayList<>();
        def.rewards().firstClear().stream()
                .filter(reward -> reward.isUnlock() && reward.id().isPresent())
                .forEach(reward -> ids.add(reward.id().get().toString()));
        return ids;
    }
}
