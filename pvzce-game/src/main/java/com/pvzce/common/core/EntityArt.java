package com.pvzce.common.core;

import com.pvzce.api.content.AnimationBindings;
import com.pvzce.api.content.AnimationSource;
import com.pvzce.api.content.ContentDefs;
import com.pvzce.api.content.PlantDef;
import com.pvzce.api.content.ProjectileDef;
import com.pvzce.api.content.ResourceDef;
import com.pvzce.api.content.ToolDef;
import com.pvzce.api.content.ZombieDef;
import com.pvzce.api.entity.EntityKind;
import com.pvzce.api.util.Identifier;

/**
 * The single answer to "which art does this content id use?".
 *
 * <p>Two questions, one lookup, because they have to agree: the <b>animation
 * file</b> the client plays ({@code assets/<ns>/animations/<dir>/<leaf>.json}) and
 * the <b>fallback sprite</b> drawn when there is no animation resource. Both used
 * to be guessed from the id alone, which stopped working the moment the shipped
 * art was grouped by kind - {@code textures/entities/plant/attacker/pea_shooter.png}
 * is not reachable by appending {@code pea_shooter} to a prefix.
 *
 * <p>Definitions may declare {@code animation_dir} and {@code texture}; a definition
 * that declares neither keeps exactly the old id-derived path, so mods are unaffected.
 * Resources are the one exception that predates this: their sprite is the resource's
 * own {@code icon} field, which is what the HUD and the cards already draw.
 *
 * <p>Lives in {@code common/core} next to {@link BuiltInRegistries} because it is a
 * registry lookup; the client renderer and the server-side card resolver both call it.
 */
public final class EntityArt {
    /** {@code assets/<ns>/textures/entities/} - the flat sprite family. */
    public static final String ENTITY_PREFIX = "textures/entities/";
    /** {@code assets/<ns>/textures/resource/} - resource card and bank icons. */
    public static final String RESOURCE_PREFIX = "textures/resource/";

    /**
     * The one texture drawn when a texture reference cannot be resolved.
     *
     * <p>A 2x2 magenta/black checkerboard (uploaded with nearest-neighbour filtering so the
     * squares survive magnification), the same idea as Minecraft's missing-texture tile. It
     * replaced a white quad: several shipped ids pointed at art that was never generated
     * ({@code textures/resource/generic}, {@code textures/resource/coin}, the per-entity
     * fallbacks), and a white rectangle is indistinguishable from a deliberately blank
     * sprite - the checkerboard is not.
     *
     * <p>Declared here rather than at the draw site because three layers need the same
     * answer: the renderer that falls back, {@link #sprite} when a definition names no art,
     * and the texture manager that decides how to filter it.
     */
    public static final Identifier MISSING_TEXTURE =
            Identifier.withDefaultNamespace("textures/mission");

    /**
     * The animation file id for a definition id.
     *
     * <p>Returns {@code defId} itself when the definition is unknown or declares no
     * directory - that is the {@code animations/<id.path()>.json} convention, and it
     * is also the correct answer for anything the client cannot look up (a mod's
     * content on a client that never loaded its data pack still gets the old path).
     */
    public static Identifier animationFile(Identifier defId) {
        if (defId == null) {
            return null;
        }
        return AnimationSource.fileId(defId, animationDir(defId));
    }

    /** The declared animation directory of a content id, or {@code null} for the default. */
    public static String animationDir(Identifier defId) {
        AnimationBindings bindings = bindings(defId);
        return bindings == null ? null : bindings.animationDir().orElse(null);
    }

    /**
     * The sprite to draw when this content has no animation resource.
     *
     * <p>Never returns {@code null}: a null id is {@link #MISSING_TEXTURE} outright, and an
     * id whose derived path also turns out to be absent ends there too, because the
     * renderer draws {@link #MISSING_TEXTURE} for any texture it cannot resolve. The caller
     * must not have to invent a path of its own - that is exactly how the board and the
     * seed chooser drifted apart before.
     */
    public static Identifier sprite(Identifier defId) {
        return sprite(defId, null);
    }

    /**
     * The same, in the registry the entity's kind names.
     *
     * <p>See {@link #bindings(Identifier, String)} for why the kind matters: two registries
     * may hold the same id, and the snow pea is both a plant and the projectile it fires -
     * so a projectile that asked without saying so was answered with the plant's texture.
     */
    public static Identifier sprite(Identifier defId, String kind) {
        if (defId == null) {
            return MISSING_TEXTURE;
        }
        Identifier declared = declaredTexture(defId, kind);
        if (declared != null) {
            return declared;
        }
        ResourceDef resource = BuiltInRegistries.RESOURCES.get(defId);
        if (EntityKind.RESOURCE.equals(kind) || (kind == null && resource != null)) {
            if (resource != null) {
                return resource.icon() == null ? withPrefix(RESOURCE_PREFIX, defId) : resource.icon();
            }
        }
        return withPrefix(ENTITY_PREFIX, defId);
    }

    /**
     * The animation bindings of a content id <em>in the registry its kind names</em>.
     *
     * <p>The kind is not decoration. Content ids are free to collide across registries - the
     * snow pea is both a plant and the projectile it fires - and asking "which definition has
     * this id" without saying which registry answers with whichever one is checked first. That
     * is exactly how every snow pea in flight was drawn as a whole snow pea <em>plant</em>:
     * the projectile inherited the plant's {@code animation_dir} and played the plant's
     * controller model.
     *
     * <p>An unknown or absent kind falls back to {@link #bindings(Identifier)}, because a
     * caller that has no kind (a card, an editor palette) still has to get an answer.
     */
    public static AnimationBindings bindings(Identifier defId, String kind) {
        if (defId == null) {
            return null;
        }
        PlantDef plant = BuiltInRegistries.PLANTS.get(defId);
        if (EntityKind.PLANT.equals(kind)) {
            return plant == null ? null : plant.animations();
        }
        ZombieDef zombie = BuiltInRegistries.ZOMBIES.get(defId);
        if (EntityKind.ZOMBIE.equals(kind)) {
            return zombie == null ? null : zombie.animations();
        }
        ProjectileDef projectile = BuiltInRegistries.PROJECTILES.get(defId);
        if (EntityKind.PROJECTILE.equals(kind)) {
            return projectile == null ? null : projectile.animations();
        }
        ResourceDef resource = BuiltInRegistries.RESOURCES.get(defId);
        if (EntityKind.RESOURCE.equals(kind)) {
            return resource == null ? null : resource.animations();
        }
        return bindings(defId);
    }

    /**
     * The animation bindings of a content id across every entity registry.
     *
     * <p>For callers that have an id and nothing else. A caller that knows the entity's kind
     * must use {@link #bindings(Identifier, String)}: two registries can hold the same id and
     * this search returns whichever is checked first.
     */
    public static AnimationBindings bindings(Identifier defId) {
        if (defId == null) {
            return null;
        }
        PlantDef plant = BuiltInRegistries.PLANTS.get(defId);
        if (plant != null) {
            return plant.animations();
        }
        ZombieDef zombie = BuiltInRegistries.ZOMBIES.get(defId);
        if (zombie != null) {
            return zombie.animations();
        }
        ProjectileDef projectile = BuiltInRegistries.PROJECTILES.get(defId);
        if (projectile != null) {
            return projectile.animations();
        }
        ResourceDef resource = BuiltInRegistries.RESOURCES.get(defId);
        return resource == null ? null : resource.animations();
    }

    /**
     * How much bigger than its art the client should draw this content.
     *
     * <p>The one answer to "how big is this entity", next to the one answer to "which
     * art is this entity": a renderer that scaled by a field of its own, or by a kind
     * constant, would be the second opinion that {@link EntityArt} exists to prevent.
     *
     * <p>{@code 1} for anything unknown or undeclared, so a definition written before
     * this field existed is drawn exactly as it was.
     */
    public static float renderScale(Identifier defId) {
        if (defId == null) {
            return ContentDefs.DEFAULT_RENDER_SCALE;
        }
        PlantDef plant = BuiltInRegistries.PLANTS.get(defId);
        if (plant != null) {
            return plant.renderScale();
        }
        ZombieDef zombie = BuiltInRegistries.ZOMBIES.get(defId);
        if (zombie != null) {
            return zombie.renderScale();
        }
        ProjectileDef projectile = BuiltInRegistries.PROJECTILES.get(defId);
        if (projectile != null) {
            return projectile.renderScale();
        }
        ResourceDef resource = BuiltInRegistries.RESOURCES.get(defId);
        return resource == null ? ContentDefs.DEFAULT_RENDER_SCALE : resource.renderScale();
    }

    private static Identifier declaredTexture(Identifier defId, String kind) {
        Identifier texture = null;
        PlantDef plant = BuiltInRegistries.PLANTS.get(defId);
        ZombieDef zombie = BuiltInRegistries.ZOMBIES.get(defId);
        ProjectileDef projectile = BuiltInRegistries.PROJECTILES.get(defId);
        ResourceDef resource = BuiltInRegistries.RESOURCES.get(defId);
        ToolDef tool = BuiltInRegistries.TOOLS.get(defId);
        // The kind, when the caller has one, picks the registry outright: an id in two
        // registries (the snow pea is a plant and a projectile) otherwise answers with
        // whichever is tested first.
        if (EntityKind.PLANT.equals(kind)) {
            return plant == null ? null : plant.texture().orElse(null);
        }
        if (EntityKind.ZOMBIE.equals(kind)) {
            return zombie == null ? null : zombie.texture().orElse(null);
        }
        if (EntityKind.PROJECTILE.equals(kind)) {
            return projectile == null ? null : projectile.texture().orElse(null);
        }
        if (EntityKind.RESOURCE.equals(kind)) {
            return resource == null ? null : resource.texture().orElse(null);
        }
        if (plant != null) {
            texture = plant.texture().orElse(null);
        } else if (zombie != null) {
            texture = zombie.texture().orElse(null);
        } else if (projectile != null) {
            texture = projectile.texture().orElse(null);
        } else if (resource != null) {
            texture = resource.texture().orElse(null);
        } else if (tool != null) {
            texture = tool.texture().orElse(null);
        }
        return texture;
    }

    private static Identifier withPrefix(String prefix, Identifier defId) {
        return Identifier.of(defId.namespace(), prefix + defId.path());
    }

    private EntityArt() {
    }
}
