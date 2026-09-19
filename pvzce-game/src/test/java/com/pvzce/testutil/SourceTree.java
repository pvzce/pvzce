package com.pvzce.testutil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * The checkout's own sources, for the guards that check properties of the code rather than of a
 * running game.
 *
 * <p>Two guards read sources instead of objects - {@code LayerDependencyTest} (who may import
 * whom) and {@code DocumentedSymbolsTest} (the documents name real symbols). Both need the same two
 * things: the checkout root, and the list of {@code .java} files under it. This is that one copy,
 * so a guard added later does not grow a third way of finding the repository.
 */
public final class SourceTree {
    /** Modules whose sources the guards read, relative to the checkout root. */
    public static final List<String> SOURCE_ROOTS = List.of(
            "pvzce-game/src/main/java", "pvzce-api/src/main/java", "pvzce-game/src/test/java");

    private SourceTree() {
    }

    /**
     * The checkout root, found by walking up from the working directory for {@code docs/}.
     *
     * <p>The Gradle test working directory is the module and an IDE may run from the repository
     * root, so neither can be assumed. Returns {@code null} outside a source checkout, which is
     * the caller's cue to skip itself rather than fail.
     */
    public static Path root() {
        Path candidate = Path.of("").toAbsolutePath();
        for (int i = 0; i < 4 && candidate != null; i++) {
            if (Files.isRegularFile(candidate.resolve("docs/当前项目架构.md"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        return null;
    }

    /** The main sources of the game, which are the ones the layering rule is about. */
    public static List<Path> gameMainSources(Path root) throws IOException {
        return javaFiles(root.resolve("pvzce-game/src/main/java"));
    }

    /** Every {@code .java} file under a directory, sorted so a failure message is stable. */
    public static List<Path> javaFiles(Path base) throws IOException {
        if (!Files.isDirectory(base)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(base)) {
            List<Path> found = new ArrayList<>(files.filter(p -> p.toString().endsWith(".java")).toList());
            found.sort(Path::compareTo);
            return found;
        }
    }
}
