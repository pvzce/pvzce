package com.pvzce.common.resource;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pvzce.api.util.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;

/**
 * Pack stack (low to high): built-in pack, then external resource/data packs in
 * list order. Higher packs override lower ones, mirroring Minecraft.
 *
 * <p>Every lookup walks the stack through {@link #forEachMatch}, so the three
 * per-method copies of "iterate packs, open, read, build a PackResource" are gone.
 * They had already drifted: one iterated high-to-low for a first-hit lookup, the
 * others low-to-high for overrides, and each decided independently what to do when
 * a pack failed to open.
 */
public final class PvzceResourceManager implements AutoCloseable {
    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("PVZCE/Resources");

    /**
     * One pack root: the directory a kind of pack lives in.
     *
     * <p>The list order is the load order, so a data pack's file wins over a resource pack's when
     * both define the same path - the same precedence the two hard-coded calls had.
     */
    private record PackRoot(String directory, AvailablePack.Kind kind) {
    }

    private static final List<PackRoot> PACK_ROOTS = List.of(
            new PackRoot("resourcepacks", AvailablePack.Kind.RESOURCEPACK),
            new PackRoot("datapacks", AvailablePack.Kind.DATAPACK));

    /**
     * A pack this game directory offers, whether or not it is loaded.
     *
     * @param name        the directory name, or {@link ClasspathPack#NAME} for the built-in pack
     * @param kind        which root it was found under; the built-in pack is its own kind because
     *                    it is neither a resource pack nor a data pack - it serves both trees
     * @param enabled     whether it is in the loaded stack
     * @param path        its directory, or {@code null} for the built-in pack
     * @param description the {@code pack.mcmeta} description, falling back to the name
     */
    public record AvailablePack(String name, Kind kind, boolean enabled, Path path, String description) {
        /** Which tree a pack was found under, or that it is the built-in one. */
        public enum Kind {
            BUILT_IN,
            RESOURCEPACK,
            DATAPACK
        }
    }

    private final List<PvzcePack> packs = new ArrayList<>();
    private final ClassLoader classLoader;
    private Path gameDir;

    public PvzceResourceManager(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    public void init(Path gameDir) throws IOException {
        this.gameDir = gameDir;
        reload();
    }

    /** Rescans external packs; the built-in pack stays at the bottom of the stack. */
    public void reload() throws IOException {
        for (PvzcePack pack : packs) {
            pack.close();
        }
        packs.clear();
        packs.add(new ClasspathPack(classLoader));

        if (gameDir != null) {
            // Re-read here rather than holding a selection field: the client writes this file and
            // the server reloads on its say-so, so a cached copy is a copy that goes stale exactly
            // when it matters.
            PackSelection selection = PackSelection.load(gameDir);
            for (PackRoot root : PACK_ROOTS) {
                addDirectoryPacks(root, selection);
            }
        }
    }

    private void addDirectoryPacks(PackRoot root, PackSelection selection) throws IOException {
        for (Path entry : listPackDirectories(gameDir.resolve(root.directory()))) {
            String name = entry.getFileName().toString();
            if (selection.isDisabled(name)) {
                continue;
            }
            packs.add(new DirectoryPack(entry, "dir/" + name));
        }
    }

    /**
     * The one rule for "what is a pack": a directory directly under a pack root, in name order.
     *
     * <p>Both {@link #reload()} and {@link #scanAvailable(Path)} come through here. A page that
     * listed packs by its own copy of this loop would disagree with the stack the moment either
     * copy changed - and the page's whole job is to show what is loaded.
     */
    public static List<Path> listPackDirectories(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (var entries = Files.list(root)) {
            return entries.filter(Files::isDirectory).sorted().toList();
        }
    }

    /**
     * Every pack this game directory offers, built-in first, then resource packs, then data packs.
     *
     * <p>{@link #packs()} reports the loaded stack only, so it cannot answer "what is switched
     * off"; this is the list a management page shows, and its {@code enabled} flag is read from
     * the same selection file {@link #reload()} consults.
     */
    public List<AvailablePack> scanAvailable(Path gameDir) throws IOException {
        PackSelection selection = PackSelection.load(gameDir);
        List<AvailablePack> result = new ArrayList<>();
        ClasspathPack builtIn = new ClasspathPack(classLoader);
        result.add(new AvailablePack(ClasspathPack.NAME, AvailablePack.Kind.BUILT_IN, true, null,
                readDescription(builtIn, ClasspathPack.NAME)));
        for (PackRoot root : PACK_ROOTS) {
            for (Path path : listPackDirectories(gameDir.resolve(root.directory()))) {
                String name = path.getFileName().toString();
                result.add(new AvailablePack(name, root.kind(), selection.isEnabled(name), path,
                        readDescription(new DirectoryPack(path, name), name)));
            }
        }
        return result;
    }

    /**
     * The description a pack declares in its {@code pack.mcmeta}, or {@code fallback}.
     *
     * <p>Only the plain-string form of {@code pack.description} is read: Minecraft also allows a
     * chat component there, and rendering one is a thing this project has no use for.
     */
    private static String readDescription(PvzcePack pack, String fallback) {
        try (InputStream in = pack.open("pack.mcmeta")) {
            if (in == null) {
                return fallback;
            }
            JsonObject root = JsonParser.parseString(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject section = root.getAsJsonObject("pack");
            JsonElement description = section == null ? null : section.get("description");
            return description != null && description.isJsonPrimitive()
                    ? description.getAsString() : fallback;
        } catch (Exception e) {
            // A pack with an unreadable pack.mcmeta still loads and still shows its name; only
            // the caption is missing, so this is not worth more than a debug line.
            LOGGER.debug("Could not read pack.mcmeta of " + pack.name() + "; showing its name instead", e);
            return fallback;
        }
    }

    /**
     * How many files one pack holds under each content directory: {@code plants 12},
     * {@code textures 40}. Keys are the directory names the loader uses.
     *
     * <p>Counts the pack's own files rather than the merged stack's, because the page's question
     * is "what does this pack add" - a number that includes the built-in content underneath it
     * would be the same for every pack.
     */
    public Map<String, Integer> contentSummary(AvailablePack pack) throws IOException {
        PvzcePack source = pack.path() == null
                ? builtInPack() : new DirectoryPack(pack.path(), pack.name());
        if (source == null) {
            return Map.of();
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String tree : List.of("data", "assets")) {
            for (String path : source.list(tree)) {
                // <tree>/<namespace>/<content directory>/<file>; anything shallower is not content.
                String[] parts = path.split("/");
                if (parts.length >= 4) {
                    counts.merge(parts[2], 1, Integer::sum);
                }
            }
        }
        return counts;
    }

    /** The built-in pack at the bottom of the stack, or {@code null} before the first reload. */
    public PvzcePack builtInPack() {
        return packs.isEmpty() ? null : packs.get(0);
    }

    /** Loads a resource by path (e.g. {@code assets/pvzce/textures/foo.png}); highest pack wins. */
    public Optional<PackResource> getResource(String path) throws IOException {
        PackResource[] found = new PackResource[1];
        forEachMatch(path, true, (pack, resource) -> {
            if (found[0] == null) {
                found[0] = resource;
            }
        });
        return Optional.ofNullable(found[0]);
    }

    public Optional<PackResource> getAsset(Identifier id) throws IOException {
        return getResource("assets/" + id.toPath());
    }

    public Optional<PackResource> getData(Identifier id) throws IOException {
        return getResource("data/" + id.toPath());
    }

    /**
     * True when a texture id resolves to a PNG in the pack stack.
     *
     * <p>The one place the two accepted spellings live: textures are addressed either
     * fully ({@code pvzce:textures/gui/dialogue/box_left}) or as a bare asset id, and
     * both the renderer's "can I draw this" and the content validators' "does this
     * definition's art exist" have to agree on the answer.
     */
    public boolean hasTexture(Identifier id) {
        if (id == null) {
            return false;
        }
        try {
            return getResource("assets/" + id.toPath() + ".png").isPresent() || getAsset(id).isPresent();
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Lists every distinct path under a prefix, with the highest-priority occurrence winning. */
    public Map<String, PackResource> listResources(String prefix) throws IOException {
        Map<String, PackResource> result = new LinkedHashMap<>();
        for (PvzcePack pack : packs) {
            for (String path : pack.list(prefix)) {
                // First (lowest-priority) write wins in the map, so a later, higher
                // pack replaces it only if we overwrite - which is what "highest
                // priority wins" means here.
                try (InputStream in = openOrNull(pack, path)) {
                    if (in != null) {
                        result.put(path, new PackResource(pack.name(), path, in.readAllBytes()));
                    }
                }
            }
        }
        return result;
    }

    /**
     * Like {@link #listResources(String)} but keeps the complete pack stack per
     * path (lowest to highest priority). Tag loading needs this for MC-style
     * {@code replace} semantics.
     */
    public Map<String, List<PackResource>> listResourceStacks(String prefix) throws IOException {
        Map<String, List<PackResource>> result = new LinkedHashMap<>();
        for (PvzcePack pack : packs) {
            for (String path : pack.list(prefix)) {
                try (InputStream in = openOrNull(pack, path)) {
                    if (in != null) {
                        result.computeIfAbsent(path, ignored -> new ArrayList<>())
                                .add(new PackResource(pack.name(), path, in.readAllBytes()));
                    }
                }
            }
        }
        return result;
    }

    /**
     * Visits every pack that provides {@code path}, lowest priority first (or
     * highest priority first when {@code reverse} is set).
     */
    private void forEachMatch(String path, boolean reverse, BiConsumer<PvzcePack, PackResource> consumer)
            throws IOException {
        int size = packs.size();
        for (int step = 0; step < size; step++) {
            PvzcePack pack = packs.get(reverse ? size - 1 - step : step);
            try (InputStream in = openOrNull(pack, path)) {
                if (in != null) {
                    consumer.accept(pack, new PackResource(pack.name(), path, in.readAllBytes()));
                }
            }
        }
    }

    /**
     * Opens a path, treating "this pack does not have it" and "this pack failed to
     * read it" the same way as far as the stack walk is concerned, but logging the
     * latter so a broken pack does not silently degrade to the built-in one.
     */
    private static InputStream openOrNull(PvzcePack pack, String path) {
        try {
            return pack.open(path);
        } catch (IOException | RuntimeException e) {
            if (!isMissing(pack, path)) {
                LOGGER.warn("Failed to read " + path + " from pack " + pack.name(), e);
            }
            return null;
        }
    }

    /** {@link DirectoryPack} throws for a missing file; {@link ClasspathPack} returns null. */
    private static boolean isMissing(PvzcePack pack, String path) {
        return pack instanceof DirectoryPack directory && !directory.contains(path);
    }

    public List<PvzcePack> packs() {
        return List.copyOf(packs);
    }

    @Override
    public void close() throws IOException {
        for (PvzcePack pack : packs) {
            pack.close();
        }
    }
}
