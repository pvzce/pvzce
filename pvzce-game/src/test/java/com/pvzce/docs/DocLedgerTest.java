package com.pvzce.docs;

import com.pvzce.testutil.SourceTree;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every document in {@code docs/} is registered in the ledger, and every archived volume is indexed.
 *
 * <p>The ledger ({@code docs/README.md}) answers "what is this file, and does a task have to update
 * it?". That answer is only useful while it is complete: a document added without a row is a
 * document nobody knows the obligations of, and the next task will either skip it forever or
 * rewrite it by accident. Six of them had accumulated that way (the two reports, the editor design,
 * the decision log before it existed, the smoke guide's second home) before this guard existed.
 *
 * <p>Two shapes, because {@code docs/} has two kinds of file:
 *
 * <ul>
 *   <li>the live documents and the frozen snapshots - one row each in the ledger;</li>
 *   <li>the archived changelog volumes under {@code docs/架构变更记录/} - too many for the ledger
 *       table, so each one must instead be linked from the changelog index it was split out of.</li>
 * </ul>
 *
 * <p>A row counts as present when the ledger names the file by relative path, by file name, by file
 * name without {@code .md}, or by a {@code <directory>/*} glob - the last two exist so the four
 * {@code mod-guide} pages do not need four near-identical rows.
 */
class DocLedgerTest {
    private static final String LEDGER = "README.md";
    private static final String ARCHIVE_DIRECTORY = "架构变更记录";
    private static final String CHANGELOG = "架构变更记录.md";

    private static Path docs;
    private static String ledger;

    @BeforeAll
    static void readLedger() throws IOException {
        Path root = SourceTree.root();
        Assumptions.assumeTrue(root != null, "not running from a source checkout; docs are not present");
        docs = root.resolve("docs");
        Assumptions.assumeTrue(Files.isRegularFile(docs.resolve(LEDGER)), "docs/README.md is missing");
        ledger = Files.readString(docs.resolve(LEDGER));
    }

    @Test
    void everyDocumentHasARowInTheLedger() throws IOException {
        List<String> unregistered = new ArrayList<>();
        for (Path file : documents()) {
            if (file.getFileName().toString().equals(LEDGER) || isArchived(file)) {
                continue;
            }
            String relative = slash(docs.relativize(file));
            if (!isRegistered(relative)) {
                unregistered.add(relative);
            }
        }
        assertTrue(unregistered.isEmpty(), """
                documents with no row in docs/README.md:
                %s
                Add each one to the ledger with its tier (每轮过一遍 / 按条件写 / 冻结) and when it changes.""".formatted(
                String.join("\n", unregistered)));
    }

    @Test
    void everyArchivedVolumeIsLinkedFromTheChangelogIndex() throws IOException {
        String changelog = Files.readString(docs.resolve(CHANGELOG));
        List<String> unlinked = new ArrayList<>();
        for (Path file : documents()) {
            if (!isArchived(file)) {
                continue;
            }
            String name = file.getFileName().toString();
            if (!changelog.contains(name)) {
                unlinked.add(ARCHIVE_DIRECTORY + "/" + name);
            }
        }
        assertTrue(unlinked.isEmpty(), """
                archived changelog volumes that the index does not link:
                %s
                A volume nobody links is a decision nobody can find again.""".formatted(
                String.join("\n", unlinked)));
    }

    /**
     * The main changelog keeps the index plus the three most recent rounds, and no more.
     *
     * <p>Without this the split is a one-off tidy-up: the next round appends a fourth section and
     * the file starts growing back, which is exactly how it reached 2166 lines and 262 entries with
     * no way in. Three is the documented number (see the file's own header), so it is checkable.
     */
    @Test
    void theChangelogKeepsOnlyTheThreeMostRecentRounds() throws IOException {
        String changelog = Files.readString(docs.resolve(CHANGELOG));
        int recent = changelog.indexOf("## 2. 最近 3 轮");
        assertTrue(recent > 0, CHANGELOG + " lost its '## 2. 最近 3 轮（全文）' section");
        long rounds = changelog.substring(recent).lines()
                .filter(line -> line.startsWith("# ") && !line.startsWith("## "))
                .count();
        assertTrue(rounds >= 1 && rounds <= 3, """
                the changelog holds %d rounds in full; the rule is 1..3.
                Split the oldest one out to docs/架构变更记录/YYYY-MM-<轮名>.md and add it to the
                index in §1.2, as the file's header describes.""".formatted(rounds));
    }

    /**
     * The ledger keeps its three tiers; a rewrite that drops one silently changes the policy.
     *
     * <p>Mostly a tripwire for a large edit of the ledger: the tiers are the whole point of the
     * file, and losing the word "冻结" would be invisible in review.
     */
    @Test
    void theLedgerStillNamesTheThreeTiers() {
        for (String tier : List.of("活·每轮过一遍", "活·按条件写", "冻结")) {
            assertTrue(ledger.contains(tier), "docs/README.md no longer mentions the tier " + tier);
        }
    }

    /**
     * The changelog's per-layer index counts the entries in each archived volume, and is right.
     *
     * <p>These five numbers are the only hand-written counts left in the live documents, and they had
     * already drifted once: the client volume claimed 42 and held 37, so a reader choosing which
     * volume to search had the wrong idea of its size. They are worth keeping - they are how you
     * decide which volume is worth opening - so they get a checker rather than being deleted.
     */
    @Test
    void theIndexedEntryCountsMatchTheArchivedVolumes() throws IOException {
        String changelog = Files.readString(docs.resolve(CHANGELOG));
        int start = changelog.indexOf("### 1.1");
        int end = changelog.indexOf("####", start);
        assertTrue(start > 0 && end > start, CHANGELOG + " lost its §1.1 per-layer table");
        Matcher rows = Pattern.compile("\\|\\s*\\[[^\\]]+\\]\\(([^)]+)\\)\\s*\\|\\s*(\\d+)\\s*\\|")
                .matcher(changelog.substring(start, end));
        List<String> problems = new ArrayList<>();
        int checked = 0;
        while (rows.find()) {
            Path volume = docs.resolve(rows.group(1));
            if (!Files.isRegularFile(volume)) {
                problems.add(rows.group(1) + " is linked from the index but does not exist");
                continue;
            }
            long actual = Files.readString(volume).lines().filter(line -> line.startsWith("## ")).count();
            long claimed = Long.parseLong(rows.group(2));
            checked++;
            if (actual != claimed) {
                problems.add(rows.group(1) + ": the index says " + claimed + " entries, it has " + actual);
            }
        }
        assertTrue(checked >= 5, "the §1.1 table lists only " + checked + " volumes (expected at least 5)");
        assertTrue(problems.isEmpty(), "stale entry counts in " + CHANGELOG + ":\n" + String.join("\n", problems));
    }

    private static boolean isRegistered(String relative) {
        if (ledger.contains(relative)) {
            return true;
        }
        Path path = Path.of(relative);
        String name = path.getFileName().toString();
        if (ledger.contains(name) || ledger.contains(name.replace(".md", ""))) {
            return true;
        }
        Path parent = path.getParent();
        return parent != null && ledger.contains(slash(parent) + "/*");
    }

    private static boolean isArchived(Path file) {
        return file.getParent() != null
                && file.getParent().getFileName().toString().equals(ARCHIVE_DIRECTORY);
    }

    private static List<Path> documents() throws IOException {
        try (Stream<Path> files = Files.walk(docs)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".md"))
                    .sorted()
                    .toList();
        }
    }

    private static String slash(Path path) {
        return path.toString().replace('\\', '/');
    }
}
