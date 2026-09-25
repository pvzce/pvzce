package com.pvzce.common.resource;

import com.pvzce.api.util.Identifier;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.resource.PvzceResourceManager.AvailablePack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pack list, and the one directory rule behind both sides of it.
 *
 * <p>The page that manages packs shows what the loader loads, so the two have to answer "what is
 * a pack?" the same way - and they answer it from {@code PvzceResourceManager.listPackDirectories}.
 * This test is that claim: the scan and the loaded stack are compared name for name.
 *
 * <p>The disabled case goes through the real loader rather than through the pack stack alone: a
 * pack that is out of the stack must also be out of the registries, which is the thing a player
 * would notice.
 */
class PackSelectionTest {

    /** A fresh directory per test; JUnit deletes it, and prints it when a test fails. */
    @TempDir
    Path gameDir;

    private static ClassLoader classLoader() {
        return Thread.currentThread().getContextClassLoader();
    }

    /** A plant file the real codec accepts, so "loaded" means the loader ran, not just the reader. */
    private static void writeTestPlant(Path pack) throws Exception {
        Path plants = pack.resolve("data/test/plants");
        Files.createDirectories(plants);
        Files.writeString(plants.resolve("peashooter.json"), """
                {"id":"test:peashooter","cost":{"resources":{"pvzce:sun":125},"cooldown":300},
                 "health":400,"attack_interval":45,"shots":[]}
                """);
    }

    @Test
    void theScanAndTheLoaderReadTheSameDirectoryRule() throws Exception {
        Files.createDirectories(gameDir.resolve("resourcepacks/alpha"));
        Files.createDirectories(gameDir.resolve("resourcepacks/beta/inner"));
        Files.writeString(gameDir.resolve("resourcepacks/readme.txt"), "not a pack");
        Files.createDirectories(gameDir.resolve("datapacks/gamma"));

        PvzceResourceManager resources = new PvzceResourceManager(classLoader());
        resources.init(gameDir);

        List<String> scanned = resources.scanAvailable(gameDir).stream()
                .filter(pack -> pack.kind() != AvailablePack.Kind.BUILT_IN)
                .map(AvailablePack::name)
                .toList();
        List<String> loaded = resources.packs().stream()
                .filter(DirectoryPack.class::isInstance)
                .map(pack -> pack.name().substring("dir/".length()))
                .toList();
        // Same names in the same order: a file is not a pack, a directory inside a pack is not a
        // second pack, and both sides read resource packs before data packs.
        assertEquals(loaded, scanned, "the page and the loaded stack must agree on what a pack is");
        assertEquals(List.of("alpha", "beta", "gamma"), scanned);

        AvailablePack builtIn = resources.scanAvailable(gameDir).get(0);
        assertEquals(AvailablePack.Kind.BUILT_IN, builtIn.kind(), "the built-in pack is listed first");
        assertEquals(ClasspathPack.NAME, builtIn.name());
        assertTrue(builtIn.enabled(), "the built-in pack is always loaded");
    }

    @Test
    void aDisabledPackLeavesTheStackAndTheRegistries() throws Exception {
        BuiltInRegistries.bootstrap();
        Path pack = gameDir.resolve("datapacks/testpack");
        writeTestPlant(pack);

        PvzceResourceManager resources = new PvzceResourceManager(classLoader());
        resources.init(gameDir);
        PvzceDataLoader loader = new PvzceDataLoader();
        Identifier plantFile = Identifier.of("test", "plants/peashooter.json");
        Identifier plantId = Identifier.of("test", "peashooter");
        assertTrue(resources.getData(plantFile).isPresent(), "the pack is loaded to begin with");
        PvzceDataLoader.LoadResult before = loader.load(resources, BuiltInRegistries.ACCESS);
        assertTrue(before.errors().isEmpty(), before.errors().toString());
        assertTrue(before.loaded().contains(plantId), "the pack's content is loaded to begin with");

        PackSelection selection = PackSelection.load(gameDir);
        selection.setEnabled("testpack", false);
        assertTrue(selection.save());
        resources.reload();

        assertTrue(resources.getData(plantFile).isEmpty(), "a disabled pack's files are not readable");
        assertFalse(resources.packs().stream().anyMatch(loaded -> loaded.name().equals("dir/testpack")),
                "a disabled pack is not in the stack");
        PvzceDataLoader.LoadResult after = loader.load(resources, BuiltInRegistries.ACCESS);
        assertTrue(after.errors().isEmpty(), after.errors().toString());
        assertFalse(after.loaded().contains(plantId), "a disabled pack registers nothing");

        AvailablePack scanned = resources.scanAvailable(gameDir).stream()
                .filter(entry -> entry.name().equals("testpack"))
                .findFirst()
                .orElseThrow();
        assertFalse(scanned.enabled(), "the page must still list it, switched off");
    }

    @Test
    void theListRoundTripsThroughItsFile() throws Exception {
        PackSelection selection = PackSelection.load(gameDir);
        assertTrue(selection.disabled().isEmpty(), "a game directory with no list has every pack on");

        selection.setEnabled("beta", false);
        selection.setEnabled("gamma", false);
        assertTrue(selection.save());
        Path file = gameDir.resolve(PackSelection.FILE_NAME);
        assertTrue(Files.isRegularFile(file), "the list is written beside the other config files");

        PackSelection reread = PackSelection.load(gameDir);
        assertEquals(List.of("beta", "gamma"), reread.disabled());
        assertFalse(reread.isEnabled("beta"));
        assertTrue(reread.isEnabled("alpha"), "a pack the file does not name stays enabled");

        reread.setEnabled("beta", true);
        assertTrue(reread.save());
        assertEquals(List.of("gamma"), PackSelection.load(gameDir).disabled(),
                "switching one back on removes only it");
    }

    @Test
    void theBuiltInPackCannotBeDisabled() throws Exception {
        PackSelection selection = PackSelection.load(gameDir);
        assertTrue(PackSelection.isBuiltIn(ClasspathPack.NAME));
        assertThrows(IllegalArgumentException.class,
                () -> selection.setEnabled(ClasspathPack.NAME, false));
        assertTrue(selection.isEnabled(ClasspathPack.NAME));

        // A list that names it - hand-edited, or written by an older build - changes nothing
        // either: the built-in pack never consults the list.
        Files.createDirectories(gameDir.resolve("config"));
        Files.writeString(gameDir.resolve(PackSelection.FILE_NAME),
                "{\"disabled\":[\"" + ClasspathPack.NAME + "\"]}");
        PvzceResourceManager resources = new PvzceResourceManager(classLoader());
        resources.init(gameDir);
        assertEquals(ClasspathPack.NAME, resources.packs().get(0).name());
        assertTrue(resources.scanAvailable(gameDir).stream()
                .filter(entry -> entry.kind() == AvailablePack.Kind.BUILT_IN)
                .allMatch(AvailablePack::enabled));
    }

    @Test
    void aPackIsDescribedByItsMcmetaOrByItsDirectoryName() throws Exception {
        Path described = Files.createDirectories(gameDir.resolve("datapacks/described"));
        Files.writeString(described.resolve("pack.mcmeta"),
                "{\"pack\":{\"pack_format\":1,\"description\":\"User levels\"}}");
        Files.createDirectories(gameDir.resolve("datapacks/plain"));

        PvzceResourceManager resources = new PvzceResourceManager(classLoader());
        Map<String, String> descriptions = resources.scanAvailable(gameDir).stream()
                .collect(Collectors.toMap(AvailablePack::name, AvailablePack::description));

        assertEquals("User levels", descriptions.get("described"));
        assertEquals("plain", descriptions.get("plain"), "no pack.mcmeta means the directory name");
        // The built-in pack has one too, read from the classpath rather than from a directory.
        assertEquals("PVZCE built-in resources", descriptions.get(ClasspathPack.NAME));
    }

    @Test
    void theSummaryCountsEachContentDirectory() throws Exception {
        Path pack = gameDir.resolve("datapacks/counted");
        Files.createDirectories(pack.resolve("data/test/plants"));
        Files.createDirectories(pack.resolve("data/test/zombies"));
        Files.createDirectories(pack.resolve("assets/test/textures/gui"));
        Files.writeString(pack.resolve("data/test/plants/one.json"), "{}");
        Files.writeString(pack.resolve("data/test/plants/two.json"), "{}");
        Files.writeString(pack.resolve("data/test/zombies/one.json"), "{}");
        Files.writeString(pack.resolve("assets/test/textures/gui/icon.png"), "");
        Files.writeString(pack.resolve("pack.mcmeta"), "{}");

        PvzceResourceManager resources = new PvzceResourceManager(classLoader());
        AvailablePack scanned = resources.scanAvailable(gameDir).stream()
                .filter(entry -> entry.name().equals("counted"))
                .findFirst()
                .orElseThrow();

        Map<String, Integer> summary = resources.contentSummary(scanned);
        assertEquals(2, summary.get("plants"), "every file under the directory is counted");
        assertEquals(1, summary.get("zombies"));
        assertEquals(1, summary.get("textures"), "an asset directory is counted the same way");
        assertEquals(4, summary.keySet().stream().mapToInt(summary::get).sum());
    }
}
