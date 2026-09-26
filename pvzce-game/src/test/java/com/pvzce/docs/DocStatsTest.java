package com.pvzce.docs;

import com.pvzce.testutil.SourceTree;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scale numbers in the as-built entry document are generated, not typed.
 *
 * <p>{@code 当前项目架构.md} used to open with a hand-written line - "418 files / about 74k lines,
 * 132 test files, 591 resource JSONs, 32 plants, 26 zombies, 28 levels". Every one of those had
 * drifted by the time anyone looked: the real numbers were 511 / 94k / 159 / 660 / 41 / 32 / 44.
 * The line was not wrong when it was written; it was wrong because nothing regenerated it, and a
 * number in prose has no owner.
 *
 * <p>So the numbers now live in a marked block and are <em>computed</em>. This test is both the
 * checker and the generator: run it normally and it fails when the block is stale; run it with
 * {@code -Ppvzce.smoke=pvzce.updateDocStats=true} (which the {@code test} task forwards as a system
 * property) and it rewrites the block in place. One implementation, two modes - a separate script
 * would be a second copy of the counting rules, and the two would disagree eventually.
 *
 * <p>Deliberately not checked: test-case counts. {@code 验证约定.md} §6 forbids recording them, and
 * a number that is forbidden as a metric should not be generated either.
 */
class DocStatsTest {
    /** The marker pair around the generated block, matched on this prefix / suffix. */
    private static final String BEGIN_PREFIX = "<!-- BEGIN DOC STATS";
    private static final String END = "<!-- END DOC STATS -->";

    /** Told to rewrite the block instead of asserting on it. */
    private static final String UPDATE_PROPERTY = "pvzce.updateDocStats";

    private static Path root;
    private static Path document;

    @BeforeAll
    static void locateCheckout() {
        root = SourceTree.root();
        Assumptions.assumeTrue(root != null, "not running from a source checkout; docs are not present");
        document = root.resolve("docs/当前项目架构.md");
        Assumptions.assumeTrue(Files.isRegularFile(document), "the as-built entry document is missing");
    }

    @Test
    void theDocumentedScaleMatchesTheCheckout() throws IOException {
        String text = Files.readString(document);
        int begin = text.indexOf(BEGIN_PREFIX);
        int end = text.indexOf(END);
        assertTrue(begin >= 0 && end > begin, "当前项目架构.md lost its DOC STATS markers");
        int bodyStart = text.indexOf('\n', begin) + 1;

        String actual = text.substring(bodyStart, end);
        String expected = render();

        if (Boolean.getBoolean(UPDATE_PROPERTY)) {
            Files.writeString(document, text.substring(0, bodyStart) + expected + text.substring(end));
            return;
        }
        assertEquals(expected, actual, """
                the generated scale block in 当前项目架构.md is stale.
                Rewrite it with:
                  ./gradlew :pvzce-game:test --tests '*DocStatsTest*' -Ppvzce.smoke=pvzce.updateDocStats=true
                then run the test once more without the flag to confirm it is in sync.""");
    }

    /** The block body, exactly as it should appear between the two markers. */
    private static String render() throws IOException {
        long gameFiles = countJava(root.resolve("pvzce-game/src/main/java"));
        long gameLines = countLines(root.resolve("pvzce-game/src/main/java"));
        long testFiles = countJava(root.resolve("pvzce-game/src/test/java"))
                + countJava(root.resolve("pvzce-api/src/test/java"));
        long resourceJson = countFiles(root.resolve("pvzce-game/src/main/resources"), ".json");
        long loaderFiles = countJava(root.resolve("pvzce-loader/src"));
        long loaderLines = countLines(root.resolve("pvzce-loader/src"));
        long apiFiles = countJava(root.resolve("pvzce-api/src/main/java"));

        return ("""
                > **规模（生成，不要手改）**：`pvzce-game` 主源码 %d 文件 / %d 行、测试 %d 文件、资源 %d 个 JSON；\
                `pvzce-loader` %d 文件 / %d 行（Fabric Loader fork）；`pvzce-api` %d 文件。\
                内容：植物 %d 种、僵尸 %d 种、粒子定义 %d 个、关卡文件 %d 个。
                """).formatted(
                gameFiles, gameLines, testFiles, resourceJson, loaderFiles, loaderLines, apiFiles,
                contentCount("plants"), contentCount("zombies"),
                contentCount("particles"), contentCount("levels"));
    }

    /** One content registry's definition count, by the directory the definitions live in. */
    private static long contentCount(String registryDirectory) throws IOException {
        return countFiles(root.resolve("pvzce-game/src/main/resources/data/pvzce").resolve(registryDirectory), ".json");
    }

    private static long countJava(Path base) throws IOException {
        return countFiles(base, ".java");
    }

    private static long countFiles(Path base, String suffix) throws IOException {
        if (!Files.isDirectory(base)) {
            return 0;
        }
        try (Stream<Path> files = Files.walk(base)) {
            return files.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(suffix)).count();
        }
    }

    private static long countLines(Path base) throws IOException {
        List<Path> files = SourceTree.javaFiles(base);
        long lines = 0;
        for (Path file : files) {
            try (Stream<String> text = Files.lines(file)) {
                lines += text.count();
            }
        }
        return lines;
    }
}
