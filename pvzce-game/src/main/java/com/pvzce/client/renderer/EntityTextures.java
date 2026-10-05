package com.pvzce.client.renderer;

import com.pvzce.api.content.SceneElementArt;
import com.pvzce.api.content.SceneElementDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.core.BuiltInRegistries;
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
        return forScene(sceneId, false);
    }

    public static Identifier forScene(String sceneId) {
        return forScene(sceneId, false);
    }

    /**
     * The same, in the variant the level's sky and the element's state call for.
     *
     * <p>A scene element may declare its own sprite, its size, and a variant per time of day -
     * the original draws the doom shroom's crater twice, once for daylight and once for a lawn
     * after dark. Which one is used is decided here rather than by the element, because the
     * element does not know what time it is; an element that declares nothing falls through to
     * the id-derived path, which is every element but the crater.
     *
     * @param night the level's own night test (see {@code ClientLevel.isNight})
     */
    public static Identifier forScene(Identifier sceneId, boolean night) {
        if (sceneId == null) {
            return Identifier.withDefaultNamespace(SCENE_PREFIX + "unknown");
        }
        SceneElementDef def = BuiltInRegistries.SCENE_ELEMENTS.get(sceneId);
        if (def != null) {
            Identifier declared = def.artOrDefault().textureFor(night).orElse(null);
            if (declared != null) {
                return declared;
            }
        }
        return resolve(sceneId, SCENE_PREFIX);
    }

    public static Identifier forScene(String sceneId, boolean night) {
        return forScene(Identifier.tryParse(sceneId), night);
    }

    /**
     * The scene element this one is drawn on top of, or {@code null} when it fills its cell.
     *
     * <p>An element id rather than a texture: what is under a tombstone is the lawn, and the
     * lawn is content - a pack may restyle it, and a level that hides it (see
     * {@code LevelDef.hiddenSceneElements}) hides it under the tombstone too.
     */
    public static Identifier sceneUnderlay(String sceneId) {
        SceneElementDef def = BuiltInRegistries.SCENE_ELEMENTS.get(Identifier.tryParse(sceneId));
        return def == null ? null : def.artOrDefault().underlay().orElse(null);
    }

    /**
     * How big a scene element is drawn, in cells, centred on its cell.
     *
     * <p>One cell for everything that declares no art of its own, which is what the board's
     * placement highlight and the editor's hit test assume.
     */
    public static float[] sceneSize(String sceneId) {
        SceneElementDef def = BuiltInRegistries.SCENE_ELEMENTS.get(Identifier.tryParse(sceneId));
        SceneElementArt art = def == null ? SceneElementArt.NONE : def.artOrDefault();
        return new float[]{art.width(), art.height()};
    }

    /**
     * True when this element's texture is a tile sheet rather than one cell-sized sprite.
     *
     * <p>The element says so in its own art ({@code "tiled": true}), which is how a level or a
     * pack brings a terrain material of its own - see {@code pvzce:hillside_mid}. The two built-in
     * atlases predate the field and are tiled by id, and that spelling is kept because a pack that
     * replaces {@code pvzce:grass} without redeclaring the field still gets the lawn it had
     * (compare {@code SceneElementDef.profileFor}, the same compatibility shape for the roof and
     * the pool).
     */
    public static boolean sceneTiled(String sceneId) {
        SceneElementDef def = BuiltInRegistries.SCENE_ELEMENTS.get(Identifier.tryParse(sceneId));
        if (def != null && def.artOrDefault().tiled()) {
            return true;
        }
        return "pvzce:grass".equals(sceneId) || "pvzce:ground".equals(sceneId);
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
