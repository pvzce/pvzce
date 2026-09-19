package com.pvzce.docs;

import com.pvzce.testutil.SourceTree;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The as-built documents name real code.
 *
 * <p>{@code 当前项目架构.md} and its per-layer books ({@code 架构-*.md}) plus
 * {@code UI切换与导航架构.md} describe what the project <em>is</em>,
 * and the way they go wrong is not by being vague - it is by naming a class that was deleted, or a
 * method that was renamed, so a reader follows the pointer into nothing. That had happened nine
 * times over one refactor (a {@code BeltSyncS2C}, a {@code usesConveyorBelt}, a
 * {@code ToolMechanic.of} that never existed under that name) while every test stayed green: the
 * documents' claims about <em>tests</em> were protected by the tests, and their claims about code
 * were protected by nobody.
 *
 * <p>Three shapes are checked, all precise enough that a failure is always a real stale reference:
 *
 * <ul>
 *   <li>{@code com.pvzce.a.b.Type} - the file must exist at that path;</li>
 *   <li>{@code Type.member} - the type must exist and some source file must declare that member
 *       (a method, a field, an enum constant or a record component);</li>
 *   <li>{@code camelCaseName} alone in backticks - some source file must mention that word, which
 *       is what catches a rename in prose where no type is named next to it.</li>
 * </ul>
 *
 * <p>Names that are only <em>prose</em> in backticks ({@code capabilities}, {@code behavior}) are
 * ignored by construction: a bare word is not a {@code Type.member} pair, and a dotted name whose
 * head is not a known type is reported only when it is fully qualified.
 *
 * <p>{@code Type.member} is also how the documents write three things that are <em>not</em> Java
 * members, so each is skipped by shape rather than by an exception list: a file name
 * ({@code ConveyorBelt.png}, {@code PvzceServer.java}), a data or save key
 * ({@code LevelDef.playable_teams}, {@code PlayerProfile.UnlockedLevels}) and a placeholder
 * ({@code Screen.mouseXxx}). The rules are in {@link #isJavaMemberName}.
 */
class DocumentedSymbolsTest {
    /** Spellings after a dot that name a file, not a member. */
    private static final Set<String> FILE_EXTENSIONS = Set.of(
            "java", "md", "json", "png", "ogg", "wav", "txt", "py", "gradle", "toml", "reanim");

    /** One identifier, for the "does any source mention this word" check. */
    private static final Pattern WORD = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /** Simple type name -> the file that declares it. */
    private static final Map<String, Path> TYPES = new HashMap<>();
    /** Simple type name -> that file's text, for member lookups. */
    private static final Map<String, String> TYPE_SOURCES = new HashMap<>();
    /** Every word that appears anywhere in the sources, for the bare-name check. */
    private static final Set<String> MENTIONED = new LinkedHashSet<>();
    private static Path docsDir;
    private static Path root;

    @BeforeAll
    static void indexSourcesAndDocs() throws IOException {
        root = SourceTree.root();
        Assumptions.assumeTrue(root != null, "not running from a source checkout; docs are not present");
        docsDir = root.resolve("docs");

        for (String sourceRoot : SourceTree.SOURCE_ROOTS) {
            for (Path file : SourceTree.javaFiles(root.resolve(sourceRoot))) {
                String name = file.getFileName().toString().replace(".java", "");
                TYPES.putIfAbsent(name, file);
                String source = Files.readString(file);
                TYPE_SOURCES.putIfAbsent(name, source);
                Matcher words = WORD.matcher(source);
                while (words.find()) {
                    MENTIONED.add(words.group());
                }
            }
        }
    }

    private static List<Path> documents() throws IOException {
        try (Stream<Path> files = Files.walk(docsDir)) {
            return files.filter(p -> p.toString().endsWith(".md")).sorted().toList();
        }
    }

    /** The documents this test holds to their word. */
    private static boolean isAsBuilt(Path document) {
        String name = document.getFileName().toString();
        return name.equals("当前项目架构.md") || name.equals("UI切换与导航架构.md")
                || (name.startsWith("架构-") && name.endsWith(".md"));
    }

    @Test
    void everyFullyQualifiedNameInTheDocsExists() throws IOException {
        Pattern qualified = Pattern.compile("\\bcom\\.pvzce(?:\\.[a-z][a-z0-9_]*)*\\.([A-Z][A-Za-z0-9]*)\\b");
        Set<String> problems = new LinkedHashSet<>();
        for (Path document : documents()) {
            if (!isAsBuilt(document)) {
                continue;
            }
            String text = Files.readString(document);
            Matcher matcher = qualified.matcher(text);
            while (matcher.find()) {
                String typeName = matcher.group(1);
                if (!TYPES.containsKey(typeName)) {
                    problems.add(document.getFileName() + " names " + matcher.group()
                            + ", which is not a source file");
                }
            }
        }
        assertTrue(problems.isEmpty(), "stale fully-qualified names:\n" + String.join("\n", problems));
    }

    /**
     * {@code Type.member} in backticks: the type exists and some source file declares the member.
     *
     * <p>A declaration is looked for as {@code member(} (a method), or as the bare word (a field, an
     * enum constant, a record component). That is deliberately loose about <em>where</em> the
     * member is: a document that says "the level's {@code plantAt}" is not claiming which class it
     * is on, only that the concept exists.
     */
    @Test
    void everyMemberTheDocsNameExists() throws IOException {
        Pattern member = Pattern.compile("`([A-Z][A-Za-z0-9]*)\\.([a-zA-Z_][A-Za-z0-9_]*)`");
        Set<String> problems = new LinkedHashSet<>();
        for (Path document : documents()) {
            if (!isAsBuilt(document)) {
                continue;
            }
            String text = Files.readString(document);
            Matcher matcher = member.matcher(text);
            while (matcher.find()) {
                String typeName = matcher.group(1);
                String memberName = matcher.group(2);
                Path declaring = TYPES.get(typeName);
                if (declaring == null || !isJavaMemberName(memberName)) {
                    // Not a type we ship, or not a Java member at all.
                    continue;
                }
                String source = TYPE_SOURCES.get(typeName);
                if (!declares(source, memberName)) {
                    problems.add(document.getFileName() + " says `" + typeName + "." + memberName
                            + "`, but " + declaring.getFileName() + " does not declare " + memberName);
                }
            }
        }
        assertTrue(problems.isEmpty(), "stale member references:\n" + String.join("\n", problems));
    }

    /**
     * Whether a dotted name is a Java member, so that the three lookalikes are not reported.
     *
     * <ul>
     *   <li>{@code png} - a file extension, from a mention of the asset itself;</li>
     *   <li>{@code playable_teams} - lower snake case, which is how the JSON is spelled, written as
     *       {@code LevelDef.playable_teams} next to the record component it feeds;</li>
     *   <li>{@code UnlockedLevels} - upper camel case, which is a nested type or a key in a save
     *       file rather than a member of the type before the dot;</li>
     *   <li>{@code mouseXxx} - a placeholder for a family of methods.</li>
     * </ul>
     */
    private static boolean isJavaMemberName(String member) {
        if (FILE_EXTENSIONS.contains(member) || member.contains("Xxx") || member.contains("xxx")) {
            return false;
        }
        if (member.indexOf('_') >= 0 && !member.equals(member.toUpperCase())) {
            return false;
        }
        return !(Character.isUpperCase(member.charAt(0)) && !member.equals(member.toUpperCase()));
    }

    /**
     * A name the documents write without a class in front of it.
     *
     * <p>{@code Type.member} only catches a rename when the document also names the type. The
     * commonest drift is worse than that: the document says "the server's {@code tickWaves} counts
     * the gap between waves" and the method is now {@code WaveDirector.tick} - a reader has no
     * class to look it up under, and nothing to compare against. Eight such names had accumulated
     * ({@code tickWaves}, {@code rebuildBeltSlots}, {@code readSavedSeedSelection},
     * {@code sanitizeWorldName}, {@code levelKey}, {@code worldPath}, {@code hitTicks},
     * {@code sanitizeSeedSelection}).
     *
     * <p>Only lower camel case is checked, so the three shapes that legitimately have no Java
     * declaration are excluded by construction: an asset or original-game name ({@code VaseShatter},
     * {@code BossExplosion}, {@code ParticleScale}, {@code PoolCleaner}), a planned type
     * ({@code LevelEntryRequest}) and a retired one named on purpose ({@code WorldSelectScreen},
     * {@code BeltSyncS2C}) all start with a capital, and a data key has underscores
     * ({@code max_seed_slots}). What is left is a name that can only be code.
     *
     * <p>The sources are searched as text, so a name that survives only inside another file's
     * comment counts as present. That is deliberate: this test is about the documents, and a
     * rename that leaves stale javadoc behind is a job for the compiler and for review.
     */
    @Test
    void everyBareCamelCaseNameInTheDocsExists() throws IOException {
        Pattern bare = Pattern.compile("`([a-z][A-Za-z0-9]*[A-Z][A-Za-z0-9]*)`");
        Set<String> problems = new LinkedHashSet<>();
        for (Path document : documents()) {
            if (!isAsBuilt(document)) {
                continue;
            }
            String text = Files.readString(document);
            Matcher matcher = bare.matcher(text);
            while (matcher.find()) {
                String name = matcher.group(1);
                if (TYPES.containsKey(name)) {
                    continue;
                }
                if (!MENTIONED.contains(name)) {
                    problems.add(document.getFileName() + " says `" + name
                            + "`, which appears in no source file");
                }
            }
        }
        assertTrue(problems.isEmpty(), "stale names in prose:\n" + String.join("\n", problems));
    }

    private static boolean declares(String source, String member) {
        if (source == null) {
            return false;
        }
        return source.contains(member + "(") || source.contains(member + ";")
                || source.contains(member + ",") || source.contains(member + ")")
                || source.contains(member + " ") || source.contains(member + "\n");
    }

    /** A reminder that this file is only as good as the documents it reads. */
    @Test
    void theAsBuiltDocumentsAreStillThere() throws IOException {
        List<String> present = new ArrayList<>();
        for (String name : List.of("当前项目架构.md", "UI切换与导航架构.md", "代码规范.md",
                "验证约定.md", "架构变更记录.md")) {
            if (Files.isRegularFile(docsDir.resolve(name))) {
                present.add(name);
            }
        }
        assertTrue(present.contains("当前项目架构.md") && present.contains("UI切换与导航架构.md"),
                "the two as-built documents must exist for this test to mean anything");

        // A new 架构-*.md book joins the checked set by name, so the split of a book (or a new
        // layer book) cannot quietly escape the checks above by not being listed anywhere.
        List<String> books = new ArrayList<>();
        for (Path document : documents()) {
            String name = document.getFileName().toString();
            if (name.startsWith("架构-")) {
                books.add(name);
                assertTrue(isAsBuilt(document), name + " is not treated as an as-built document");
            }
        }
        assertTrue(books.contains("架构-服务端.md"),
                "the split books must be present, found: " + books);
    }
}
