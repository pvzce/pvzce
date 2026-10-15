package com.pvzce.common.resource;

import com.pvzce.api.util.Identifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ClasspathPackTest {
    @TempDir Path temp;
    private static final String DIRECTORY = "assets/test/textures/body";

    @Test
    void componentDirectoriesAreNotAssetsInDevelopmentOrInAJar() throws Exception {
        Path root = temp.resolve("classes");
        Files.createDirectories(root.resolve(DIRECTORY));
        byte[] texture = {1, 2, 3};
        Files.write(root.resolve(DIRECTORY + "/part.png"), texture);
        Path jar = temp.resolve("assets.jar");
        try (var out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry(DIRECTORY + "/"));
            out.closeEntry();
            out.putNextEntry(new JarEntry(DIRECTORY + "/part.png"));
            out.write(texture);
            out.closeEntry();
        }
        for (Path source : new Path[]{root, jar}) {
            try (var loader = new URLClassLoader(new java.net.URL[]{source.toUri().toURL()}, null);
                 var resources = new PvzceResourceManager(loader)) {
                resources.init(temp.resolve("game"));
                assertTrue(resources.getResource(DIRECTORY).isEmpty());
                assertFalse(resources.hasTexture(Identifier.parse("test:textures/body")));
                assertFalse(resources.hasTexture(Identifier.parse("test:textures/missing")));
                assertTrue(resources.hasTexture(Identifier.parse("test:textures/body/part")));
                assertTrue(resources.hasTexture(Identifier.parse("test:textures/body/part.png")));
                assertArrayEquals(texture, resources.getResource(DIRECTORY + "/part.png")
                        .orElseThrow().bytes());
            }
        }
    }

    @Test
    void builtInSkeletonDirectoriesDoNotMasqueradeAsStaticSprites() throws Exception {
        try (var resources = new PvzceResourceManager(Thread.currentThread().getContextClassLoader())) {
            resources.init(temp);
            assertFalse(resources.hasTexture(Identifier.parse("pvzce:textures/entities/zombie/basic/basic_zombie")));
            assertFalse(resources.hasTexture(Identifier.parse("pvzce:textures/entities/zombie/armored/buckethead_zombie")));
            assertFalse(resources.hasTexture(Identifier.parse("pvzce:textures/entities/plant/attacker/iceboom_shroom")));
            assertTrue(resources.hasTexture(Identifier.parse("pvzce:textures/gui/cards/pea_shooter")));
        }
    }
}
