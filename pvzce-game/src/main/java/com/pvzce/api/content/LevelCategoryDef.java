package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.util.Identifier;

/**
 * A level category ("adventure", "puzzle", "minigame") - the tab row of the level
 * select screen.
 *
 * <p>Loaded from {@code data/<ns>/level_categories/<name>.json}. A category is
 * declared once and may appear under any number of themes, so {@code adventure} means
 * one thing everywhere instead of being declared again per theme.
 *
 * <p><b>No {@code name} field.</b> A definition's display name comes from the language
 * file ({@code assets/<ns>/lang/<locale>.json}, key {@code <ns>.<path>}), exactly like
 * plants, zombies and scene elements. A second name in the data file would be a second
 * thing to keep in sync and would win over a resource pack's translation.
 *
 * @param order the tab's position; lower first, ties broken by id. The unclassified
 *              bucket is always last regardless of this value.
 */
public record LevelCategoryDef(Identifier id, int order) {
    public static final Codec<LevelCategoryDef> CODEC = RecordCodecBuilder.create(i -> i.group(
            Identifier.CODEC.fieldOf("id").forGetter(LevelCategoryDef::id),
            Codec.INT.optionalFieldOf("order", 0).forGetter(LevelCategoryDef::order)
    ).apply(i, LevelCategoryDef::new));
}
