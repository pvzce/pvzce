package com.pvzce.common;

import com.pvzce.api.util.Identifier;

/**
 * Built-in registry ids and registry-scoped names in one place.
 *
 * <p>Mirrors {@link PvzceSounds} for identifiers: {@code pvzce:sun},
 * {@code pvzce:plant_team}, the game rules referenced from entities, and the
 * scene surface classes. Anything referenced from more than one class belongs
 * here so the two copies cannot drift.
 */
public final class PvzceIds {
    public static final Identifier FERTILIZER = id("fertilizer");
    public static final Identifier KERNEL_PULT = id("kernel_pult");
    public static final Identifier UMBRELLA_LEAF = id("umbrella_leaf");
    public static final Identifier BUFF_BUTTER_PLENTY = id("butter_plenty");
    public static final Identifier SUN = id("sun");
    public static final Identifier REDSTONE = id("redstone");
    public static final Identifier ENERGY_BEAN = id("energy_bean");

    /**
     * The persistent currency, in the original's four denominations.
     *
     * <p>Unlike sun it outlives a run: whatever a level collected is banked into the
     * world's {@code profile.dat} when the level ends, win or lose. They are separate
     * resources rather than one resource with an amount because each is a different
     * <em>object</em> with its own sprite and its own worth - a single "coin" id could
     * not tell the player whether they just picked up 10 or 1000.
     *
     * <p>What one is worth is {@code ResourceDef.defaultValue} in
     * {@code data/pvzce/resources/}, and a drop is spawned with that value as its
     * amount, so a team's resource count for a denomination already reads in coins.
     */
    public static final Identifier COIN_SILVER = id("coin_silver");
    public static final Identifier COIN_GOLD = id("coin_gold");
    public static final Identifier DIAMOND = id("diamond");
    public static final Identifier MONEY_BAG = id("money_bag");

    /** Every denomination, in ascending worth; the HUD and the bank sum over this. */
    public static final java.util.List<Identifier> COIN_DENOMINATIONS =
            java.util.List.of(COIN_SILVER, COIN_GOLD, DIAMOND, MONEY_BAG);

    /** True when this resource is money rather than a level resource like sun. */
    public static boolean isCoin(Identifier resource) {
        return resource != null && COIN_DENOMINATIONS.contains(resource);
    }

    /** True when this wire id names money; tolerant of ids the client cannot parse. */
    public static boolean isCoin(String resourceId) {
        return isCoin(Identifier.tryParse(resourceId));
    }

    /** The plant a fresh profile starts with, and the first level's only plant card. */
    public static final Identifier STARTER_PLANT = id("pea_shooter");
    /**
     * The jalapeno, named because one other thing hands it out without a plant.
     *
     * <p>The rhythm levels pay a long consecutive-PERFECT streak with a jalapeno in every row, and
     * the blast that buys is read off the plant's own definition - its damage, its damage type,
     * whether it melts ice, its sound. Spelled here rather than at that call site for the reason
     * every other id in this class is: a plant renamed in two places is a reward that silently
     * stops working.
     */
    public static final Identifier JALAPENO = id("jalapeno");
    /** The one tool a fresh profile starts with, so a misplaced plant can be dug up. */
    public static final Identifier STARTER_TOOL = id("shovel");

    public static final Identifier PLANT_TEAM = id("plant_team");
    public static final Identifier ZOMBIE_TEAM = id("zombie_team");

    public static final Identifier GRASS = id("grass");
    public static final Identifier GROUND = id("ground");
    /**
     * The roof's two terrains, the bare surfaces of world 5.
     *
     * <p>Named here because "is this cell bare ground" is a question the level asks (the vase tool
     * puts a vase on bare ground and nowhere else) and answering it by spelling the ids out at the
     * call site is how roof cells end up excluded by accident.
     */
    public static final Identifier ROOF_FLAT = id("roof_flat");
    public static final Identifier ROOF_SLOPE = id("roof_slope");
    /** The hole a blast leaves in bare ground. */
    public static final Identifier CRATER = id("crater");
    /** The same hole while it is filling back in, for the end of its recovery. */
    public static final Identifier CRATER_FADING = id("crater_fading");

    /**
     * The built-in liquid. A scene element opts into liquid rendering by naming a
     * liquid id, and a ripple event names the liquid it disturbs, so this id is the
     * join between the scene layer, the renderer and the server-side event.
     */
    public static final Identifier WATER = id("water");

    /**
     * The ice a zamboni leaves behind.
     *
     * <p>A scene element rather than a status: it is the ground, it persists in the save with the
     * scene, and it is tagged unplantable - which is the whole reason the trail matters.
     *
     * <p>Not permanent, though: every frozen cell melts back into lawn after the level's
     * {@code ice_melt} (thirty seconds by default), and a fire blast melts it at once. See
     * {@link #RULE_ICE_MELT}.
     */
    public static final Identifier ICE = id("ice");

    /** A vase standing on the lawn, empty. */
    public static final Identifier VASE = id("vase");
    /**
     * True for the scene elements a click with nothing in hand breaks open.
     *
     * <p>Two families, and they are deliberately different objects: the vase tool's own
     * {@link #VASE}/{@link #VASE_FULL}, and the vase level's {@link #POT_QUESTION}/{@link #POT_LEAF}
     * (see {@code ScaryPotterMechanic} for why the level's pots are not vases). What they share is
     * the interaction - click one and a mallet comes down on it - so the client has one answer to
     * "is there something here to hit" and the server one answer to "what did that break". Kept
     * here rather than in either half because both ask it.
     */
    public static boolean isSmashableContainer(Identifier id) {
        return VASE.equals(id) || VASE_FULL.equals(id)
                || POT_QUESTION.equals(id) || POT_LEAF.equals(id);
    }
    /**
     * The same vase with a card inside.
     *
     * <p>A second scene element rather than a flag on the first: a cell holds one id, and the
     * client draws from it - two ids is how a picture with two states is spelled here.
     */
    public static final Identifier VASE_FULL = id("vase_full");

    /**
     * A scary pot with a question mark on it: one of 4-5's vases, contents unknown.
     *
     * <p>Scary Potter's pots are the original's own drawings (`Scary_Pot.png`, cut by
     * `tools/gen_scary_pots.py`), and they are a different object from {@link #VASE}: a vase is
     * the tool the player owns, a pot is the level's. Two ids is how the engine keeps them from
     * being mistaken for each other when a hammer is swung at one.
     */
    public static final Identifier POT_QUESTION = id("pot_question");
    /**
     * The same pot with a leaf on it: the one the player can see holds a plant.
     *
     * <p>`ScaryPotterChangePotType` turns a handful of a round's plant pots green, which is the
     * level's only hint - every other pot has to be opened to find out.
     */
    public static final Identifier POT_LEAF = id("pot_leaf");

    /**
     * The water a mutation floods a lawn with, as opposed to the pool's own {@link #WATER}.
     *
     * <p>The same surface class - so every rule that reads terrain treats them alike - but with
     * per-cell art instead of a liquid: the pool draws its surface in one pass on the stage's own
     * basin frame, so a water cell outside that basin would be drawn inside it and a flooded lawn
     * would look like grass. See {@code tools/gen_flood_water.py}.
     */
    public static final Identifier FLOOD_WATER = id("flood_water");

    public static final Identifier RULE_DAY_LENGTH = id("day_length");
    public static final Identifier RULE_NIGHT_LENGTH = id("night_length");
    /**
     * How long the sky waits between two suns, at least and at most, in ticks.
     *
     * <p>A range rather than a rate, and read as a countdown rather than rolled every tick: the
     * sky picks the next gap when it drops a sun, so the gap the author wrote is the gap the
     * player gets. The old {@code sun_spawn_chance} was an independent roll per tick, which has
     * no memory - the same average meant droughts and clusters that no level asked for.
     *
     * <p>{@code min} of {@code 0} is the switch: it means the sky never drops anything (the
     * conveyor levels and most night levels), which is what the old chance of {@code 0} said.
     */
    public static final Identifier RULE_SUN_SPAWN_INTERVAL_MIN = id("sun_spawn_interval_min");
    public static final Identifier RULE_SUN_SPAWN_INTERVAL_MAX = id("sun_spawn_interval_max");
    /**
     * How long the level's first sun takes, in ticks.
     *
     * <p>Only the first: after it, the sky follows {@code sun_spawn_interval_min}/{@code _max}.
     * A level that wants no head start writes the same number as its interval.
     */
    public static final Identifier RULE_SUN_SPAWN_INITIAL_TICKS = id("sun_spawn_initial_ticks");
    public static final Identifier RULE_SUN_VALUE = id("sun_value");
    /**
     * How often a dying zombie leaves a sun behind, 0..1.
     *
     * <p>Separate from {@code sun_spawn_interval_min}, which is the sky: a level can have no sun fall
     * from above and still pay for kills, which is exactly what a level with no sun producers
     * and no sky needs. The original's Whack-a-Zombie is the case it exists for.
     */
    public static final Identifier RULE_ZOMBIE_SUN_DROP_CHANCE = id("zombie_sun_drop_chance");
    /**
     * How many suns one paying kill drops.
     *
     * <p>The other half of {@code zombie_sun_drop_chance}: three suns scattered around where the
     * zombie fell is a different offer from one, and a level with no producers and no sky pays
     * its player in exactly this currency. The chance is read against the count, so a level that
     * wants the same income in fewer, better moments lowers the chance and keeps the count.
     */
    public static final Identifier RULE_ZOMBIE_SUN_DROP_COUNT = id("zombie_sun_drop_count");
    public static final Identifier RULE_CRATER_RECOVERY = id("crater_recovery");
    /**
     * How long the zamboni's ice stays before it melts, in ticks.
     *
     * <p>Zero means never, which is the switch a level uses to keep a lane frozen for the whole
     * run. The default is the original's own number: an ice trail is a hazard with a clock rather
     * than a permanent change to the lawn - thirty seconds is long enough that the lane is lost
     * while the zamboni is still driving down it, and short enough that the player gets the ground
     * back.
     *
     * <p>Counted per cell from the moment that cell froze (see {@code LevelServer.tickScene}), so
     * a trail melts from its start rather than all at once.
     */
    public static final Identifier RULE_ICE_MELT = id("ice_melt");
    public static final Identifier RULE_ZOMBIE_DAMAGE_MULTIPLIER = id("zombie_damage_multiplier");
    public static final Identifier RULE_ZOMBIE_SPEED_MULTIPLIER = id("zombie_speed_multiplier");
    public static final Identifier RULE_PLANT_DAMAGE_MULTIPLIER = id("plant_damage_multiplier");
    /**
     * How much health a zombie arrives with, as a multiplier on its definition's own.
     *
     * <p>Read where a zombie is <em>created</em>, not while it walks: a zombie's health is its
     * health for the rest of its life (armour, the half-health transition and the client's bar all
     * read it), so a mutation that strengthens the horde strengthens the horde that has not
     * arrived yet. That is also the honest reading of "僵尸强化" - the ones already on the lawn
     * were killed or will be, and re-scaling a half-eaten zombie mid-bite would heal it.
     *
     * <p>Separate from the endless rounds' growth, which travels per wave
     * ({@code WaveDef.Entry.healthScale}) because it belongs to the wave rather than to the level.
     */
    public static final Identifier RULE_ZOMBIE_HEALTH_MULTIPLIER = id("zombie_health_multiplier");
    /**
     * How much health a plant has when it is planted, as a multiplier on its definition's own.
     *
     * <p>The same "read once, at creation" rule as {@link #RULE_ZOMBIE_HEALTH_MULTIPLIER}, and for
     * the same reason: a plant's full health is what the watering can heals it to and what the
     * client compares its current health against, so re-scaling a wounded plant would silently
     * repair it. A plant placed while the mutation is running is born fragile; the ones already
     * rooted are what the player has.
     */
    public static final Identifier RULE_PLANT_HEALTH_MULTIPLIER = id("plant_health_multiplier");
    /**
     * How long a status (a chill, a freeze) lasts, as a multiple of the effect's own duration.
     *
     * <p>Read where the status is applied ({@code StatusDurations.scale}), so it covers the snow
     * pea's hit and the ice-shroom's freeze alike: "the ground is icy, cold lasts twice as long" is
     * one rule about this lawn rather than a property of either plant.
     */
    public static final Identifier RULE_SLOW_DURATION_MULTIPLIER = id("slow_duration_multiplier");
    /**
     * How much faster than written this level's zombies arrive, as a multiplier on the rate.
     *
     * <p>Scales the level's whole spawn cadence - the gap between waves and the gap between
     * the zombies inside one wave - so {@code 2.0} plays the same wave table at twice the
     * speed: the same zombies, in half the time, with the relative pacing the author wrote.
     * Distinct from {@link #RULE_ZOMBIE_SPEED_MULTIPLIER}, which is how fast a zombie that is
     * already on the lawn walks; this one is how fast the next one shows up.
     *
     * <p>It exists for the levels whose pressure comes from somewhere else - a conveyor belt
     * hands out cards at its own fixed rate, so "the belt gives you this much and the horde
     * arrives this fast" is a knob the wave table alone cannot express without rewriting every
     * delay in it.
     */
    public static final Identifier RULE_ZOMBIE_SPAWN_SPEED_MULTIPLIER = id("zombie_spawn_speed_multiplier");
    /**
     * How long this level's cards take to recharge, as a multiple of the card's own cooldown.
     *
     * <p>The original's mini-games are where the card bar stops behaving like the adventure's:
     * Sleep Deprivation hands the player five cards and lets them come back three times as
     * fast. A multiplier rather than a per-card override because a level that wants faster
     * cards wants it for the bar it dealt, and because the card's own number is authored in
     * the plant/tool definition - a level is the wrong place to restate it.
     */
    public static final Identifier RULE_SEED_COOLDOWN_MULTIPLIER = id("seed_cooldown_multiplier");
    /**
     * How fast this level's plants work, as a multiplier on the rate.
     *
     * <p>Read by {@code PlantEntity.actionStep}, the one tick of progress every counting-down
     * capability spends (a shooter's cooldown, a producer's interval, a thrower's lob). Scaling
     * that single number rather than each capability is what keeps "the plants work twice as
     * fast" from becoming several answers that can drift apart.
     *
     * <p>It does not touch damage: this rule says how often, not how hard.
     */
    public static final Identifier RULE_PLANT_ACTION_SPEED_MULTIPLIER =
            id("plant_action_speed_multiplier");
    /**
     * Whether one card placement plants its whole column instead of one cell.
     *
     * <p>The "排山倒海" shape: a click on a column fills every cell of it that will take the plant
     * and skips the ones that will not (an occupied cell, water for a land plant, outside the
     * level's placement zone) - for the price of <em>one</em> card. Read by the placement path
     * itself ({@code LevelServer.placePlant}) and by the client, which draws the column it is about
     * to fill on the hover tint.
     *
     * <p>Off unless a level says otherwise: a level that plants in columns is a different game
     * about planting, not a default.
     */
    public static final Identifier RULE_PLANT_WHOLE_COLUMN = id("plant_whole_column");
    /**
     * How fast this level's sun arrives, as a multiplier on the rate.
     *
     * <p>Both halves of the sun economy in one number, because a player who reads "sun rate"
     * does not mean "the sky but not the flowers": the sky's countdown ({@code SunDropClock}) and
     * a producer's interval are both divided by it, so 2.0 is two suns where there was one.
     * Bigger is faster here, the same direction as every other rate rule.
     */
    public static final Identifier RULE_SUN_RATE_MULTIPLIER = id("sun_rate_multiplier");
    /**
     * What this level charges for a plant card, as a multiplier on the plant's own price.
     *
     * <p>Applied where the sun is actually taken ({@code DeckCardSource.spend}) rather than on
     * the price a card prints, so one rule covers every card on the bar and no definition has to
     * be rewritten to say "and now it costs half".
     */
    public static final Identifier RULE_PLANT_SUN_COST_MULTIPLIER =
            id("plant_sun_cost_multiplier");
    /**
     * How hard a mutation level is: {@code easy} / {@code normal} / {@code hard} / {@code hell}.
     *
     * <p>A string rule rather than four, because the three numbers a tier means (how many
     * mutations at once, how often, how strong) are one decision - see
     * {@code common.level.mutation.MutationDifficulty}. An unrecognised value falls back to the
     * middle tier and is reported by the level validator, the same policy every other rule
     * follows.
     */
    public static final Identifier RULE_MUTATION_DIFFICULTY = id("mutation_difficulty");
    /**
     * How long a mutation level waits for its first mutation.
     *
     * <p>Its own rule because the opening is the one moment a level is allowed to be gentle: the
     * player has to plant something before the lawn starts rewriting itself, and the tiers'
     * intervals are deliberately too short to serve as a grace period on 地狱 (thirty seconds).
     */
    public static final Identifier RULE_MUTATION_INITIAL_TICKS = id("mutation_initial_ticks");
    /**
     * How shrunken this level's own mutation clock is, as a multiplier on the interval.
     *
     * <p>1 is "a mutation every interval the tier says"; 0.5 is twice as often. It exists so a
     * single level can be a busier mutation run than its tier without a fifth tier.
     */
    public static final Identifier RULE_MUTATION_INTERVAL_MULTIPLIER =
            id("mutation_interval_multiplier");
    /**
     * A second multiplier on card recharge, owned by the mutations.
     *
     * <p>Deliberately not {@link #RULE_SEED_COOLDOWN_MULTIPLIER}: a level may set that one in its
     * own file, and a mutation that scaled it would have to remember the level's number and put
     * it back - which breaks the moment two mutations both touch it. Two rules whose product is
     * the recharge keeps ownership clean: the level owns one, the mutations own the other, and
     * undoing a mutation only ever divides out its own half.
     */
    public static final Identifier RULE_MUTATION_SEED_COOLDOWN_FACTOR =
            id("mutation_seed_cooldown_factor");
    /**
     * Whether this level's graves give up their dead at the last wave.
     *
     * <p>The name is the original rule's; what it means changed from "roll for a zombie on
     * every grave every tick" to "every grave opens once, when the final wave arrives". The
     * per-tick roll fed a zombie every four seconds from the four graves a night level
     * ships, which is the 2-5 minigame and not what a level wants its scenery to do; the
     * last wave is where the original puts it.
     */
    public static final Identifier RULE_GRAVES_SPAWN_NIGHT = id("graves_spawn_night");
    /**
     * How long a zombie takes to climb out of a grave, in ticks.
     *
     * <p>A rule rather than a constant because it is pacing: a level whose graves open one
     * after another wants the climb to be a beat the player can see, and a level where the
     * whole lawn erupts at once wants it over with.
     */
    public static final Identifier RULE_ZOMBIE_RISE_TICKS = id("zombie_rise_ticks");

    /**
     * How long an endless level waits between rounds for the player's card choice, in ticks.
     *
     * <p>Zero (and unwritten) means the engine's own minute. A level that wants the boundary to
     * carry on by itself shortens it; the point of the rule is that the wait is a level's
     * decision rather than a constant, which is also what lets a demo or a test drive past the
     * boundary without a client that knows how to answer.
     */
    public static final Identifier RULE_ROUND_CLEAR_TIMEOUT_TICKS = id("round_clear_timeout_ticks");

    public static final Identifier ENV_PLANT_AI = id("plant_ai");

    /**
     * Built-in damage types - the answer to "does armour absorb this".
     *
     * <p>{@code pvzce:ash} is the ash line's blast (cherry bomb, Jalapeno, Doom
     * Shroom, potato mine, Squash): it lands on the body, so the cone a pea has to
     * chew through does not save a Conehead from a cherry. {@code projectile} is an
     * ordinary shot and {@code impact} a hit with no projectile to describe (a
     * rolling bowling Wall-nut, a Gargantuar's fist); both let armour absorb first.
     * {@code splash} is the thrown-plant blast - a melon's area damage is authored
     * as a blast in the original too, which is why it shares the ash line's armour
     * rule rather than the shooter's. {@code mower} is the lawn mower and the hammer
     * tool: it does not wear what it hits down, it removes it.
     *
     * <p>{@code spray} is the fume-shroom's cloud, and the only type whose meaning is
     * a <em>slot</em> rather than a yes/no about armour: it goes past what is held in
     * front (a screen door, a newspaper) and is still absorbed by what is worn on the
     * head, which is why {@code DamageTypeDef} needs both flags to describe it.
     *
     * <p>The declarations live in {@code data/pvzce/damage_types/}; these ids exist
     * so code and data cannot drift, exactly as {@link PvzceSounds} does for sounds.
     */
    public static final Identifier DAMAGE_ASH = id("ash");
    public static final Identifier DAMAGE_IMPACT = id("impact");
    public static final Identifier DAMAGE_PROJECTILE = id("projectile");
    public static final Identifier DAMAGE_SPLASH = id("splash");
    public static final Identifier DAMAGE_SPRAY = id("spray");
    /**
     * A shot that lands on a zombie the ice already holds (the ice-boom shroom).
     *
     * <p>Its own type rather than {@code projectile}, because the two differ in what armour does
     * about them: the bolt that shatters a frozen zombie goes past what the zombie holds in front
     * of itself, which is the payoff for freezing it first. The counterpart is the damage type's
     * own file, not this constant.
     */
    public static final Identifier DAMAGE_SHATTER = id("shatter");
    /**
     * The tally the ice line's bolts add up on a zombie before it freezes (see
     * {@code ZombieEntity#addBuildup} and {@code ShatterCapability}).
     *
     * <p>An id rather than a bare string because it is <em>shared</em>: two cold plants in a
     * pack have to count towards the same ice, or a lawn of both would freeze nothing while
     * looking like it should.
     */
    public static final Identifier ICEBOOM_CHILL = id("iceboom_chill");
    public static final Identifier DAMAGE_MOWER = id("mower");
    /**
     * A body pulled under the water (the tangle kelp).
     *
     * <p>Its own type rather than the mower's, even though both ignore armour: the mower throws a
     * head and an arm off as it goes over, and a drowned zombie does not.
     */
    public static final Identifier DAMAGE_DRAG_UNDER = id("drag_under");
    /**
     * Something heavy landing on a zombie (the squash).
     *
     * <p>Ignores armour - a squash flattens a Buckethead, bucket and all, which is the whole
     * point of the plant - but it does <em>not</em> burn. The squash used {@code ash} until the
     * user reported the ash: it inherited the type from the days when it was a proximity
     * explosive, so a squashed zombie was drawn as a charred body with no head and no arms, which
     * is what a cherry bomb does and not what being flattened looks like.
     */
    public static final Identifier DAMAGE_CRUSH = id("crush");

    /**
     * Built-in level mechanics, the ids a level's {@code mechanics} list may name.
     *
     * <p>{@code deck} is the implicit default: a level that declares no card source gets
     * it, which is what keeps every ordinary level's JSON free of a block that only says
     * "the normal rules apply". {@code conveyor} and {@code placement_zone} are the two
     * mechanics Wall-nut Bowling is built from, and they are independent - a normal level
     * may restrict its plantable area without having a belt, and a belt level may use the
     * whole lawn.
     */
    public static final Identifier MECHANIC_DECK = id("deck");
    public static final Identifier MECHANIC_CONVEYOR = id("conveyor");
    public static final Identifier MECHANIC_PLACEMENT_ZONE = id("placement_zone");
    /**
     * Lawn mowers, one per row by default.
     *
     * <p>Unlike the other mechanics this one is <em>implicit</em>: every ordinary level has
     * mowers whether or not its file mentions them, because that is what the original does
     * and a level that lost them would be a different, unfairer level. A level that wants
     * to change them (Wall-nut Bowling has none) declares the mechanic and lists its rows.
     */
    public static final Identifier MECHANIC_MOWER = id("mower");
    /**
     * A tool the level hands the player on its own terms.
     *
     * <p>Not a tool <em>card</em>: the block can reprice or re-time a tool, and it can make one
     * the level's plain click ({@code "default": true}) - the original's mallet in
     * Whack-a-Zombie, which is the cursor rather than a seed packet. It is not a card source, so
     * a level declares it beside its deck.
     */
    public static final Identifier MECHANIC_TOOL = id("tool");
    /**
     * Fog over part of the board (world 4).
     *
     * <p>Read by the client's renderer and by the plantern's own capability; nothing about the
     * simulation consults it, which is what makes the whole feature a presentation one.
     */
    public static final Identifier MECHANIC_FOG = id("fog");
    /**
     * A thunderstorm over the whole board (4-10, the original's own storm level).
     *
     * <p>The fog's sibling and its opposite: the fog darkens one side of the lawn and stays
     * there, while a storm blackens the whole board and lifts it again with every lightning
     * strike. Both are presentation - no rule, hit test or spawn reads either - and both hide
     * what stands where they are dark, through the same render-layer test.
     */
    public static final Identifier MECHANIC_STORM = id("storm");

    /** The plantern's capability id; named here because the level's fog bookkeeping looks it up. */
    public static final Identifier PLANT_CAPABILITY_REVEAL = id("reveal");
    /**
     * Graves that keep giving up their dead while the level runs.
     *
     * <p>Whack-a-Zombie's shape: the level's zombies come out of the gravestones, not off the
     * road, and the graves a player smashes come back. Distinct from the
     * {@code graves_spawn_night} rule, which is the other thing graves do - open once, at the
     * last wave - and which every night level gets by default.
     */
    public static final Identifier MECHANIC_GRAVE_SPAWNER = id("grave_spawner");
    /**
     * Gravestones scattered over part of the lawn when the level starts.
     *
     * <p>The other half of the original's night lawns. Every night level from 2-1 on opens with
     * tombstones standing in the half of the lawn furthest from the house, in a layout that is
     * different every time; they block planting, and the {@code graves_spawn_night} rule opens
     * whatever is still standing at the final wave. Where they stand is a property of the lawn
     * rather than of the level file, which is why it is a mechanic and not a list of cells in
     * {@code scene}: the file cannot say "seven of them, over there".
     */
    public static final Identifier MECHANIC_GRAVE_FIELD = id("grave_field");
    /**
     * How this level's zombies arrive, wave by wave.
     *
     * <p>The wave table says <em>what</em> comes and roughly when; this says how the clock
     * behaves around it - whether a cleared lawn shortens the next wave's countdown, whether a
     * wave keeps a presence on the lawn or trickles on its written interval, whether a wave waits
     * to be finished before the next one is allowed, and whether a wave's composition is a
     * written list or a point budget spent on a pool.
     *
     * <p>Implicit, like the mowers: a level that declares nothing still gets the clear bonus,
     * because "the player killed everything and is now waiting out a countdown written for a
     * slower player" is a defect rather than a mode.
     */
    public static final Identifier MECHANIC_WAVE_PACING = id("wave_pacing");
    /**
     * Waves that never run out: the original's Survival Endless.
     *
     * <p>A level with this mechanic cycles its wave table, inflating each round, and has no
     * victory condition at all - the only way out is a zombie reaching the house. It is the
     * second half of {@link #MECHANIC_MUTATION}, which needs a level that runs long enough for
     * its mutations to matter.
     */
    public static final Identifier MECHANIC_ENDLESS = id("endless");
    /**
     * The preparation phase: the stretch before the first wave, where the player spends the sun the
     * level gave them and starts the waves themselves. See {@code PreparationMechanic}.
     */
    public static final Identifier MECHANIC_PREPARATION = id("preparation");
    /** 斗转星移: pairs of cells a ground zombie travels between. */
    public static final Identifier MECHANIC_PORTAL = id("portal");
    public static final Identifier MECHANIC_RESONANCE = id("resonance");
    public static final Identifier ECHO_LILY = id("echo_lily");
    public static final Identifier RESONANCE_MOSS = id("resonance_moss");
    public static final Identifier ECHO_WAVE = id("echo_wave");
    public static final Identifier ECHO_RING = id("echo_ring");
    public static final Identifier ECHO_CHIME = id("original/echo_chime");
    /** 种子雨: seed packets the level drops onto the lawn on a clock. */
    public static final Identifier MECHANIC_SEED_RAIN = id("seed_rain");
    /** 我是僵尸: the enemy's garden, laid out round by round. */
    public static final Identifier MECHANIC_PLANT_GARDEN = id("plant_garden");
    /** 节奏草坪: a chart the player plays on the keyboard, and the lanes that answer it. */
    public static final Identifier MECHANIC_RHYTHM = id("rhythm");
    /**
     * 对战: one human against a Jev-driven opponent, on a lawn split between them.
     *
     * <p>The plant side races a sun total while the zombie side tries to break through. Both
     * sides' decks, the opening sun each side gets and the zombie side's income are declared in
     * this one block: the mode is one statement about the level rather than five blocks that have
     * to agree with each other, and it is also the block a level is recognised by (the server
     * reads it to know which side the opponent plays and where each side may place).
     */
    public static final Identifier MECHANIC_VERSUS = id("versus");
    /**
     * Where a level lets the zombie side put its zombies down.
     *
     * <p>The mirror of {@link #MECHANIC_PLACEMENT_ZONE}: that one is the plantable area, this one
     * is the area a zombie card may be spent on. A versus level declares both, which is how
     * "plants on the left five columns, zombies on the right four" is written down once per side
     * instead of being a rule hidden inside either of them.
     */
    public static final Identifier MECHANIC_ZOMBIE_ZONE = id("zombie_zone");
    /**
     * The mutation system: a level where the rules themselves are rewritten every so often.
     *
     * <p>The id of a mechanic with no block of its own (the same shape as
     * {@link #MECHANIC_DECK}): which mutation appears, when, and how strong is code rather than
     * level data, so declaring this mechanic is the whole statement. See
     * {@code common.level.mutation.Mutations} for the catalogue.
     */
    public static final Identifier MECHANIC_MUTATION = id("mutation");

    /** The level category the plain (mutation-free) endless levels live under. */
    public static final Identifier CATEGORY_SURVIVAL = id("survival");

    /**
     * The two built-in endless schedules, the growth curves
     * {@code common.level.endless.EndlessSchedules} registers.
     *
     * <p>Named here for the same reason the mutations below are: the levels, the language files
     * and the tests all have to name one, and "which curve is this level on" is exactly the kind
     * of fact that otherwise ends up written twice.
     */
    public static final Identifier ENDLESS_SCHEDULE_POOL = id("pool_endless");
    public static final Identifier ENDLESS_SCHEDULE_MUTATION = id("mutation_endless");
    /**
     * The rhythm levels' zombie clock.
     *
     * <p>A schedule like any other - the same record, the same generator - but its "round" is where
     * in the song the run is rather than how far into an endless run it has got, so its ramp is the
     * shape of one track. See {@code EndlessSchedules.rhythmLawn}.
     */
    public static final Identifier ENDLESS_SCHEDULE_RHYTHM = id("rhythm_lawn");

    /**
     * The built-in mutations, the catalogue {@code common.level.mutation.Mutations} registers.
     *
     * <p>One constant per mutation rather than leaving the ids to the classes that implement
     * them: the level list, the language files and the tests all need to name a mutation, and
     * "the id a player sees on the panel" is exactly the kind of fact that ends up written
     * twice. The names are the ids - {@code pvzce.slot_replace} is the whole description, and
     * the number any one of them rolls is in its own class, not here.
     */
    public static final Identifier MUTATION_SLOT_REPLACE = id("slot_replace");
    public static final Identifier MUTATION_CONVEYOR = id("conveyor");
    public static final Identifier MUTATION_SUN_RATE = id("sun_rate");
    public static final Identifier MUTATION_PLANT_ATTACK_RATE = id("plant_attack_rate");
    public static final Identifier MUTATION_ZOMBIE_SPEED = id("zombie_speed");
    public static final Identifier MUTATION_ZOMBIE_SPAWN_RATE = id("zombie_spawn_rate");
    public static final Identifier MUTATION_PLANT_SUN_COST = id("plant_sun_cost");
    public static final Identifier MUTATION_NIGHTFALL = id("nightfall");
    public static final Identifier MUTATION_BOWLING_NUT = id("bowling_nut");
    public static final Identifier MUTATION_WHACK_A_ZOMBIE = id("whack_a_zombie");
    public static final Identifier MUTATION_GRAVE_GROWTH = id("grave_growth");
    public static final Identifier MUTATION_ZOMBIE_CRISIS = id("zombie_crisis");
    public static final Identifier MUTATION_BUFF_SHIFT = id("buff_shift");
    public static final Identifier MUTATION_APOCALYPSE = id("apocalypse");
    public static final Identifier MUTATION_ZOMBIE_BLAST = id("zombie_blast");
    public static final Identifier MUTATION_PLANT_BLAST = id("plant_blast");
    public static final Identifier MUTATION_MENDEL = id("mendel");
    public static final Identifier MUTATION_KELP_SPREAD = id("kelp_spread");

    // The second catalogue. Grouped the way `Mutations.bootstrap` registers them, which is also
    // the order the panel lists them in and the order the dice walk them.

    /** 僵尸强化: every zombie that arrives has a rolled multiple of its own health. */
    public static final Identifier MUTATION_ZOMBIE_HEALTH = id("zombie_health");
    /** 植物脆化: every plant that is planted has a rolled fraction of its own health. */
    public static final Identifier MUTATION_PLANT_FRAGILE = id("plant_fragile");
    /** 卡片冷却: every card's recharge is scaled, the bar and the belt alike. */
    public static final Identifier MUTATION_CARD_COOLDOWN = id("card_cooldown");
    /** 阳光暴雨: extra sun falls from the sky on a clock of the mutation's own. */
    public static final Identifier MUTATION_SUN_SHOWER = id("sun_shower");

    /** 卡槽封锁: one or two plant cards are locked for as long as the mutation stands. */
    public static final Identifier MUTATION_SLOT_LOCK = id("slot_lock");
    /** 卡槽轮盘: a plant card is swapped for another one, over and over. */
    public static final Identifier MUTATION_SLOT_ROULETTE = id("slot_roulette");

    /** 迷雾降临: the right half of the board is covered by fog. */
    public static final Identifier MUTATION_FOG_ROLL_IN = id("fog_roll_in");
    /** 水淹草坪: the middle rows become water. */
    public static final Identifier MUTATION_FLOOD_LAWN = id("flood_lawn");
    /** 陨石雨: rocks fall on the lawn, leaving craters. */
    public static final Identifier MUTATION_METEOR_SHOWER = id("meteor_shower");
    /** 荆棘草坪: anything walking on the ground bleeds. */
    public static final Identifier MUTATION_THORN_LAWN = id("thorn_lawn");
    /** 结冰地面: zombies walk half again as fast and stay frozen twice as long. */
    public static final Identifier MUTATION_ICE_GROUND = id("ice_ground");

    /** 植物僵尸: a plant-headed zombie is sent in every so often. */
    public static final Identifier MUTATION_ZOMBOTANY = id("zombotany");
    /** 巨人突袭: a Gargantuar, with the Imp that rides it. */
    public static final Identifier MUTATION_GARGANTUAR_RAID = id("gargantuar_raid");
    /** 小鬼空投: an Imp is dropped straight onto the lawn. */
    public static final Identifier MUTATION_IMP_AIRDROP = id("imp_airdrop");
    /** 蹦极僵尸: one arrives, takes a plant, and leaves. */
    public static final Identifier MUTATION_BUNGEE_RAID = id("bungee_raid");
    /** 气球空袭: a small flock of balloons, all in one lane. */
    public static final Identifier MUTATION_BALLOON_RAID = id("balloon_raid");

    /** 豌豆派对: plants that do not shoot are given a gun. */
    public static final Identifier MUTATION_PEA_PARTY = id("pea_party");
    /** 割草机补给: a spent mower comes back. */
    public static final Identifier MUTATION_RANDOM_SUPPLY = id("random_supply");
    /** 割草机自走: an unspent mower is released on its own. */
    public static final Identifier MUTATION_AUTO_RELEASE = id("auto_release");
    /** 阳光流失: the bank leaks. */
    public static final Identifier MUTATION_SUN_DRAIN = id("sun_drain");

    /**
     * The zombies a {@code zombie_crisis} may pick from: {@code #pvzce:mutation_crisis}.
     *
     * <p>A tag rather than a hardcoded list, so a level pack (or a mod) can widen or narrow the
     * pool without touching code - which is the whole reason the mutation rolls a <em>subject</em>
     * out of a tag instead of naming the one zombie it spawns.
     */
    public static final Identifier TAG_ZOMBIE_MUTATION_CRISIS = id("mutation_crisis");

    /**
     * The zombies a {@code zombotany} raid may pick from: {@code #pvzce:mutation_zombotany}.
     *
     * <p>A tag for the same reason the crisis pool is one: which plant-headed zombies exist is
     * content, and a pack that ships a fifth should be able to say so without touching code.
     */
    public static final Identifier TAG_ZOMBIE_MUTATION_ZOMBOTANY = id("mutation_zombotany");

    /**
     * The plants and tools the mutation catalogue names by hand.
     *
     * <p>Here rather than inline in the mutations for the same reason every other id is: the
     * content files spell these names, and a mutation that hardcoded {@code "bowling_nut"} would
     * be a second copy of a string the data pack owns.
     */
    public static final Identifier WALL_NUT = id("wall_nut");
    public static final Identifier BOWLING_NUT = id("bowling_nut");
    public static final Identifier TANGLE_KELP = id("tangle_kelp");
    public static final Identifier DOOM_SHROOM = id("doom_shroom");
    public static final Identifier HAMMER = id("hammer");
    public static final Identifier PEASHOOTER_PEA = id("pea");
    public static final Identifier SNOW_PEA = id("snow_pea");
    public static final Identifier FIRE_PEA = id("fire_pea");

    /**
     * Automatic pickup of sun and coins: no clicking.
     *
     * <p>The first {@link com.pvzce.api.content.LevelBuff}. Unlike the mechanics above it is the
     * <em>player's</em> choice rather than the level's - it is switched on from the seed
     * chooser's buff tab - but the level may still pin it, which is how a custom level says
     * "this one is always on here".
     */
    public static final Identifier BUFF_AUTO_COLLECT = id("auto_collect");
    /**
     * Spore-shooting mushrooms reach 1.5x as far.
     *
     * <p>Only the id lives here; the factor is
     * {@code BuiltInBuffs.MUSHROOM_RANGE_FACTOR}, because this table is names, not numbers.
     */
    public static final Identifier BUFF_MUSHROOM_RANGE = id("mushroom_range");
    /**
     * The fog over world 4 is a column and a half shorter.
     *
     * <p>The reward for 4-9, and the one buff that exists purely so the player can answer the
     * thing that has been beating them for nine levels. The distance is
     * {@code BuiltInBuffs.FOG_RETREAT_COLUMNS}; this table is names, not numbers.
     */
    public static final Identifier BUFF_FOG_RETREAT = id("fog_retreat");
    /**
     * Digging a plant up returns a fifth of what it cost.
     *
     * <p>The shop's third item, and the only buff in the game that is not handed out by a level.
     * Read by {@code LevelServer}'s shovel branch through {@code LevelBuff.shovelRefundFraction}.
     */
    public static final Identifier BUFF_SUN_SHOVEL = id("sun_shovel");
    /**
     * A planted tangle kelp grows another one beside it.
     *
     * <p>The 3-9 reward. The same effect the mutation of the same name applies on a timer; the
     * rule is shared, only the trigger differs.
     */
    public static final Identifier BUFF_KELP_SPREAD = id("kelp_spread");

    /**
     * The shop's one item that is nothing but a shop item.
     *
     * <p>The other two are not repeated here: the sun shovel's item id <em>is</em>
     * {@link #BUFF_SUN_SHOVEL}, and the rake's <em>is</em> {@link #RAKE} - the same id the mechanic
     * is registered under. An item id that shadowed the thing it grants would be a second name for
     * one fact, and "do I own this" would have two answers.
     */
    public static final Identifier SHOP_CARD_SLOT = id("card_slot");

    /**
     * The rake: a one-shot guard that flattens the first zombie to reach it.
     *
     * <p>Two registries share the name, on purpose, and they are the two halves of one purchase.
     * {@link #RAKE} is what the shop grants and what "do I own the rake" is asked about (a player's
     * profile holds a set of unlocked ids); this is the <em>mechanic</em> that lays one down at the
     * start of a level that did not say otherwise. A second id for the ownership flag would be a
     * second answer to the same question.
     *
     * <p>Neither is a card. The rake used to be one - a tool card with an effect id nothing
     * implemented - and removing it is what makes "the rake is not a tool" true in the data rather
     * than only in the intent.
     */
    public static final Identifier MECHANIC_RAKE = id("rake");

    /** The rake as a purchase: the shop item id and what the profile's unlocked set holds. */
    public static final Identifier RAKE = id("rake");

    /**
     * The mechanic that stands vases on the lawn at the start of a level.
     *
     * <p>Named {@code vase_field} rather than {@code vase} for the same reason the gravestone pair
     * is {@code grave_field} / {@code grave_spawner}: the bare name belongs to the <em>thing</em>
     * (the {@code pvzce:vase} scene element, the {@code pvzce:vase} tool card), and a mechanic that
     * shared it would make "the level declares a vase" and "this cell is a vase" the same string.
     */
    public static final Identifier MECHANIC_VASE_FIELD = id("vase_field");

    /**
     * The mechanic that fills a lawn with pots, in rounds, for the original's Scary Potter.
     *
     * <p>Distinct from {@link #MECHANIC_VASE_FIELD}: a vase field is a fixed handful of vases
     * holding cards, and a scary potter level is rounds of scattered pots holding cards
     * <em>or zombies</em>, with the level's end tied to the last one.
     */
    public static final Identifier MECHANIC_SCARY_POTTER = id("scary_potter");

    /** Scene element surface classes (compare with {@link #GRASS}-style element ids). */
    public static final String SURFACE_GRASS = "GRASS";
    public static final String SURFACE_GROUND = "GROUND";
    public static final String SURFACE_WATER = "WATER";
    public static final String SURFACE_ROOF = "ROOF";
    public static final String SURFACE_ROOF_SLOPE = "ROOF_SLOPE";
    public static final String SURFACE_CRATER = "CRATER";
    public static final String SURFACE_GRAVE = "GRAVE";

    /**
     * The placement "feet" strings are gone. What a plant may be planted on is
     * now {@code #c:*} tags ({@code #c:plantable}, {@code #c:water},
     * {@code #c:requires_ground}, ...) declared in
     * {@code data/c/tags/}; see {@link com.pvzce.common.tag.PvzceTags} and
     * {@link com.pvzce.common.core.PlantPlacement}.
     */

    public static Identifier id(String path) {
        return Identifier.withDefaultNamespace(path);
    }

    private PvzceIds() {
    }
}
