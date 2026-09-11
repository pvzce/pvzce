package com.pvzce.common.resource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** A resource/data pack rooted at a directory. */
public final class DirectoryPack implements PvzcePack {
    private final Path root;
    private final String name;

    public DirectoryPack(Path root, String name) {
        this.root = root.toAbsolutePath().normalize();
        this.name = name;
    }

    @Override
    public String name() {
        return name;
    }

    /** Resolves a pack-relative path, rejecting anything that escapes the root. */
    public Path resolve(String path) throws IOException {
        Path target = root.resolve(path).normalize();
        if (!target.startsWith(root)) {
            throw new IOException("Path escapes pack root: " + path);
        }
        return target;
    }

    /** True when this pack really does not contain {@code path}. */
    public boolean contains(String path) {
        try {
            return Files.isRegularFile(resolve(path));
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public InputStream open(String path) throws IOException {
        return Files.newInputStream(resolve(path));
    }

    @Override
    public List<String> list(String prefix) throws IOException {
        String normalized = PackPaths.normalizePrefix(prefix);
        Path base = root.resolve(normalized).normalize();
        if (!Files.isDirectory(base)) {
            return List.of();
        }
        try (var paths = Files.walk(base)) {
            // Sorted so content registration order does not depend on the file
            // system, which made a duplicate-id conflict resolve differently
            // between machines.
            return paths.filter(Files::isRegularFile)
                    .map(base::relativize)
                    .map(Path::toString)
                    .map(p -> p.replace(java.io.File.separatorChar, '/'))
                    .map(p -> PackPaths.join(normalized, p))
                    .sorted()
                    .toList();
        }
    }
}
