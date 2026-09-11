package com.pvzce.client.renderer;

import com.pvzce.api.util.Identifier;

/**
 * The single place that turns a content id into a texture id.
 *
 * <p>Four call sites used to derive these paths by hand, with two conflicting
 * policies: some preserved the id's namespace, others stripped it and forced
 * {@code pvzce}. A modded entity therefore resolved its sprite in the seed-chooser
 * preview but requested a {@code pvzce:} texture on the in-game board, so it was
 * invisible during play. Namespace preservation is the correct rule - a mod's
 * textures live under its own namespace.
 */
public final class EntityTextures {
    public static final String ENTITY_PREFIX = "textures/entities/";
    public static final String RESOURCE_PREFIX = "textures/resource/";
    public static final String SCENE_PREFIX = "textures/scene/";

    public static Identifier forEntity(Identifier defId) {
        return resolve(defId, ENTITY_PREFIX);
    }

    public static Identifier forEntity(String defId) {
        return forEntity(Identifier.tryParse(defId));
    }

    public static Identifier forResource(Identifier resourceId) {
        return resolve(resourceId, RESOURCE_PREFIX);
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
