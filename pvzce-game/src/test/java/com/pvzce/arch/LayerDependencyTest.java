package com.pvzce.arch;

import com.pvzce.testutil.SourceTree;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The layers only point one way.
 *
 * <p>The project is one Gradle source set, so nothing but convention stops {@code com.pvzce.server}
 * from importing {@code com.pvzce.client}, or a content definition from reaching into the
 * simulation. A convention that only lives in a document erodes one convenient import at a time and
 * the compiler never says a word - which is how the layering table in {@code 当前项目架构.md} came
 * to need a "two deliberate exceptions" footnote that nobody could check.
 *
 * <p>The rules below are that table, made executable. Every exception is named by file pattern, so
 * the violations that exist are the ones that were decided, and a new one fails the build instead of
 * quietly becoming a third footnote:
 *
 * <ul>
 *   <li>{@code server}, {@code common} and {@code api} never import {@code client} - the client is a
 *       mirror of the simulation, and the simulation must not learn how it is drawn;</li>
 *   <li>{@code client} never imports {@code server} - shared logic belongs in {@code common},
 *       otherwise "server-authoritative" turns into one program with two names;</li>
 *   <li>{@code common -> server} only from a capability or a level mechanic, both of which act on
 *       the concrete entity and the level server;</li>
 *   <li>{@code api -> server} and {@code api -> common} only from the definition packages, which
 *       carry behaviour presets naming the capability implementations.</li>
 * </ul>
 *
 * <p>Only {@code import} statements are read: reflection and inline fully qualified names are not
 * caught, which is a job for review. The point here is the case a reviewer cannot see in a
 * 60k-line tree - one added import.
 */
class LayerDependencyTest {
    private static final Pattern IMPORT = Pattern.compile(
            "^import (?:static )?com\\.pvzce\\.([a-z0-9_]+)\\.", Pattern.MULTILINE);

    /** Where a {@code common -> server} edge may start. */
    private static final List<String> COMMON_TO_SERVER =
            List.of("common/capability/", "common/level/mechanic/");

    /** Where an {@code api -> server} edge may start. */
    private static final List<String> API_TO_SERVER =
            List.of("api/content/capability/", "api/entity/LevelAccess.java");

    /** Where an {@code api -> common} edge may start. */
    private static final List<String> API_TO_COMMON = List.of("api/content/", "api/entity/");

    /** Package root of the game's own layers; importer paths are relative to it. */
    private static Path gamePackage;

    @BeforeAll
    static void findSources() {
        Path root = SourceTree.root();
        Assumptions.assumeTrue(root != null, "not running from a source checkout; no layers to check");
        gamePackage = root.resolve("pvzce-game/src/main/java/com/pvzce");
    }

    @Test
    void noLayerImportsTheClient() throws IOException {
        assertEdgesAllowed("api", "client", importer -> false);
        assertEdgesAllowed("common", "client", importer -> false);
        assertEdgesAllowed("server", "client", importer -> false);
    }

    @Test
    void theClientDoesNotImportTheServer() throws IOException {
        assertEdgesAllowed("client", "server", importer -> false);
    }

    @Test
    void commonReachesTheServerOnlyThroughCapabilitiesAndMechanics() throws IOException {
        assertEdgesAllowed("common", "server", allowedFrom(COMMON_TO_SERVER));
    }

    @Test
    void apiReachesTheServerOnlyThroughDefinitions() throws IOException {
        assertEdgesAllowed("api", "server", allowedFrom(API_TO_SERVER));
    }

    @Test
    void apiReachesTheFrameworkOnlyThroughDefinitions() throws IOException {
        assertEdgesAllowed("api", "common", allowedFrom(API_TO_COMMON));
    }

    /**
     * Asserts every import from one layer to another starts at a file the rule allows.
     *
     * @param importer  the layer doing the importing, e.g. {@code common}
     * @param target    the layer being imported, e.g. {@code server}
     * @param isAllowed whether that importer may make the edge at all; the rules above are the whole
     *                  list of exceptions, so this is where a deliberate edge is named
     */
    private static void assertEdgesAllowed(String importer, String target,
                                           Predicate<String> isAllowed) throws IOException {
        Set<String> violations = new LinkedHashSet<>();
        for (Path file : SourceTree.javaFiles(gamePackage)) {
            String relative = gamePackage.relativize(file).toString().replace('\\', '/');
            if (!relative.startsWith(importer + "/") || isAllowed.test(relative)) {
                continue;
            }
            Matcher matcher = IMPORT.matcher(Files.readString(file));
            while (matcher.find()) {
                if (matcher.group(1).equals(target)) {
                    violations.add(relative + " -> " + matcher.group(0).trim());
                }
            }
        }
        assertTrue(violations.isEmpty(),
                importer + " may not import " + target + " here:\n" + String.join("\n", violations));
    }

    private static Predicate<String> allowedFrom(List<String> prefixes) {
        return importer -> {
            for (String prefix : prefixes) {
                if (importer.startsWith(prefix)) {
                    return true;
                }
            }
            return false;
        };
    }
}
