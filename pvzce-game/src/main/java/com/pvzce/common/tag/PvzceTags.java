package com.pvzce.common.tag;

import com.pvzce.api.content.PlantDef;
import com.pvzce.api.content.SceneElementDef;
import com.pvzce.api.content.ZombieDef;
import com.pvzce.api.registry.Registry;
import com.pvzce.api.registry.ResourceKey;
import com.pvzce.api.tag.TagKey;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.core.PvzceRegistries;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Ready-made tag keys, the shared tag manager and registry-scoped tag queries.
 *
 * <h2>Namespaces</h2>
 *
 * <p>Tags are keyed by <em>id</em>, not by the registry they belong to, exactly
 * like Minecraft: {@code data/c/tags/scene_element/plantable.json} is
 * {@code #c:plantable} for terrain and {@code data/c/tags/plant/plantable.json}
 * is a different {@code #c:plantable} for plants. The two never collide because
 * every query names the registry it asks about, which is what lets the same
 * convention word mean "you may plant here" on a tile and "you may plant this"
 * on a plant.
 *
 * <h2>Where the built-in tags live</h2>
 *
 * <p>The {@code c} namespace holds the cross-content conventions a pack or mod
 * is expected to extend ({@code #c:plantable}, {@code #c:carrier}, ...); this
 * class only declares the keys.
 * Game-specific groupings such as {@code #pvzce:sun_producer} stay in the
 * {@code pvzce} namespace, because "produces sun" is a PVZCE concept rather
 * than something another game layer would agree on.
 */
public final class PvzceTags {
    public static final TagManager MANAGER = new TagManager();

    /** The convention namespace, mirroring Fabric's {@code #c:*} tags. */
    public static final String CONVENTION_NAMESPACE = "c";

    // ------------------------------------------------------------------
    // scene_element tags - what a cell lets a plant be placed on
    //
    // The SCENE_ prefix is not decoration: #c:plantable exists in both this
    // registry and the plant one, and two constants with the same name in one
    // class would be a compile error. The tag ids stay identical, which is the
    // point - see the class comment.
    // ------------------------------------------------------------------

    /** Terrain a plant may be planted directly on (grass, flat/sloped roof). */
    public static final TagKey<SceneElementDef> SCENE_PLANTABLE =
            sceneElement("plantable");
    /**
     * Terrain that counts as solid ground: a carrier can stand on it, and a
     * ground-bound plant (flower pot, potato mine) belongs in it. Grass, bare
     * ground and roofs qualify; water and graves do not.
     */
    public static final TagKey<SceneElementDef> SCENE_GROUND =
            sceneElement("ground");
    /** Terrain that accepts nothing at all (grave, crater). */
    public static final TagKey<SceneElementDef> SCENE_UNPLANTABLE =
            sceneElement("unplantable");
    /** Aquatic terrain; only water plants may be placed on it. */
    public static final TagKey<SceneElementDef> SCENE_WATER =
            sceneElement("water");
    /**
     * Terrain that is a gravestone: the one thing a grave buster may be planted on.
     *
     * <p>Separate from {@link #SCENE_UNPLANTABLE}, which every grave is also in - that tag
     * says "no ordinary plant here", and this one says "the grave buster, and only the grave
     * buster". A tile may carry both, and the more specific rule is checked first.
     */
    public static final TagKey<SceneElementDef> SCENE_GRAVE =
            sceneElement("grave");

    // ------------------------------------------------------------------
    // plant tags - what a plant is, and what it may be placed on
    // ------------------------------------------------------------------

    /**
     * Plants that may hold another plant on top: the plant-side twin of
     * {@link #SCENE_PLANTABLE}. A flower pot on bare ground is what makes that
     * cell plantable, so "may something be planted on this?" has the same answer
     * whether the thing asked about is a tile or a plant.
     */
    public static final TagKey<PlantDef> PLANTABLE =
            plant("plantable");
    /**
     * Plants that carry other plants. A carrier is itself a plant, so it is
     * planted through the plant tags and then acts as terrain for whatever is
     * planted on it.
     */
    public static final TagKey<PlantDef> CARRIER =
            plant("carrier");
    /**
     * Plants that must be pushed into the ground itself (flower pot, potato mine):
     * never water, never a plant, never a carrier.
     */
    public static final TagKey<PlantDef> REQUIRES_GROUND =
            plant("requires_ground");
    /** Plants that must be planted on water (lily pad). */
    public static final TagKey<PlantDef> WATER_PLANT =
            plant("water_plant");
    /**
     * Plants that must be planted on another plant (coffee bean). "Another plant"
     * is any plant strictly below it, not a special subclass: a coffee bean on a
     * sunflower is as valid as one on a lily pad.
     */
    public static final TagKey<PlantDef> PLANT_ONLY =
            plant("plant_only");
    /**
     * Plants that must be planted on a gravestone (grave buster).
     *
     * <p>See {@link #SCENE_GRAVE}. The rule is a tag pair rather than a capability check for
     * the same reason {@code #c:water_plant} is: "where may this go" is answered by
     * {@link com.pvzce.common.core.PlantPlacement} so that the server, the plant AI and any
     * future hover feedback all get the same answer, instead of the plant being refused after
     * the player has already spent the card.
     */
    public static final TagKey<PlantDef> GRAVE_ONLY =
            plant("grave_only");

    /**
     * Every tag the built-in placement rules read. A pack that drops one of
     * these silently loses the ability to plant anything on the matching
     * terrain, so the loader reports the missing ones instead.
     */
    public static final List<TagKey<?>> PLACEMENT_TAGS = List.of(
            SCENE_PLANTABLE, SCENE_GROUND, SCENE_UNPLANTABLE, SCENE_WATER, SCENE_GRAVE,
            PLANTABLE, CARRIER, REQUIRES_GROUND, WATER_PLANT, PLANT_ONLY, GRAVE_ONLY);

    /** Built-in example: plants that produce sun (sunflower, marigold). */
    public static final TagKey<PlantDef> SUN_PRODUCERS =
            TagKey.create(PvzceRegistries.PLANTS, Identifier.withDefaultNamespace("sun_producer"));

    /**
     * Scene elements that come up out of the ground when a level raises them mid-game.
     *
     * <p>The built-in gravestones are in it, and the client plays the push of dirt aside for
     * whatever else is: the {@code grave_spawner} mechanic raises headstones one at a time,
     * and a tombstone that blinks into place reads as a rendering glitch rather than as
     * something climbing out of the lawn. A pack's own headstone joins by tagging it - which
     * is also why this is a tag and not the {@code GRAVE} surface class: "this is a
     * gravestone" and "this one is animated" are different statements, and a mod may want
     * either without the other.
     *
     * <p>In the {@code pvzce} namespace rather than {@code c}: what a client is expected to
     * do with an element is this game's convention, not one another game layer would agree on.
     */
    public static final TagKey<SceneElementDef> SCENE_RISES_FROM_GROUND =
            TagKey.create(PvzceRegistries.SCENE_ELEMENTS,
                    Identifier.withDefaultNamespace("rises_from_ground"));

    // ------------------------------------------------------------------
    // zombie tags - what a kind of zombie is, beyond its own definition
    // ------------------------------------------------------------------

    /**
     * Zombies a freeze does not hold: the ice-shroom's list of things cold cannot stop.
     *
     * <p>The original exempts a balloon zombie in the air and a digger under the lawn from
     * being <em>frozen</em> - it still deals them the damage and leaves them chilled - and
     * which zombies those are is a statement about content rather than a branch the freeze
     * should carry. A pack adds its own by tagging it, so a zombie whose art or behaviour
     * makes it immune to cold never has to be named in code.
     *
     * <p>In the {@code pvzce} namespace: "cold cannot hold this one" is this game's rule, the
     * same way {@link #SCENE_RISES_FROM_GROUND} is this game's convention.
     */
    public static final TagKey<ZombieDef> ZOMBIE_FREEZE_IMMUNE =
            TagKey.create(PvzceRegistries.ZOMBIES,
                    Identifier.withDefaultNamespace("freeze_immune"));

    /**
     * The zombies a mutation's crisis may conjure.
     *
     * <p>Data rather than a list in code, because "which zombies are a crisis" is a judgement about
     * content: a pack that ships a zombie should be able to say whether it belongs in the pool, and
     * a level whose theme is the pool should not be sent Gargantuars by a rule it cannot see. The
     * mutation rolls its subject out of this tag, so the pool is the only thing to edit.
     */
    public static final TagKey<ZombieDef> ZOMBIE_MUTATION_CRISIS =
            TagKey.create(PvzceRegistries.ZOMBIES,
                    Identifier.withDefaultNamespace("mutation_crisis"));

    /** Scene elements, for terrain tag queries. */
    public static final RegistryTagView<SceneElementDef> SCENE_ELEMENTS = view(PvzceRegistries.SCENE_ELEMENTS);
    /** Plants, for the placement rules and for content queries. */
    public static final RegistryTagView<PlantDef> PLANTS = view(PvzceRegistries.PLANTS);
    /** Zombies, for the content queries that are about a kind of zombie rather than an entity. */
    public static final RegistryTagView<ZombieDef> ZOMBIES = view(PvzceRegistries.ZOMBIES);

    private PvzceTags() {
    }

    private static TagKey<SceneElementDef> sceneElement(String path) {
        return TagKey.create(PvzceRegistries.SCENE_ELEMENTS, Identifier.of(CONVENTION_NAMESPACE, path));
    }

    private static TagKey<PlantDef> plant(String path) {
        return TagKey.create(PvzceRegistries.PLANTS, Identifier.of(CONVENTION_NAMESPACE, path));
    }

    public static <T> TagKey<T> key(ResourceKey<Registry<T>> registry, Identifier id) {
        return TagKey.create(registry, id);
    }

    public static Set<TagKey<?>> keys() {
        return MANAGER.keys();
    }

    public static Set<Identifier> ids(TagKey<?> tag) {
        return MANAGER.ids(tag);
    }

    public static boolean contains(TagKey<?> tag, Identifier id) {
        return MANAGER.contains(tag, id);
    }

    /** True when the tag exists in the pack stack (an absent tag has no ids either). */
    public static boolean isLoaded(TagKey<?> tag) {
        return MANAGER.keys().contains(tag);
    }

    /**
     * Placement tags that no loaded pack declares. Reported once per reload by
     * the level validator; an empty list is the healthy case.
     */
    public static List<TagKey<?>> missingPlacementTags() {
        List<TagKey<?>> missing = new java.util.ArrayList<>();
        for (TagKey<?> tag : PLACEMENT_TAGS) {
            if (!isLoaded(tag)) {
                missing.add(tag);
            }
        }
        return List.copyOf(missing);
    }

    private static <T> RegistryTagView<T> view(ResourceKey<Registry<T>> key) {
        return new RegistryTagView<>(key);
    }

    /**
     * Tag queries bound to one registry.
     *
     * <p>Exists because a {@code TagKey<T>} alone cannot say which registry to
     * read: the tag loader binds tags to the registry instance, and a caller
     * holding only a {@code TagKey} would have to reach for the global registry
     * access. A view carries both, so a query cannot be asked against the wrong
     * registry and the two {@code #c:plantable} tags stay distinct by
     * construction.
     */
    public static final class RegistryTagView<T> {
        private final ResourceKey<Registry<T>> registryKey;

        private RegistryTagView(ResourceKey<Registry<T>> registryKey) {
            this.registryKey = registryKey;
        }

        public ResourceKey<Registry<T>> registryKey() {
            return registryKey;
        }

        private Registry<T> registry() {
            return BuiltInRegistries.ACCESS.get(registryKey);
        }

        /** True when {@code id} is an entry of this registry named by {@code tagId}. */
        public boolean contains(Identifier tagId, Identifier id) {
            if (tagId == null || id == null) {
                return false;
            }
            Registry<T> registry = registry();
            if (registry == null) {
                return false;
            }
            return registry.getTag(TagKey.create(registryKey, tagId))
                    .map(tag -> tag.containsId(id))
                    .orElse(false);
        }

        /** True when {@code id} is named by {@code tag}. */
        public boolean contains(TagKey<T> tag, Identifier id) {
            return contains(tag.id(), id);
        }

        /** Every entry id in {@code tag}; empty when the tag is not loaded. */
        public Set<Identifier> ids(TagKey<T> tag) {
            Registry<T> registry = registry();
            if (registry == null) {
                return Set.of();
            }
            return registry.getTag(tag).map(bound -> Set.copyOf(bound.ids())).orElse(Set.of());
        }

        /** Every tag bound to this registry, whether or not a key was declared for it. */
        public Set<Identifier> tagIds() {
            Registry<T> registry = registry();
            if (registry == null) {
                return Set.of();
            }
            Set<Identifier> ids = new LinkedHashSet<>();
            for (TagKey<T> tag : registry.tagKeys()) {
                ids.add(tag.id());
            }
            return Collections.unmodifiableSet(ids);
        }

        @Override
        public String toString() {
            return "RegistryTagView[" + registryKey.location() + "]";
        }
    }
}
