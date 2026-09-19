package com.pvzce.testutil;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Throwaway game directories that are deleted when the JVM exits.
 *
 * <p>{@code Files.createTempDirectory} never deletes anything, and this suite called it in
 * forty-five places: every run left the same set of half-written saves, configs and datapacks
 * behind in the system temp directory, and nothing ever noticed because none of them is ever read
 * again. A test that wants a directory for one method should use JUnit's {@code @TempDir} (fresh
 * per test, deleted afterwards, and printed when the test fails); this class is for the two cases
 * {@code @TempDir} cannot serve - a helper that builds a directory for its caller, and a
 * singleton directory that is shared by every test in the JVM.
 *
 * <p>Cleanup is a shutdown hook, so it runs once for the whole suite rather than per directory:
 * the point is that the temp directory stops growing, not that a test sees an empty one.
 */
public final class TestDirs {
    private static final List<Path> CREATED = new ArrayList<>();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(TestDirs::deleteAll, "pvzce-test-tempdirs"));
    }

    private TestDirs() {
    }

    /** A new temp directory with this prefix, deleted when the JVM exits. */
    public static synchronized Path create(String prefix) {
        try {
            Path dir = Files.createTempDirectory(prefix);
            CREATED.add(dir);
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException("could not create a temp directory: " + prefix, e);
        }
    }

    /** Best-effort recursive delete: a failure here must not fail the suite. */
    private static void deleteAll() {
        for (Path dir : CREATED) {
            try (var paths = Files.walk(dir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        // Left for the operating system to reap.
                    }
                });
            } catch (IOException ignored) {
                // Same: cleanup is a courtesy, not a test result.
            }
        }
    }
}
