package com.pvzce.common.core;

import com.pvzce.api.content.PlantDef;
import com.pvzce.api.registry.RegistryEntryAddedCallback;
import com.pvzce.api.registry.Registry;
import com.pvzce.api.tag.TagKey;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.common.nbt.IntTag;
import com.pvzce.common.nbt.ListTag;
import com.pvzce.common.nbt.NbtIo;
import com.pvzce.common.nbt.StringTag;
import com.pvzce.common.resource.PvzceDataLoader;
import com.pvzce.common.resource.PvzceResourceManager;
import com.pvzce.common.tag.PvzceTags;
import com.pvzce.common.tag.TestContent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the foundation-layer defects: a "missing" id that parsed
 * as valid, a documented registration path that never notified listeners,
 * content ids derived from file basenames, two spellings of every registry
 * directory, and NBT accessors that failed late or not at all.
 */
class FoundationRegressionTest {
    @BeforeAll
    static void bootstrap() {
        BuiltInRegistries.bootstrap();
    }

    /**
     * An empty namespace or path is not an identifier. {@code tryParse("")} used to
     * return {@code pvzce:}, so every "null means missing" check in the codebase
     * accepted an empty string as a real id - including save loading and data
     * registration.
     */
    @Test
    void emptyIdentifiersAreRejected() {
        assertNull(Identifier.tryParse(""), "an empty string is not an id");
        assertNull(Identifier.tryParse(":"), "an empty namespace and path is not an id");
        assertNull(Identifier.tryParse("pvzce:"), "an empty path is not an id");
        assertThrows(IllegalArgumentException.class, () -> Identifier.parse(""));
        assertNull(Identifier.tryParse("Pvzce:Pea"), "uppercase is still rejected");
        assertNotNull(Identifier.tryParse("pvzce:pea_shooter"));
        // A leading separator still means "the default namespace", exactly like
        // vanilla's Identifier.parse(":foo") -> minecraft:foo.
        assertEquals("pvzce:peashooter", Identifier.tryParse(":peashooter").toString());
    }

    /**
     * The documented registration path must notify listeners. Only
     * {@code BuiltInRegistries.registerStatic} used to fire the callback, so a mod
     * following the mod guide registered a listener and never heard anything.
     */
    @Test
    void registryRegisterNotifiesEntryAddedListeners() {
        Registry<String> registry = BuiltInRegistries.ACCESS.newRegistry(
                com.pvzce.api.registry.ResourceKey.create(
                        Identifier.withDefaultNamespace("root"), Identifier.withDefaultNamespace("test_callback")));
        List<String> seen = new ArrayList<>();
        RegistryEntryAddedCallback.<String>event(registry).register((id, value) -> seen.add(id.toString()));

        Registry.register(registry, "pvzce:alpha", "alpha");
        assertEquals(List.of("pvzce:alpha"), seen, "Registry.register must fire the callback");

        ((com.pvzce.api.registry.MappedRegistry<String>) registry).registerDynamic(
                Identifier.withDefaultNamespace("beta"), "beta");
        assertEquals(List.of("pvzce:alpha", "pvzce:beta"), seen, "data-pack registration must fire it too");
    }

    /**
     * A nested content file must keep its directory in the id. Only the basename
     * used to be used, so {@code plants/a/pea.json} and {@code plants/b/pea.json}
     * silently collapsed onto one id and one of them won arbitrarily.
     */
    @Test
    void nestedContentPathsProduceDistinctIds() throws Exception {
        Path gameDir = Files.createTempDirectory("pvzce-nested-ids");
        Path pack = gameDir.resolve("datapacks/nested");
        Path first = pack.resolve("data/test/plants/tier1/pea.json");
        Path second = pack.resolve("data/test/plants/tier2/pea.json");
        Files.createDirectories(first.getParent());
        Files.createDirectories(second.getParent());
        Files.writeString(first, "{\"cost\":{\"resources\":{}},\"health\":111}");
        Files.writeString(second, "{\"cost\":{\"resources\":{}},\"health\":222}");

        PvzceResourceManager resources = new PvzceResourceManager(Thread.currentThread().getContextClassLoader());
        resources.init(gameDir);
        PvzceDataLoader.LoadResult result = new PvzceDataLoader().load(resources, BuiltInRegistries.ACCESS);
        assertTrue(result.errors().isEmpty(), result.errors().toString());

        PlantDef tier1 = BuiltInRegistries.PLANTS.get(Identifier.of("test", "tier1/pea"));
        PlantDef tier2 = BuiltInRegistries.PLANTS.get(Identifier.of("test", "tier2/pea"));
        assertNotNull(tier1, "the nested directory must be part of the id");
        assertNotNull(tier2, "the nested directory must be part of the id");
        assertEquals(111, tier1.health());
        assertEquals(222, tier2.health());
    }

    /** Two files claiming the same id must be reported, not silently resolved by order. */
    @Test
    void duplicateContentIdsAreReported() throws Exception {
        Path gameDir = Files.createTempDirectory("pvzce-duplicate-ids");
        Path pack = gameDir.resolve("datapacks/dupes");
        Path first = pack.resolve("data/test/plants/pea.json");
        Path second = pack.resolve("data/test/plants/other.json");
        Files.createDirectories(first.getParent());
        Files.writeString(first, "{\"id\":\"test:same\",\"cost\":{\"resources\":{}},\"health\":1}");
        Files.writeString(second, "{\"id\":\"test:same\",\"cost\":{\"resources\":{}},\"health\":2}");

        PvzceResourceManager resources = new PvzceResourceManager(Thread.currentThread().getContextClassLoader());
        resources.init(gameDir);
        PvzceDataLoader.LoadResult result = new PvzceDataLoader().load(resources, BuiltInRegistries.ACCESS);
        assertFalse(result.errors().isEmpty(), "a duplicate id must be reported");
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("Duplicate content id")), result.errors().toString());
    }

    /**
     * Both directory spellings must work for tags. The tag loader kept its own
     * registry table that only knew the singular form, so a tag file under
     * {@code tags/plants/} was rejected as an unknown registry even though the
     * matching content directory is {@code plants/}.
     *
     * <p>The pack root is the namespace alone; the extra {@code pvzce} segment this
     * used to spell is gone along with the loaders that required it.
     */
    @Test
    void tagDirectoriesAcceptBothSpellings() throws Exception {
        TagKey<PlantDef> group = TagKey.create(PvzceRegistries.PLANTS, Identifier.of("test", "group"));
        // One spelling per pack: with both files in one pack a single contains() would
        // pass even if one of the two paths were rejected outright, which is exactly the
        // bug this pins.
        for (String dir : new String[]{"plant", "plants"}) {
            Path gameDir = Files.createTempDirectory("pvzce-tag-" + dir);
            Path file = gameDir.resolve("datapacks/tags" + dir + "/data/test/tags/" + dir + "/group.json");
            Files.createDirectories(file.getParent());
            Files.writeString(file, "{\"values\":[\"pvzce:pea_shooter\"]}");

            PvzceResourceManager resources = new PvzceResourceManager(
                    Thread.currentThread().getContextClassLoader());
            resources.init(gameDir);
            PvzceTags.MANAGER.reload(resources, BuiltInRegistries.ACCESS);

            assertTrue(PvzceTags.MANAGER.contains(group, Identifier.withDefaultNamespace("pea_shooter")),
                    "a tag under tags/" + dir + "/ must resolve to the plant registry");
        }
    }

    /**
     * The tag table is global; the test above pointed it at a throwaway pack, so it
     * is put back here rather than left for whichever class runs next.
     */
    @AfterAll
    static void restoreTags() throws Exception {
        TestContent.restoreBuiltInTags();
    }

    /** Arrays with an impossible length must fail as IOException, not as a raw Java error. */
    @Test
    void corruptNbtLengthsAreReportedAsIoErrors() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(com.pvzce.common.nbt.Tag.COMPOUND);
            out.writeUTF("");
            out.writeByte(com.pvzce.common.nbt.Tag.BYTE_ARRAY);
            out.writeUTF("data");
            out.writeInt(-5);
            out.writeByte(com.pvzce.common.nbt.Tag.END);
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            IOException error = assertThrows(IOException.class, () -> NbtIo.read(in));
            assertTrue(error.getMessage().contains("length"), error.getMessage());
        }
    }

    /** Nulls are rejected when they are stored, not when the save is written. */
    @Test
    void nbtRejectsNullsAtInsertionTime() {
        CompoundTag tag = new CompoundTag();
        assertThrows(IllegalArgumentException.class, () -> tag.put("k", null));
        ListTag list = new ListTag();
        assertThrows(IllegalArgumentException.class, () -> list.add(null));
    }

    /**
     * Numeric values widen like vanilla's. Reading strictly by tag class meant a
     * value written as an int and read as a float silently produced the default.
     */
    @Test
    void nbtNumericGettersWidenAcrossTagTypes() {
        CompoundTag tag = new CompoundTag();
        tag.put("int", new IntTag(42));
        assertEquals(42, tag.getInt("int"));
        assertEquals(42F, tag.getFloat("int"), 0.0001F);
        assertEquals(42D, tag.getDouble("int"), 0.0001D);
        assertEquals(42L, tag.getLong("int"));
        // A genuinely wrong type still falls back rather than throwing.
        tag.put("text", new StringTag("nope"));
        assertEquals(0, tag.getInt("text"));
    }

    /** The NBT root contract is symmetric: what the writer accepts, the reader accepts. */
    @Test
    void nbtRootMustBeCompoundOnBothSides() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        assertThrows(IOException.class, () -> NbtIo.writeUnnamedTag(new ListTag(), out));
    }
}
