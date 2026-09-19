package com.pvzce.common.resource;

import com.pvzce.api.util.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.io.InputStream;
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
            addDirectoryPacks(gameDir.resolve("resourcepacks"));
            addDirectoryPacks(gameDir.resolve("datapacks"));
        }
    }

    private void addDirectoryPacks(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var entries = Files.list(dir)) {
            for (Path entry : entries.sorted().toList()) {
                if (Files.isDirectory(entry)) {
                    packs.add(new DirectoryPack(entry, "dir/" + entry.getFileName()));
                }
            }
        }
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
