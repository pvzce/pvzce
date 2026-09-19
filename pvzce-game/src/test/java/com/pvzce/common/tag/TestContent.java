package com.pvzce.common.tag;

import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.resource.PvzceDataLoader;
import com.pvzce.common.resource.PvzceResourceManager;
import com.pvzce.testutil.TestDirs;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test entry point for content + tags.
 *
 * <p>Tests used to load only the data pack. Since the placement rules read tags,
 * a test that skips the tag pass sees an empty tag table and every cell becomes
 * unplantable - the failure would look like a broken rule rather than missing
 * test setup. One helper keeps that step from being forgotten per test class.
 *
 * <p>{@link BuiltInRegistries#bootstrap()} is global and idempotent, so it is
 * called once here rather than from every {@code @BeforeAll}.
 */
public final class TestContent {
    private static final AtomicInteger BOOTSTRAPS = new AtomicInteger();

    /**
     * One game directory for the whole JVM.
     *
     * <p>This used to be a fresh temp directory per call, and {@code loadBuiltInContent} is called
     * by every test class that needs content: forty-odd directories per run, none of them ever
     * deleted. The manager only reads from it, so sharing one is safe; {@link TestDirs} removes it
     * when the JVM exits.
     */
    private static final Path SHARED_GAME_DIR = TestDirs.create("pvzce-test-content");

    private TestContent() {
    }

    /** Loads the classpath data pack into a fresh game directory and returns the manager. */
    public static PvzceResourceManager loadBuiltInContentAndTags() throws Exception {
        PvzceResourceManager resources = loadBuiltInContent();
        TagManager.LoadResult tags = PvzceTags.MANAGER.reload(resources, BuiltInRegistries.ACCESS);
        if (!tags.errors().isEmpty()) {
            throw new IllegalStateException("built-in tags failed to load: " + tags.errors());
        }
        return resources;
    }

    /** Loads the classpath data pack only; for tests that supply their own tags. */
    public static PvzceResourceManager loadBuiltInContent() throws Exception {
        if (BOOTSTRAPS.getAndIncrement() == 0) {
            BuiltInRegistries.bootstrap();
        }
        PvzceResourceManager resources = new PvzceResourceManager(
                Thread.currentThread().getContextClassLoader());
        resources.init(SHARED_GAME_DIR);
        PvzceDataLoader.LoadResult result = new PvzceDataLoader().load(resources, BuiltInRegistries.ACCESS);
        if (!result.errors().isEmpty()) {
            throw new IllegalStateException("built-in content failed to load: " + result.errors());
        }
        return resources;
    }

    /**
     * Puts the built-in tag table back.
     *
     * <p>The tag manager is a singleton, so a test that reloads it from its own
     * throwaway pack (the tag-loader tests do) wipes the convention tags for every
     * test that runs afterwards in the same JVM. Any such test calls this in
     * {@code @AfterAll}; without it the failure surfaces as a baffling "cannot
     * plant anything" in an unrelated class.
     */
    public static void restoreBuiltInTags() throws Exception {
        loadBuiltInContentAndTags();
    }
}
