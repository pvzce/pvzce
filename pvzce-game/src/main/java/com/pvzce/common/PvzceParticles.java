package com.pvzce.common;

import com.pvzce.api.util.Identifier;

/**
 * Every built-in default particle id in one place.
 *
 * <p>Mirrors {@link PvzceSounds}: the ids were string literals scattered across the
 * capabilities, and the particle engine only reported an unknown one once per
 * second, so a typo showed up as "the effect is missing" with nothing pointing at
 * the emitter. Content may still name any registered particle directly - these are
 * only the defaults the built-in capabilities reach for.
 *
 * <p>All of them come from the original's own particle set, converted by
 * {@code tools/particles_to_pvzce.py}; the file names under
 * {@code data/pvzce/particles/} are the original emitter names in snake_case.
 */
public final class PvzceParticles {
    // Every constant below resolves to a definition the shipped pack actually contains,
    // so the reload-time check stays quiet. Where the original's own sprite for an effect
    // is not in the working copy of refer/im7/particles, the closest one that is stands in
    // and the comment says which effect it is really for - see
    // tools/particles_to_pvzce.py, which regenerates the whole set from that directory.
    /**
     * What any projectile hit looks like on a zombie: a small spark.
     *
     * <p>This used to be {@code zombie_head} - literally the zombie's head coming off -
     * which fired on every single hit. The head belongs to the death effect; a hit is a
     * spark, and armour breaking is its own sprite.
     */
    public static final Identifier HIT_SPARK = id("starburst");
    /** The specks that fly off a plant-based impact. */
    public static final Identifier PEA_SPLAT_BITS = id("starburst_2");
    /** A kernel or butter impact, tinted differently from a pea. */
    public static final Identifier KERNEL_SPLAT = id("starburst_1");
    /**
     * A melon bursting.
     *
     * <p>Also the generic heavy-impact burst: the original's Powie is the cloud a big
     * hit leaves, which is why the ash line's blast uses it too.
     */
    public static final Identifier MELON_IMPACT = id("powie");
    /** Water thrown up by something entering it. */
    public static final Identifier POOL_SPLASH = id("pool_sparkly");
    /** Sparkle left on the water surface. */
    public static final Identifier POOL_SPARKLY = id("pool_sparkly_1");
    /** The muzzle puff every shooter makes. */
    public static final Identifier PUFF_SHROOM_MUZZLE = id("puff_shroom_muzzle");
    /** The ash line's blast. */
    public static final Identifier EXPLOSION_POW = id("pow");
    /** The larger blast. */
    public static final Identifier EXPLOSION_POWIE = id("powie");
    /** The scorch mark an explosion leaves. */
    public static final Identifier BLAST_MARK = id("blast_mark");
    /** The doom shroom's own mushroom cloud. */
    public static final Identifier DOOM = id("doom");
    /** The mine's flash. */
    public static final Identifier POTATO_MINE_FLASH = id("potato_mine_flash");
    /** The mine emerging. */
    public static final Identifier POTATO_MINE_RISE = id("potato_mine");
    /** The chomper's bite. */
    public static final Identifier CHOMP = id("puff_splat");
    /** A zombie's head coming off. */
    public static final Identifier ZOMBIE_HEAD = id("zombie_head");
    /** A zombie's arm coming off. */
    public static final Identifier ZOMBIE_ARM = id("zombie_arm");
    /**
     * The football helmet, kept for content that wears one.
     *
     * <p>It used to be what <em>every</em> armour hit emitted, so a conehead showered
     * football helmets with each pea. Armour now names its own sprite through
     * {@code EquipmentDef.drop_particle}.
     */
    public static final Identifier ZOMBIE_HELMET = id("zombie_helmet");
    /** A worn cone breaking off. */
    public static final Identifier ZOMBIE_TRAFFIC_CONE = id("zombie_traffic_cone");
    /** A worn bucket breaking off. */
    public static final Identifier ZOMBIE_PAIL = id("zombie_pail");
    /** A screen door breaking off. */
    public static final Identifier ZOMBIE_DOOR = id("zombie_door");
    /** A newspaper breaking off. */
    public static final Identifier ZOMBIE_NEWSPAPER = id("zombie_newspaper");
    /** The flag zombie's flag, dropped. */
    public static final Identifier ZOMBIE_FLAG = id("zombie_flag");
    /** A zombie rising out of the ground. */
    public static final Identifier ZOMBIE_RISE = id("zombie_rise");
    /** A spadeful of dirt. */
    public static final Identifier DIRT_SMALL = id("dirt_clump");
    /** A larger dirt burst; the same clump sprite, spawned in a bigger burst. */
    public static final Identifier DIRT_BIG = id("dirt_clump");
    /** Ice and snow. */
    public static final Identifier ICE_SPARKLE = id("ice_trap");
    /** A snow pea's impact. */
    public static final Identifier SNOW_PEA_SPLAT = id("snow_puff");
    /** A soft glow on a pickup. */
    public static final Identifier LANTERN_SHINE = id("lantern_shine");

    // The lawn mower's three, from the original's own emitters. They were converted with
    // the rest of the set and then had no emitter for as long as the mower did not exist.
    /** The head that comes off a mowed zombie. */
    public static final Identifier MOWERED_ZOMBIE_HEAD = id("mowered_zombie_head");
    /** And its arm. */
    public static final Identifier MOWERED_ZOMBIE_ARM = id("mowered_zombie_arm");
    /** The dust cloud the mower drags behind it. */
    public static final Identifier MOWER_CLOUD = id("mower_cloud");
    /** The puffy cloud it kicks up on a hit. */
    public static final Identifier MOWER_CLOUD_POWIE = id("mower_cloud_powie_big_clouds");

    /**
     * The three puffs of a sleeping mushroom's breath, smallest first.
     *
     * <p>Three sizes rather than one scaled puff because a particle's size is a number in its
     * definition (see {@code tools/gen_zzz_sprite.py}): the spiral a sleeper breathes out is
     * then three definitions and the emitting code just walks the list.
     */
    public static final java.util.List<Identifier> SLEEP_ZZZ = java.util.List.of(
            id("zzz_1"), id("zzz_2"), id("zzz_3"));

    /** Every constant above, so a validator can check they all resolve. */
    public static java.util.List<Identifier> all() {
        return java.util.List.of(
                HIT_SPARK, PEA_SPLAT_BITS, KERNEL_SPLAT, MELON_IMPACT, POOL_SPLASH, POOL_SPARKLY,
                PUFF_SHROOM_MUZZLE, EXPLOSION_POW, EXPLOSION_POWIE, BLAST_MARK, DOOM,
                POTATO_MINE_FLASH, POTATO_MINE_RISE, CHOMP, ZOMBIE_HEAD, ZOMBIE_ARM,
                ZOMBIE_HELMET, ZOMBIE_TRAFFIC_CONE, ZOMBIE_PAIL, ZOMBIE_DOOR, ZOMBIE_NEWSPAPER,
                ZOMBIE_FLAG, ZOMBIE_RISE, DIRT_SMALL, DIRT_BIG, ICE_SPARKLE, SNOW_PEA_SPLAT,
                LANTERN_SHINE, MOWERED_ZOMBIE_HEAD, MOWERED_ZOMBIE_ARM, MOWER_CLOUD,
                MOWER_CLOUD_POWIE, id("zzz_1"), id("zzz_2"), id("zzz_3"));
    }

    public static Identifier id(String path) {
        return Identifier.withDefaultNamespace(path);
    }

    private PvzceParticles() {
    }
}
