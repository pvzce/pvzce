package com.pvzce.server.level;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Which almanac-style icon a level shows in the level list.
 *
 * <p>Read off the level's own definition rather than written into it: what the list draws is a
 * picture of the <em>ground</em> - day, night, pool, roof, and the two night variants - and the
 * ground is already in the level's scene and its night rule. A level that says {@code water} in its
 * scene is a pool level whether or not its author remembered to say so in an icon field.
 *
 * <p>Its own class because two things ask it now: the level list, for every level, and the level
 * collections, which show their first member's icon ({@code LevelCollections}). The rule was a
 * private method of the server until the second caller existed, and a copy of it in the collections
 * would be a second answer to "what does a pool level look like" - the answer the client turns into
 * a texture name.
 */
public final class LevelIcons {
    private static final Logger LOGGER = LoggerFactory.getLogger(LevelIcons.class);

    private LevelIcons() {
    }

    /** The icon theme for one level: {@code day}, {@code night}, {@code pool}, {@code night_pool}
     * or {@code roof}. */
    public static String of(LevelDef def) {
        boolean night = false;
        var nightRule = def.rules().get(Identifier.withDefaultNamespace("night_length"));
        if (nightRule != null && nightRule.isJsonPrimitive()) {
            try {
                night = nightRule.getAsInt() > 0;
            } catch (RuntimeException e) {
                LOGGER.warn("Level {} has a non-numeric night_length; showing it as a day level",
                        def.id(), e);
            }
        }
        boolean roof = def.scene().keySet().stream().anyMatch(key -> key.path().contains("roof"));
        boolean water = def.scene().keySet().stream().anyMatch(key -> key.path().contains("water"));
        if (roof) {
            return "roof";
        }
        if (water) {
            return night ? "night_pool" : "pool";
        }
        return night ? "night" : "day";
    }
}
