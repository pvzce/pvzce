package com.pvzce.common.entity;

import com.pvzce.api.content.ContentDefs;
import com.pvzce.api.content.ZombieDef;
import com.pvzce.api.entity.attribute.EntityAttribute;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.core.BuiltInRegistries;

/** Built-in attribute ids and registry defaults; a mod registers entries in the same registry. */
public final class EntityAttributes {
    public static final Identifier MAX_HEALTH = id("max_health");
    public static final Identifier RENDER_SCALE = id("render_scale");
    public static final Identifier MOVEMENT_SPEED = id("movement_speed");
    public static final Identifier ATTACK_DAMAGE = id("attack_damage");
    public static final Identifier ATTACK_INTERVAL = id("attack_interval");
    public static final Identifier ARMOR_DURABILITY_MULTIPLIER = id("armor_durability_multiplier");
    public static final Identifier SPAWN_HEALTH_MODIFIER = id("spawn_health");

    public static void bootstrap() {
        register(MAX_HEALTH, 1, 1, Integer.MAX_VALUE);
        register(RENDER_SCALE, ContentDefs.DEFAULT_RENDER_SCALE, ContentDefs.MIN_RENDER_SCALE,
                ContentDefs.MAX_RENDER_SCALE);
        register(MOVEMENT_SPEED, ZombieDef.DEFAULT_MOVE_SPEED, 0, Float.MAX_VALUE);
        register(ATTACK_DAMAGE, ZombieDef.DEFAULT_BITE_DAMAGE, 0, Integer.MAX_VALUE);
        register(ATTACK_INTERVAL, ZombieDef.DEFAULT_BITE_INTERVAL, 1, Integer.MAX_VALUE);
        register(ARMOR_DURABILITY_MULTIPLIER, 1, 0, Integer.MAX_VALUE);
    }

    private static Identifier id(String path) { return Identifier.withDefaultNamespace(path); }
    private static void register(Identifier id, double value, double min, double max) {
        if (!BuiltInRegistries.ATTRIBUTES.containsKey(id))
            com.pvzce.api.registry.Registry.register(BuiltInRegistries.ATTRIBUTES, id, new EntityAttribute(id, value, min, max, true));
    }
    private EntityAttributes() { }
}
