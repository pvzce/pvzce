package com.pvzce.client.renderer.liquid;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the liquid shader SOURCE, which nothing else in the suite can reach: the
 * GLSL is a Java text block, no test has a GL context, and a shader that fails to
 * compile does not throw - {@code LiquidRenderer} catches it and quietly falls back
 * to drawing the base texture in every cell.
 *
 * <p>That combination shipped a real regression: a uniform was deleted, a later
 * change kept using it, and the game silently lost its entire water surface - no
 * waves, no caustics, uniform colour - while every test stayed green. Both checks
 * here would have failed on it.
 */
class LiquidShaderSourceTest {

    /** A fresh directory per test; JUnit deletes it, and prints it when a test fails. */
    @TempDir
    Path directory;
    /**
     * Compiles the shader with glslangValidator if the tool is available.
     *
     * <p>Exit codes: 0 is success, 1 is "ran but could not do what was asked" - which
     * includes an unknown {@code #version} on an older glslang - and 2 or more is a
     * real compile error. Version trouble must not fail a build on a machine with a
     * different glslang, so only a compile error is fatal.
     */
    @Test
    void theShaderCompiles() throws Exception {
        String validator = findValidator();
        if (validator == null) {
            System.out.println("[LiquidShaderSourceTest] glslangValidator not found; "
                    + "skipping the GLSL compile check");
            return;
        }
        Path vertex = directory.resolve("liquid.vert");
        Path fragment = directory.resolve("liquid.frag");
        Files.writeString(vertex, LiquidShader.VERTEX, StandardCharsets.UTF_8);
        Files.writeString(fragment, LiquidShader.FRAGMENT, StandardCharsets.UTF_8);

        Process process = new ProcessBuilder(validator, "-l", vertex.toString(), fragment.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "glslangValidator timed out");
        int exit = process.exitValue();
        if (exit == 1) {
            System.out.println("[LiquidShaderSourceTest] glslangValidator could not validate "
                    + "this GLSL version here; skipping: " + output.trim());
            return;
        }
        assertEquals(0, exit, "the liquid shader does not compile:\n" + output);
    }

    /**
     * Every {@code uniform} the GLSL declares must be looked up by name.
     *
     * <p>A declared-but-unqueried uniform is fine for the compiler and silently reads
     * as 0 at runtime, which is how a shader loses a whole term without any error
     * anywhere.
     */
    @Test
    void everyDeclaredUniformIsLookedUp() {
        Matcher declarations = Pattern.compile("(?m)^\\s*uniform\\s+\\w+\\s+(\\w+)").matcher(LiquidShader.FRAGMENT);
        List<String> missing = new ArrayList<>();
        int declared = 0;
        while (declarations.find()) {
            declared++;
            String name = declarations.group(1);
            if (!sourceContainsLookup(name)) {
                missing.add(name);
            }
        }
        assertTrue(declared > 20, "expected the fragment shader to declare its uniforms, found " + declared);
        assertTrue(missing.isEmpty(),
                "declared in the GLSL but never queried from Java, so they read as 0: " + missing);
    }

    /**
     * The lookups live in this same Java file, so the check reads the file rather
     * than the compiled class (Java sources are not on the classpath).
     */
    private static boolean sourceContainsLookup(String uniformName) {
        String path = "src/main/java/com/pvzce/client/renderer/liquid/LiquidShader.java";
        Path file = Path.of(path);
        for (Path directory = Path.of("").toAbsolutePath();
             directory != null && !Files.isRegularFile(file);
             directory = directory.getParent()) {
            file = directory.resolve(path);
        }
        try {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            // An array uniform is queried at its first element - "uRipples[0]" is the
            // documented way to set a whole array - so the index form counts too.
            return source.contains("uniform(\"" + uniformName + "\")")
                    || source.contains("uniform(\"" + uniformName + "[0]\")");
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * The middle of a lake must never be feathered away.
     *
     * <p>Regression guard for a bug that only appeared in the running game.
     * {@code field} is the distance to the land-facing part of a cell's outline, and
     * it was left at its {@code 0.0} default for cells with no such outline. Zero is
     * also the value that means "this fragment IS on a shore", so {@code alpha} fell
     * to 0 across every interior cell: the dirt showed straight through the centre of
     * a lake and only its rim - the cells that do touch land - rendered as water.
     * Nothing in the suite could see it, because the fragment source is never run
     * without a GL context.
     *
     * <p>An open-water fragment therefore has to start from a value far beyond any
     * feather or foam band, and the shader must never initialise the field to 0.
     */
    @Test
    void openWaterIsNotFeatheredAway() {
        String fragment = LiquidShader.FRAGMENT;
        Matcher fieldInit = Pattern.compile("(?m)^\\s*float\\s+field\\s*=\\s*([^;]+);")
                .matcher(fragment);
        assertTrue(fieldInit.find(), "the fragment shader no longer declares `float field = ...`; "
                + "this test has to be updated alongside it");
        String initial = fieldInit.group(1).trim();
        // The initialiser may be a literal or a named constant; the constant carries
        // the reasoning, so both forms are accepted and then resolved.
        double sentinel;
        if (initial.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            Matcher constant = Pattern.compile("(?m)^\\s*const\\s+float\\s+" + initial
                    + "\\s*=\\s*([0-9.]+(?:[eE][-+]?\\d+)?)[fF]?\\s*;").matcher(fragment);
            assertTrue(constant.find(), "`field` starts from `" + initial + "`, but the fragment "
                    + "shader no longer defines that constant");
            sentinel = Double.parseDouble(constant.group(1));
        } else {
            Matcher literal = Pattern.compile("^(\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?)[fF]?$")
                    .matcher(initial);
            assertTrue(literal.matches(),
                    "`field` must start from a large finite sentinel for open water, but it "
                            + "starts from `" + initial + "`. A literal 0 is the value that means "
                            + "\"on a shore\", which feathers the whole middle of the water away.");
            sentinel = Double.parseDouble(literal.group(1));
        }
        assertTrue(sentinel >= 1.0,
                "the open-water sentinel (" + sentinel + ") must exceed every feather and foam "
                        + "band, or interior cells still fade out near their own edges");

        // Cells that DO face land must still take their distance from shoreField, or
        // the sentinel would leak onto real shorelines and remove the foam instead.
        assertTrue(fragment.contains("field = shoreField("),
                "shore-facing cells no longer read shoreField(), so the open-water sentinel "
                        + "leaks onto real shorelines and the foam disappears");
    }

    private static String findValidator() {
        for (String candidate : new String[]{"glslangValidator", "glslang"}) {
            try {
                Process process = new ProcessBuilder(candidate, "--version")
                        .redirectErrorStream(true).start();
                process.getInputStream().readAllBytes();
                if (process.waitFor(20, TimeUnit.SECONDS) && process.exitValue() == 0) {
                    return candidate;
                }
            } catch (IOException | InterruptedException ignored) {
                // Try the next candidate; a missing tool is not a test failure.
            }
        }
        return null;
    }
}
