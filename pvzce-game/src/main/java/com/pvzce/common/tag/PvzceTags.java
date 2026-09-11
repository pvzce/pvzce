package com.pvzce.common.tag;

import com.pvzce.api.content.PlantDef;
import com.pvzce.api.registry.Registry;
import com.pvzce.api.registry.ResourceKey;
import com.pvzce.api.tag.TagKey;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.core.PvzceRegistries;

import java.util.Set;

/** Ready-made tag keys and static access to the shared tag manager. */
public final class PvzceTags {
    public static final TagManager MANAGER = new TagManager();

    /** Built-in example: plants that produce sun (sunflower, marigold). */
    public static final TagKey<PlantDef> SUN_PRODUCERS =
            TagKey.create(PvzceRegistries.PLANTS, Identifier.withDefaultNamespace("sun_producer"));

    private PvzceTags() {
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
}
