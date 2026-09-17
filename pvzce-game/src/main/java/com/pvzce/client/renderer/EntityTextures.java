package com.pvzce.client.renderer;

import com.pvzce.api.util.Identifier;
import com.pvzce.common.core.EntityArt;

/**
 * The single place that turns a content id into a texture id.
 *
 * <p>Four call sites used to derive these paths by hand, with two conflicting
 * policies: some preserved the id's namespace, others stripped it and forced
 * {@code pvzce}. A modded entity therefore resolved its sprite in the seed-chooser
 * preview but requested a {@code pvzce:} texture on the in-game board, so it was
 * invisible during play. Namespace preservation is the correct rule - a mod's
 * textures live under its own namespace.
 *
 * <p>The path itself now comes from {@link EntityArt}: a definition may declare its
 * own {@code texture}, which is how the built-in content survives being grouped into
 * {@code textures/entities/<kind>/<category>/}. The prefixes below remain the
 * convention for everything that declares nothing.
 */
public final class EntityTextures {
    public static final String ENTITY_PREFIX = EntityArt.ENTITY_PREFIX;
    public static final String RESOURCE_PREFIX = EntityArt.RESOURCE_PREFIX;
    public static final String SCENE_PREFIX = "textures/scene/";

    /**
     * The fallback sprite of a content id: its declared texture, or the id-derived
     * path.
     *
     * @return the texture id, or {@code null} when {@code defId} is null
     */
    public static Identifier forEntity(Identifier defId) {
        return EntityArt.sprite(defId);
    }

    /**
     * The same, in the registry the entity's kind names.
     *
     * <p>An id can be in two registries at once - the snow pea is a plant and the projectile
     * it fires - so a caller that knows its kind says so and gets that one's art. Without it
     * a snow pea in flight was drawn with the plant's picture.
     */
    public static Identifier forEntity(Identifier defId, String kind) {
        return EntityArt.sprite(defId, kind);
    }

    public static Identifier forEntity(String defId) {
        return forEntity(Identifier.tryParse(defId));
    }

    public static Identifier forResource(Identifier resourceId) {
        return EntityArt.sprite(resourceId);
    }

    public static Identifier forScene(Identifier sceneId) {
        return resolve(sceneId, SCENE_PREFIX);
    }

    public static Identifier forScene(String sceneId) {
        return forScene(Identifier.tryParse(sceneId));
    }

    private static Identifier resolve(Identifier id, String prefix) {
        if (id == null) {
            return Identifier.withDefaultNamespace(prefix + "unknown");
        }
        return Identifier.of(id.namespace(), prefix + id.path());
    }

    private EntityTextures() {
    }
}
