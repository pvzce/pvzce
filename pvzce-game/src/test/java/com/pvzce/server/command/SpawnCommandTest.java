package com.pvzce.server.command;

import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.tag.TestContent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code /spawn} command's argument kinds.
 *
 * <p>{@code /spawn resource ...} used to fail with "unknown command at position 5", which
 * reads like a typo in the command name: the {@code kind} argument only offered plant,
 * zombie and projectile, so the parse stopped at the literal.
 */
class SpawnCommandTest {
    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
        BuiltInRegistries.bootstrap();
    }

    /** The command tree on its own; nothing here needs a running server. */
    private static com.mojang.brigadier.CommandDispatcher<PvzceCommandSource> tree() {
        return PvzceCommands.create();
    }

    @Test
    void everyEntityKindParses() {
        assertNotNull(BuiltInRegistries.RESOURCES.keySet(), "resources must be loaded");
        // The three that always worked, and the one that did not.
        for (String command : new String[]{
                "spawn plant pvzce:pea_shooter 1 1",
                "spawn zombie pvzce:basic_zombie 1 1",
                "spawn projectile pvzce:pea 1 1",
                "spawn resource pvzce:sun 1 1"}) {
            var parse = tree().parse(command, new PvzceCommandSource(null));
            assertTrue(parse.getExceptions().isEmpty(),
                    command + " must parse, got " + parse.getExceptions());
            assertFalse(parse.getContext().getNodes().isEmpty(), command);
        }
    }

}
