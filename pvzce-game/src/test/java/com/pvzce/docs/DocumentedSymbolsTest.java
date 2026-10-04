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
            indexSources(root.resolve(sourceRoot));
        }
        // The loader fork is not one of the shared source roots (the layering test has no business
        // there), but the documents describe it, so its names have to count as existing.
        indexSources(root.resolve("pvzce-loader/src"));
    }

    /** Adds one source tree's type names and vocabulary to the index. */
    private static void indexSources(Path base) throws IOException {
        for (Path file : SourceTree.javaFiles(base)) {
            String name = file.getFileName().toString().replace(".java", "");
            TYPES.putIfAbsent(name, file);
            String source = Files.readString(file);
            TYPE_SOURCES.putIfAbsent(name, source);
            if (isThisTestsOwnPackage(file)) {
                // The guard's own prose names the very symbols it is meant to catch. Counting those
                // mentions as "the name exists somewhere" is how `BeltSyncS2C` stayed alive for a
                // whole refactor after it was deleted: the only file that still mentioned it was
                // this one, listing it as an example of a retired name.
                continue;
            }
            Matcher words = WORD.matcher(source);
            while (words.find()) {
                MENTIONED.add(words.group());
            }
        }
    }

    /** Whether a source file is part of this test class's own package. */
    private static boolean isThisTestsOwnPackage(Path file) {
        return file.toString().replace('\\', '/').contains("/com/pvzce/docs/");
    }

    private static List<Path> documents() throws IOException {
        try (Stream<Path> files = Files.walk(docsDir)) {
            return files.filter(p -> p.toString().endsWith(".md")).sorted().toList();
        }
    }

    /**
     * The live documents this test holds to their word at all.
     *
     * <p>Everything in {@code docs/} itself except the four frozen planning documents and the
     * changelog index. The archive directories are excluded by directory, not by file name: the
     * archived changelog volumes ({@code docs/架构变更记录/}), the frozen reports ({@code docs/报告/})
     * and the third-party guide ({@code docs/mod-guide/}) all describe the code as it was at the time,
     * so naming a class that has since been renamed is correct there.
     *
     * <p>This set is what the <em>precise</em> checks run over ({@code Type.member} and fully
     * qualified names): both are claims about a specific declaration, so a hit is always real drift.
     */
    private static boolean isLiveDocument(Path document) {
        if (!docsDir.equals(document.getParent())) {
            return false;
        }
        String name = document.getFileName().toString();
        // Frozen: the three stage plans and the editor design are snapshots, not descriptions.
        if (name.startsWith("01-") || name.startsWith("02-") || name.startsWith("03-")
                || name.equals("关卡机制与拼装式编辑器-设计方案.md")) {
            return false;
        }
        // The changelog index records what used to be true; it has to be able to name deleted things.
        return !name.equals("架构变更记录.md");
    }

    /**
     * The documents whose job is to say what the code <em>is</em>, so a type name in them is a claim.
     *
     * <p>Narrower than {@link #isLiveDocument} on purpose. A few live documents legitimately name
     * things that do not exist as source files: {@code 代码规范.md} prescribes names
     * ({@code SlotKind}, {@code FormRow}), {@code 验证约定.md} and {@code 冒烟与截图指南.md} quote
     * Gradle and GLFW APIs, and {@code 00} is a plan. The eight below are the ones a reader (or an
     * agent) opens to find out what exists today.
     */
    private static boolean describesTheCode(Path document) {
        if (!isLiveDocument(document)) {
            return false;
        }
        String name = document.getFileName().toString();
        return name.equals("当前项目架构.md") || name.equals("UI切换与导航架构.md")
                || name.equals("README.md") || name.equals("决策记录.md")
                || name.equals("todo.md") || name.equals("踩坑清单.md")
                || (name.startsWith("架构-") && name.endsWith(".md"));
    }

    /** A name written in backticks that is followed by an argument list, so it claims to be a call. */
    private static final Pattern CALL_SHAPED = Pattern.compile("`([A-Z][A-Za-z0-9]*)\\([^`]*\\)`");

    /**
     * Suffixes that make a PascalCase name a claim about a type of ours rather than a sketch.
     *
     * <p>{@code Status(x, energy)} and {@code POSITION(3)+COLOR(4)} are how these documents draw the
     * <em>shape</em> of a record or a vertex layout, and {@code AddGraveStones(6, 1)} is an
     * original-game function; all three are call-shaped without being calls. Naming the suffixes is
     * what keeps this check at zero false positives, and the failure it does catch is the expensive
     * one: {@code BeltSyncS2C(cards)} and {@code RequestLevelC2S(restart)} both read as a packet you
     * can send, and neither class exists.
     */
    private static final Pattern TYPE_LIKE_SUFFIX = Pattern.compile(
            "(S2C|C2S|Mechanic|Capability|Packet|Manager|Screen|Dialog|Renderer|Model|Page|Brain|"
                    + "Source|System|Engine|Driver|Def|Registry|Factory|Listener|Handler)$");

    /** A bare PascalCase name in backticks, claiming a type exists. */
    private static final Pattern BARE_TYPE = Pattern.compile("`([A-Z][A-Za-z0-9_]*)`");

    /**
     * A line that is talking about the past or about the original game, where a name we do not ship
     * is the right name: {@code TcpPacketTransport} was deleted on purpose and the document says so,
     * and {@code VaseShatter.xml} is an asset of the original, not of this repository.
     */
    private static final Pattern MENTIONS_THE_PAST = Pattern.compile(
            "曾经|已删|已不存在|不再|退役|原版|旧实现|已经不|以前|被退|过去|当年");

    @Test
    void everyFullyQualifiedNameInTheDocsExists() throws IOException {
        Pattern qualified = Pattern.compile("\\bcom\\.pvzce(?:\\.[a-z][a-z0-9_]*)*\\.([A-Z][A-Za-z0-9]*)\\b");
        Set<String> problems = new LinkedHashSet<>();
        for (Path document : documents()) {
            if (!isLiveDocument(document)) {
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
            if (!isLiveDocument(document)) {
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
     * <p>Only lower camel case is checked here, so a data key with underscores
     * ({@code max_seed_slots}) and every capitalised name are excluded by construction. The
     * capitalised half is {@link #everyBareTypeNameInTheDocsExists}'s job, and it needs the extra
     * shape rules that this one gets for free. What is left for this test is a name that can only be
     * code.
     *
     * <p>The sources are searched as text, so a name that survives only inside another file's
     * comment counts as present. That is deliberate: this test is about the documents, and a
     * rename that leaves stale javadoc behind is a job for the compiler and for review. This test's
     * own package is the one exception - see {@link #isThisTestsOwnPackage}.
     */
    @Test
    void everyBareCamelCaseNameInTheDocsExists() throws IOException {
        Pattern bare = Pattern.compile("`([a-z][A-Za-z0-9]*[A-Z][A-Za-z0-9]*)`");
        Set<String> problems = new LinkedHashSet<>();
        for (Path document : documents()) {
            if (!describesTheCode(document)) {
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

    /**
     * A type name written with an argument list has to be a type we ship.
     *
     * <p>This is the shape the expensive drift takes. {@code BeltSyncS2C(cards)} read as a packet to
     * send for two rounds after the belt moved to a mechanic payload, and {@code
     * RequestLevelC2S(restart)} / {@code StartLevelC2S(restart, seeds)} named the two level-entry
     * packets wrongly after they were split into three. Three of the four shapes that look the same
     * but claim nothing are excluded by suffix: a record's outline ({@code Status(tick, energy)}), a
     * vertex layout ({@code POSITION(3)+COLOR(4)}) and an original-game function
     * ({@code AddGraveStones(6, 1)}). Nested types are matched by simple name only, which is why the
     * suffix list avoids words like {@code State} and {@code Animation}.
     */
    @Test
    void everyCallShapedTypeNameInTheDocsExists() throws IOException {
        Set<String> problems = new LinkedHashSet<>();
        for (Path document : documents()) {
            if (!describesTheCode(document)) {
                continue;
            }
            String text = Files.readString(document);
            Matcher matcher = CALL_SHAPED.matcher(text);
            while (matcher.find()) {
                String name = matcher.group(1);
                if (!TYPE_LIKE_SUFFIX.matcher(name).find() || TYPES.containsKey(name)) {
                    continue;
                }
                problems.add(document.getFileName() + " writes `" + matcher.group(1)
                        + "(...)`, but no source file declares that type");
            }
        }
        assertTrue(problems.isEmpty(), "call-shaped names with no type:\n" + String.join("\n", problems));
    }

    /**
     * A PascalCase name in backticks has to be a type, a word some source file uses, or marked as gone.
     *
     * <p>The counterpart of {@link #everyBareCamelCaseNameInTheDocsExists}, which cannot see this
     * shape: a deleted class starts with a capital, and the old rule exempted every capitalised name
     * on the grounds that asset names, planned types and retired types look the same. That exemption
     * is what let {@code WaveGenerator}, {@code WaveWarningFinal} and {@code RoofCleaner} sit in the
     * server book as if they were code.
     *
     * <p>The three shapes that really do have no declaration are handled by shape rather than by an
     * exception list: a file name ({@code PoolCleaner.reanim}) or a placeholder ({@code WallnutEat*})
     * is skipped outright, and a line that is already talking about the past or about the original
     * game ({@code MENTIONS_THE_PAST}) is allowed to name what it is talking about.
     */
    @Test
    void everyBareTypeNameInTheDocsExists() throws IOException {
        Set<String> problems = new LinkedHashSet<>();
        for (Path document : documents()) {
            if (!describesTheCode(document)) {
                continue;
            }
            String text = Files.readString(document);
            for (String line : text.split("\n", -1)) {
                if (MENTIONS_THE_PAST.matcher(line).find()) {
                    continue;
                }
                Matcher matcher = BARE_TYPE.matcher(line);
                while (matcher.find()) {
                    String name = matcher.group(1);
                    if (name.equals(name.toUpperCase(java.util.Locale.ROOT)) || TYPES.containsKey(name)
                            || MENTIONED.contains(name) || name.indexOf('_') >= 0) {
                        // All-caps is a constant, an embedded underscore is a data key or an
                        // original-asset name (`Zombie_duckytube`, `ALC_SOFT_device_clock`).
                        continue;
                    }
                    problems.add(document.getFileName() + " says `" + name
                            + "`, which is neither a declared type nor a word any source file uses");
                }
            }
        }
        assertTrue(problems.isEmpty(), "stale type names:\n" + String.join("\n", problems));
    }

    private static boolean declares(String source, String member) {
        if (source == null) {
            return false;
        }
        // Word boundaries, not a bare substring: `LINGER_TICKS` used to pass because
        // `DEFAULT_LINGER_TICKS` was in the file, so a document could name a constant that does not
        // exist and stay green as long as some other constant ended with the same word.
        return Pattern.compile("\\b" + Pattern.quote(member) + "\\b").matcher(source).find();
    }

    /** A reminder that this file is only as good as the documents it reads. */
    @Test
    void theAsBuiltDocumentsAreStillThere() throws IOException {
        List<String> present = new ArrayList<>();
        for (String name : List.of("当前项目架构.md", "UI切换与导航架构.md", "代码规范.md",
                "验证约定.md", "架构变更记录.md", "README.md", "决策记录.md", "todo.md")) {
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
                assertTrue(describesTheCode(document), name + " is not treated as an as-built document");
            }
        }
        assertTrue(books.contains("架构-服务端.md"),
                "the split books must be present, found: " + books);
    }
}
