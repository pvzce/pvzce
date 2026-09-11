package com.pvzce.server.command;

import com.pvzce.api.registry.Registry;
import com.pvzce.api.registry.ResourceKey;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.core.PvzceRegistries;
import com.pvzce.common.level.DayNightCycle;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The command layer used to keep its own copies of the registry list and the
 * day/night maths. These tests pin the single table so a registry or an alias can
 * no longer be added in one place and missed in another.
 */
class CommandRegistryTableTest {
    @BeforeAll
    static void bootstrap() {
        BuiltInRegistries.bootstrap();
    }

    /**
     * Every registry declared in {@code PvzceRegistries} must appear in the category
     * table. Adding a registry used to mean remembering four hand-typed lists.
     */
    @Test
    void everyDeclaredRegistryHasACategory() throws Exception {
        List<String> declared = new ArrayList<>();
        for (Field field : PvzceRegistries.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != ResourceKey.class) {
                continue;
            }
            @SuppressWarnings("unchecked")
            ResourceKey<?> key = (ResourceKey<?>) field.get(null);
            declared.add(key.location().path());
        }
        assertFalse(declared.isEmpty(), "the reflection probe found no registry keys");

        Map<String, ResourceKey<? extends Registry<?>>> categories = PvzceRegistries.byCategory();
        for (String path : declared) {
            assertTrue(categories.containsKey(path),
                    "registry '" + path + "' is declared but has no command category");
        }
    }

    /** Each category must resolve to a live registry, so suggestions are never empty. */
    @Test
    void everyCategoryResolvesToALiveRegistry() {
        for (Map.Entry<String, ResourceKey<? extends Registry<?>>> entry : PvzceRegistries.byCategory().entrySet()) {
            assertNotNull(registryOf(entry.getValue()),
                    "category '" + entry.getKey() + "' points at a registry that is not registered");
        }
    }

    /** Aliases resolve to the canonical name, and unknown names are left alone. */
    @Test
    void categoryAliasesResolveToTheCanonicalName() {
        assertEquals("plant", PvzceRegistries.canonicalCategory("plant"));
        assertEquals("plant", PvzceRegistries.canonicalCategory("plants"));
        assertEquals("plant", PvzceRegistries.canonicalCategory("PLANTS"));
        assertEquals("scene_element", PvzceRegistries.canonicalCategory("scene"));
        assertEquals("scene_element", PvzceRegistries.canonicalCategory("scene_elements"));
        assertEquals("level", PvzceRegistries.canonicalCategory("levels"));
        assertEquals("", PvzceRegistries.canonicalCategory(null));
        // An unknown name is passed through so the caller can report it verbatim.
        assertEquals("not_a_registry", PvzceRegistries.canonicalCategory("not_a_registry"));
    }

    /**
     * The identifier argument's suggestions come from the same table, so a category
     * offered by the command tree always produces completions.
     */
    @Test
    void identifierSuggestionsUseTheRegistryTable() {
        for (String category : PvzceRegistries.byCategory().keySet()) {
            IdentifierArgumentType type = IdentifierArgumentType.forCategory(category);
            assertNotNull(type, category);
        }
        // The plural alias must work too: it used to fall through to "no suggestions".
        IdentifierArgumentType plural = IdentifierArgumentType.forCategory("plants");
        assertNotNull(plural);
        assertFalse(plural.getExamples().isEmpty());

        // Unknown categories offer nothing rather than throwing.
        assertNotNull(IdentifierArgumentType.forCategory("not_a_registry"));
    }

    /** The synthetic categories are not registries and must still be usable. */
    @Test
    void syntheticCategoriesStillWork() {
        for (String category : new String[]{"team", "tag", "any_entity"}) {
            assertNotNull(IdentifierArgumentType.forCategory(category), category);
        }
        assertNull(PvzceRegistries.byCategory().get("team"), "team is not a registry");
        assertNotNull(PvzceIds.PLANT_TEAM);
    }

    /** {@code /time query day} shares the cycle maths with the clock. */
    @Test
    void dayCountComesFromTheSharedCycleMaths() {
        assertEquals(0L, DayNightCycle.dayCount(0L, 1200, 600));
        assertEquals(0L, DayNightCycle.dayCount(1799L, 1200, 600));
        assertEquals(1L, DayNightCycle.dayCount(1800L, 1200, 600), "a full cycle completes a day");
        assertEquals(2L, DayNightCycle.dayCount(3600L, 1200, 600));
        // A level with no night never advances the day counter.
        assertEquals(0L, DayNightCycle.dayCount(100_000L, 1200, 0));
        assertEquals(0L, DayNightCycle.dayCount(100_000L, 0, 0));
    }

    /** The clock's hard answer and the client's smooth blend agree on the boundary. */
    @Test
    void dayAndNightAnswersAgree() {
        int day = 1200;
        int night = 600;
        // Midday.
        assertFalse(DayNightCycle.isNight(600L, day, night));
        assertEquals(0F, DayNightCycle.nightBlend(600L, day, night), 1e-6F);
        // Midnight.
        assertTrue(DayNightCycle.isNight(1500L, day, night));
        assertEquals(1F, DayNightCycle.nightBlend(1500L, day, night), 1e-6F);
        // The blend is monotonic across dusk.
        float previous = -1F;
        for (long t = day - DayNightCycle.fadeWindow(day, night); t <= day + DayNightCycle.fadeWindow(day, night); t++) {
            float blend = DayNightCycle.nightBlend(t, day, night);
            assertTrue(blend >= previous, "the dusk blend must not go backwards at t=" + t);
            previous = blend;
        }
    }

    @SuppressWarnings("unchecked")
    private static Registry<?> registryOf(ResourceKey<? extends Registry<?>> key) {
        return BuiltInRegistries.ACCESS.get(
                (ResourceKey<Registry<Object>>) (Object) key);
    }
}
