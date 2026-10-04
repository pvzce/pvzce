package com.pvzce.common.capability.plant;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.capability.PlantCapability;
import com.pvzce.api.entity.EntityAnimations;
import com.pvzce.api.entity.LevelAccess;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.common.nbt.ListTag;
import com.pvzce.common.PvzceConstants;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.PvzceParticles;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Periodically spawns resource drops (sunflower sun, marigold coins).
 *
 * <p>Fixes a long-standing data bug: {@code PlantSounds.produce} was parsed from
 * JSON but never emitted, so a sunflower's {@code sfx/ui/points} override was
 * silently ignored. The production sound is now played at the moment of
 * production, where the JSON always claimed it would be.
 *
 * <p><b>A producer that declares no sound is silent</b>, and that is a real answer rather
 * than a missing value: the sunflower used to fall back to {@code sfx/ui/points}, which is
 * the sound of <em>collecting</em> a sun - so a flower that had just made one played the
 * chime of somebody picking it up, every eighteen seconds, whether or not anybody clicked
 * it. The original's sunflower is silent when it produces; the chime belongs to the pickup,
 * which is where {@code ResourceDef.pickup_sound} already plays it. The marigold
 * deliberately keeps its own coin ring, because its drop is small change rather than the
 * resource the level is played with.
 */
public final class ProducerCapability implements PlantCapability {
    public static final int DEFAULT_AMOUNT = 25;
    /** The first drop arrives sooner than the steady-state interval. */
    public static final int DEFAULT_FIRST_DELAY = 300;

    private final Identifier resource;
    /** Total output per production cycle, shared by all drops of the batch. */
    private final int amount;
    private final int dropCount;
    private final List<PendingDrop> pendingDrops = new ArrayList<>();
    private final int everyTicks;
    private final int firstDelayTicks;
    private final Optional<Identifier> sound;
    /** What this producer's drop is drawn at, before growth. */
    private final float dropScale;
    /**
     * How this producer grows up, or {@code null} for one that does not.
     *
     * <p>Growth belongs on the producer rather than on a capability of its own because the
     * two things it changes are both the producer's: how much the drop is worth and how big
     * the drop is drawn. The art variant is published through
     * {@link com.pvzce.server.entity.PlantEntity#setState}.
     */
    private final Growth growth;

    private int cooldown;
    /** This producer's own progress clock; see {@code ShooterCapability} for why it is not shared. */
    private final com.pvzce.common.level.RateClock clock =
            new com.pvzce.common.level.RateClock();
    /** Ticks until this producer grows, or 0 when it has no growth left to do. */
    private int growTicks;
    /** Ticks of the grow performance left; the plant produces nothing while it plays. */
    private int growingTicks;

    /**
     * A producer that grows into a bigger one: the sun-shroom, whose small form makes a
     * 15-value sun and whose grown form makes a 25-value one.
     *
     * @param afterTicks  how long after planting the growth happens
     * @param amount      what the drop is worth afterwards
     * @param dropScale   how big the drop is drawn afterwards
     * @param variant     clip-name suffix of the grown art ({@code big} for {@code idle_big})
     * @param clipTicks   how long the {@code grow} performance lasts; production pauses for it
     */
    public record Growth(int afterTicks, int amount, float dropScale, String variant, int clipTicks,
                         Identifier sound) {
        public static final int DEFAULT_CLIP_TICKS = 60;

        public static final MapCodec<Growth> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.INT.fieldOf("after_ticks").forGetter(Growth::afterTicks),
                Codec.INT.fieldOf("amount").forGetter(Growth::amount),
                Codec.FLOAT.optionalFieldOf("drop_scale", 1F).forGetter(Growth::dropScale),
                Codec.STRING.optionalFieldOf("variant", "big").forGetter(Growth::variant),
                Codec.INT.optionalFieldOf("clip_ticks", DEFAULT_CLIP_TICKS).forGetter(Growth::clipTicks),
                // Growing is an event, so it has a sound by default rather than being silent
                // by default: the original plays one when a plant matures, and a mushroom
                // that doubles in size in silence reads as a graphics glitch.
                Identifier.CODEC.optionalFieldOf("sound", com.pvzce.common.PvzceSounds.PLANT_GROW)
                        .forGetter(Growth::sound)
        ).apply(i, Growth::new));
    }

    public ProducerCapability(Identifier resource, int amount, int everyTicks, int firstDelayTicks,
                              Optional<Identifier> sound) {
        this(resource, amount, everyTicks, firstDelayTicks, sound,
                com.pvzce.common.network.packet.EntitySpawnS2C.DEFAULT_SCALE, Optional.empty());
    }

    public ProducerCapability(Identifier resource, int amount, int everyTicks, int firstDelayTicks,
                              Optional<Identifier> sound, float dropScale, Optional<Growth> growth) {
        this(resource, amount, everyTicks, firstDelayTicks, sound, dropScale, growth, 1);
    }

    public ProducerCapability(Identifier resource, int amount, int everyTicks, int firstDelayTicks,
                              Optional<Identifier> sound, float dropScale, Optional<Growth> growth, int dropCount) {
        this.resource = resource;
        this.amount = Math.max(1, amount);
        this.dropCount = Math.max(1, dropCount);
        this.everyTicks = Math.max(1, everyTicks);
        this.firstDelayTicks = firstDelayTicks < 0
                ? Math.min(this.everyTicks, DEFAULT_FIRST_DELAY)
                : Math.min(firstDelayTicks, this.everyTicks);
        this.sound = sound;
        this.dropScale = dropScale <= 0F
                ? com.pvzce.common.network.packet.EntitySpawnS2C.DEFAULT_SCALE
                : dropScale;
        this.growth = growth == null ? null : growth.orElse(null);
        this.growTicks = this.growth == null ? 0 : Math.max(1, this.growth.afterTicks());
        this.cooldown = this.firstDelayTicks;
    }

    public static final MapCodec<ProducerCapability> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Identifier.CODEC.fieldOf("resource").forGetter(ProducerCapability::resource),
            Codec.INT.optionalFieldOf("amount", DEFAULT_AMOUNT).forGetter(ProducerCapability::amount),
            Codec.INT.fieldOf("every").forGetter(ProducerCapability::everyTicks),
            Codec.INT.optionalFieldOf("first_delay", -1).forGetter(ProducerCapability::firstDelayTicks),
            Identifier.CODEC.optionalFieldOf("sound").forGetter(ProducerCapability::sound),
            Codec.FLOAT.optionalFieldOf("drop_scale",
                    com.pvzce.common.network.packet.EntitySpawnS2C.DEFAULT_SCALE)
                    .forGetter(ProducerCapability::dropScale),
            Growth.CODEC.codec().optionalFieldOf("grow").forGetter(ProducerCapability::growth),
            Codec.INT.optionalFieldOf("drop_count", 1).forGetter(ProducerCapability::dropCount)
    ).apply(i, ProducerCapability::new));

    public Identifier resource() {
        return resource;
    }

    public int amount() {
        return amount;
    }

    public int dropCount() {
        return dropCount;
    }

    public int everyTicks() {
        return everyTicks;
    }

    public int firstDelayTicks() {
        return firstDelayTicks;
    }

    public Optional<Identifier> sound() {
        return sound;
    }

    /** How this producer grows up, or empty for one that does not. */
    public Optional<Growth> growth() {
        return Optional.ofNullable(growth);
    }

    /** What this producer's drop is drawn at right now, grown or not. */
    public float dropScale() {
        return isGrown() ? growth.dropScale() : dropScale;
    }

    /** Total output per production cycle right now, including growth. */
    public int currentAmount() {
        return isGrown() ? growth.amount() : amount;
    }

    /** True once this producer has grown into its bigger form. */
    public boolean isGrown() {
        return growth != null && growTicks <= 0;
    }

    /** How the plant's art is suffixed once grown ({@code big} → {@code idle_big}). */
    @Override
    public String variantSuffix(PlantEntity plant) {
        return isGrown() ? growth.variant() : "";
    }

    @Override
    public PlantCapability instantiate() {
        return new ProducerCapability(resource, amount, everyTicks, firstDelayTicks, sound,
                dropScale, growth(), dropCount);
    }

    @Override
    public void tick(PlantEntity plant, LevelAccess level) {
        // Promised drops keep their own short visual delay while the production
        // clock advances, so batches cannot overwrite one another at high rates.
        for (var iterator = pendingDrops.iterator(); iterator.hasNext();) {
            PendingDrop drop = iterator.next();
            if (--drop.ticks <= 0) {
                level.spawnProducedResource(resource, drop.amount, plant.cellX(), plant.cellY(),
                        plant.team(), drop.scale, drop.driftX, plant.surfaceId());
                iterator.remove();
            }
        }
        if (growingTicks > 0) {
            // The growth performance owns the plant for its second or so: it is the one
            // moment the art is neither form, and producing through it would drop a sun out
            // of a mushroom that is visibly mid-transformation.
            growingTicks--;
            // `grow` is one clip for both forms, so it is published raw: suffixing it would
            // ask for a `grow_big` that no artist drew.
            plant.setAnimation(EntityAnimations.GROW);
            return;
        }
        if (growth != null && growTicks > 0 && --growTicks <= 0) {
            // `isGrown` is true from here on, so the very next state this producer publishes
            // is already the grown one and the clip below runs into `idle_big` (the clip's own
            // `on_end`), not into the small idle.
            growingTicks = Math.max(1, growth.clipTicks());
            plant.setAnimation(EntityAnimations.GROW);
            level.emitEffect("", plant.position(), plant.surfaceId(), growth.sound());
            return;
        }
        if (cooldown > 0) {
            // The plant's own clock: a watered plant counts a quarter faster, and the level's
            // `sun_rate_multiplier` counts it faster still. Read from the live rules rather than
            // captured at planting time, so a mutation that rewrites the sun rate reaches the
            // sunflowers that are already standing on the lawn.
            cooldown -= clock.step(plant.actionRate(
                    level.rules().getFloat(PvzceIds.RULE_SUN_RATE_MULTIPLIER)));
        }
        if (cooldown > 0) {
            plant.setState(EntityAnimations.IDLE);
            return;
        }
        cooldown = everyTicks;
        plant.setState(EntityAnimations.PRODUCE);
        int total = currentAmount();
        int count = Math.min(dropCount, total);
        if (count == 1) {
            level.spawnProducedResource(resource, total, plant.cellX(), plant.cellY(), plant.team(), dropScale(), plant.surfaceId());
        } else {
            for (int index = 0; index < count; index++) {
                int value = total / count + (index < total % count ? 1 : 0);
                float drift = (index - (count - 1) / 2F) * PvzceConstants.PRODUCER_DROP_SPACING_CELLS;
                if (index == 0) {
                    level.spawnProducedResource(resource, value, plant.cellX(), plant.cellY(),
                            plant.team(), dropScale(), drift, plant.surfaceId());
                } else {
                    pendingDrops.add(new PendingDrop(index * PvzceConstants.PRODUCER_DROP_GAP_TICKS,
                            value, dropScale(), drift));
                }
            }
        }
        // No fallback sound: "the definition did not name one" means the plant makes no
        // noise, and the chime that used to stand in for it belongs to the pickup. An
        // empty id is what LevelServer already reads as "play nothing".
        level.emitEffect(PvzceParticles.LANTERN_SHINE.toString(), plant.position(), plant.surfaceId(), sound.or(() -> plant.def().sounds().produce()).orElse(null));
    }

    /**
     * Watering ripens it: the sun-shroom grows up on the spot.
     *
     * <p>The garden's own rule - watering is what makes a plant grow - and the only capability
     * in the game that answers the watering can with something the player can see. A producer
     * that has already grown reports that nothing happened, so pouring water on a sunflower
     * stays a wasted click rather than a second, weaker growth.
     */
    @Override
    public boolean water(PlantEntity plant, LevelAccess level) {
        if (growth == null || growTicks <= 0) {
            return false;
        }
        growTicks = 1;
        return true;
    }

    @Override
    public void save(CompoundTag tag) {
        tag.putInt("cooldown", cooldown);
        clock.save(tag);
        tag.putInt("growTicks", growTicks);
        tag.putInt("growingTicks", growingTicks);
        ListTag drops = new ListTag();
        for (PendingDrop drop : pendingDrops) {
            CompoundTag saved = new CompoundTag();
            saved.putInt("ticks", drop.ticks);
            saved.putInt("amount", drop.amount);
            saved.putFloat("scale", drop.scale);
            saved.putFloat("driftX", drop.driftX);
            drops.add(saved);
        }
        tag.put("pendingDrops", drops);
    }

    @Override
    public void load(CompoundTag tag) {
        cooldown = tag.getInt("cooldown");
        clock.load(tag);
        pendingDrops.clear();
        for (var element : tag.getList("pendingDrops").values()) {
            if (element instanceof CompoundTag saved && saved.getInt("amount") > 0) {
                pendingDrops.add(new PendingDrop(Math.max(1, saved.getInt("ticks")),
                        saved.getInt("amount"), saved.getFloat("scale"), saved.getFloat("driftX")));
            }
        }
        // A plant saved mid-growth finishes growing where it left off; one saved by a build
        // without growth, or by a definition that has none, keeps the timer it was built
        // with (see the constructor), so the block below only applies to a real growth.
        if (growth != null) {
            growTicks = Math.max(0, tag.getInt("growTicks"));
            growingTicks = Math.max(0, tag.getInt("growingTicks"));
        }
    }

    private static final class PendingDrop {
        int ticks;
        final int amount;
        final float scale;
        final float driftX;

        PendingDrop(int ticks, int amount, float scale, float driftX) {
            this.ticks = ticks;
            this.amount = amount;
            this.scale = scale;
            this.driftX = driftX;
        }
    }

}
