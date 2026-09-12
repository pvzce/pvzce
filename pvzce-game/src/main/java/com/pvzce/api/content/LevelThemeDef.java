package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.util.Identifier;

/**
 * A level theme ("yard", "redstone") - the left column of the level select screen, and
 * the first path segment of a level id.
 *
 * <p>Loaded from {@code data/<ns>/level_themes/<name>.json}. Which categories a
 * theme offers is <b>not</b> declared here: it is read from the levels themselves, so a
 * theme can never show a tab that no level uses (the categories are the second path
 * segment of the ids under {@code levels/<theme>/}). A new series of levels therefore
 * needs no edit to this file at all.
 *
 * <p>Like {@link LevelCategoryDef} there is no {@code name} field - display names come
 * from the language file.
 *
 * @param order the column's position; lower first, ties broken by id. The unclassified
 *              bucket is always last regardless of this value.
 */
public record LevelThemeDef(Identifier id, int order) {
    public static final Codec<LevelThemeDef> CODEC = RecordCodecBuilder.create(i -> i.group(
            Identifier.CODEC.fieldOf("id").forGetter(LevelThemeDef::id),
            Codec.INT.optionalFieldOf("order", 0).forGetter(LevelThemeDef::order)
    ).apply(i, LevelThemeDef::new));
}
