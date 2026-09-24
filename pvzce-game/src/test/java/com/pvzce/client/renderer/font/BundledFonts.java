package com.pvzce.client.renderer.font;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The bundled TTFs, as {@link TtfFace}s.
 *
 * <p>One helper for every test that needs a real font, because of a constraint that
 * is easy to trip over: a face may only be loaded <em>once</em> per JVM. Repeatedly
 * allocating and releasing a 10-15MB font buffer makes glibc abort at process
 * teardown on a tight address-space limit ("too many chunks detected in tcache"),
 * and the abort reproduces with a probe that does nothing but call stb_truetype in a
 * loop - no project code involved. {@code TtfFace.of} caches by file name and never
 * frees, so asking twice is free; asking under two different names is not, which is
 * why the extension is dropped here and the cache key is the bare stem.
 */
final class BundledFonts {
    private BundledFonts() {
    }

    /** The shared face for a bundled font, by file name with or without {@code .ttf}. */
    static TtfFace face(String file) throws IOException {
        String resource = file.endsWith(".ttf") ? file : file + ".ttf";
        try (InputStream stream = BundledFonts.class.getClassLoader()
                .getResourceAsStream("assets/pvzce/font/" + resource)) {
            assertNotNull(stream, "bundled font missing from resources: " + resource);
            return TtfFace.of(resource, stream.readAllBytes());
        }
    }
}
