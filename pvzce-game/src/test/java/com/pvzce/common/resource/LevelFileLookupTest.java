package com.pvzce.common.resource;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.tag.TestContent;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How a level file is found, which is what the editor's "open an existing level" path
 * depends on.
 *
 * <p>A level id cannot be turned into a path: the loader lets a file's own {@code id}
 * override the one its location implies, and a level may sit in a nested directory. The
 * editor used to assemble {@code data/<ns>/levels/<path>} from the id - the
 * registry directory twice and no {@code .json} - so a level it had just written was
 * never found again and reopening one silently started an empty new level.
 *
 * <p>These tests pin the two properties that make the real lookup work: the pack stack
 * enumerates every level file, and the id inside a file is what identifies it.
 */
class LevelFileLookupTest {
    private static final String PREFIX = "data/pvzce/levels/";

    /** Writes one level JSON under a pack and returns the resource-manager view of it. */
    private static Map<String, PackResource> levelsIn(Path gameDir) throws Exception {
        PvzceResourceManager resources = new PvzceResourceManager(
                Thread.currentThread().getContextClassLoader());
        resources.init(gameDir);
        return resources.listResources("data");
    }

    private static JsonObject jsonOf(Map<String, PackResource> resources, String path) {
        PackResource resource = resources.get(path);
        assertNotNull(resource, "expected " + path + " in the pack stack");
        return JsonParser.parseString(resource.readString()).getAsJsonObject();
    }

    @Test
    void aLevelIsFoundByTheIdInsideTheFileNotByItsPath() throws Exception {
        BuiltInRegistries.bootstrap();
        Path gameDir = Files.createTempDirectory("pvzce-level-lookup");
        // Deliberately nested and with a name unrelated to the id: a path derived from
        // "pvzce:arena" would be data/pvzce/levels/arena.json and would miss it.
        Path file = gameDir.resolve("datapacks/user_levels/data/pvzce/levels/tier1/boss.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                {"id":"pvzce:arena","name":"竞技场","width":9,"height":5,
                 "initial_entities":[{"kind":"plant","id":"pvzce:wall_nut","x":2,"y":2}]}
                """);

        Map<String, PackResource> found = levelsIn(gameDir);
        String path = PREFIX + "tier1/boss.json";
        assertTrue(found.containsKey(path), "the nested level file must be listed");

        JsonObject json = jsonOf(found, path);
        assertEquals("pvzce:arena", json.get("id").getAsString(),
                "the file's own id is what identifies the level, not its path");
        assertEquals(1, json.getAsJsonArray("initial_entities").size(),
                "the preset entities are what the editor must load back");
    }

    @Test
    void aUserLevelOverridesTheBuiltInOneOfTheSameId() throws Exception {
        TestContent.loadBuiltInContentAndTags();
        Path gameDir = Files.createTempDirectory("pvzce-level-override");
        Path file = gameDir.resolve("datapacks/user_levels/data/pvzce/levels/demo_level.json");
        Files.createDirectories(file.getParent());
        // Same id as the built-in level: the pack stack must hand back this one, so the
        // editor edits the copy the player actually sees.
        Files.writeString(file, """
                {"id":"pvzce:demo_level","name":"我的演示关卡","width":4,"height":4}
                """);

        Map<String, PackResource> found = levelsIn(gameDir);
        JsonObject json = jsonOf(found, PREFIX + "demo_level.json");
        assertEquals("我的演示关卡", json.get("name").getAsString(),
                "the user-level copy must win over the built-in one");
        assertEquals(4, json.get("width").getAsInt());
    }
}
