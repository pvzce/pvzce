package com.pvzce.common.entity;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.WaveDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.core.BuiltInRegistries;

/** Reads the removed miniature ids in old saves without registering additional content. */
public final class LegacyZombieAttributes {
    public static WaveDef.Entry entry(Identifier oldId) {
        if (!Identifier.DEFAULT_NAMESPACE.equals(oldId.namespace()) || !oldId.path().startsWith("mini_"))
            return null;
        Identifier parent = Identifier.withDefaultNamespace(oldId.path().substring("mini_".length()));
        LevelDef level = BuiltInRegistries.LEVELS.get(Identifier.withDefaultNamespace("yard/adventure/3_5"));
        if (level == null) return null;
        // The generated level is the source of these values, including for migration.
        for (WaveDef wave : level.waves()) {
            for (WaveDef.Entry entry : wave.entries()) {
                if (entry.id().equals(parent) && !entry.attributes().values().isEmpty()) return entry;
            }
        }
        return null;
    }
    private LegacyZombieAttributes() { }
}
