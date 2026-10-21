package com.pvzce.client.renderer;

import com.pvzce.api.content.SurfaceProfile;
import com.pvzce.client.renderer.liquid.LiquidShader;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Opt-in native regression for depth comparison, PCF bias and render-state isolation. */
class WorldShadowsTest {
    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path directory;
    private static final int SIZE = 256;

    private static float sample(float x, float y) {
        ByteBuffer pixel = ByteBuffer.allocateDirect(4);
        GL11.glReadPixels((int) (x / 9 * SIZE), (int) (y / 5 * SIZE), 1, 1,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixel);
        return (pixel.get(0) & 255) / 255F;
    }

    private static void drawFloor() {
        RenderSystem.viewport(0, 0, SIZE, SIZE);
        RenderSystem.setProjectionMatrix(Matrix4f.ortho(0, 9, 0, 5, -10, 10));
        RenderSystem.clear(0, 0, 0, 1);
        SpriteRenderer.solid(0, 0, 9, 5, 0, 1, 1, 1, 1);
    }

    @Test
    void depthMapsOccludeAndRestoreRenderState() {
        assumeTrue(Boolean.getBoolean("pvzce.testGraphics"),
                "Native rendering check: enable pvzce.testGraphics in a desktop session");
        assertTrue(GLFW.glfwInit());
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        long window = GLFW.glfwCreateWindow(SIZE, SIZE, "Shadow regression", 0, 0);
        assertNotEquals(0, window, "A real OpenGL context is required");
        GLFW.glfwMakeContextCurrent(window);
        GL.createCapabilities();
        try {
            RenderSystem.init();
            // Native linking catches sampler conflicts which source validation cannot see.
            try (LiquidShader water = new LiquidShader()) {
                water.use();
                water.setLighting(1, 1, 1, 0, 0, 4, 8, 1, 1, 1, 1);
            }
            RenderSystem.setShader();
            RenderSystem.setGuiShader();
            RenderSystem.clearPointLights();
            var config = com.pvzce.client.config.PvzceClientConfig.load(directory);
            RenderSystem.shadows().configure(config);
            var camera = new PvzceCamera(SIZE, SIZE, 9, 5);
            Runnable caster = () -> {
                RenderSystem.shader().shadows().receiver(true, 2);
                SpriteRenderer.solid(3, 2, 1, 1, 0, 1, 1, 1, 1);
            };
            RenderSystem.shadows().render(camera, 4.5F, 15, 9, 5, caster);
            assertFalse(GL11.glIsEnabled(GL11.GL_DEPTH_TEST));
            assertTrue(GL11.glIsEnabled(GL11.GL_BLEND));
            drawFloor();
            assertEquals(0.62F, sample(3.5F, 1.84F), 0.03F, "Occluded ground retains only ambient light");
            assertEquals(1F, sample(3.5F, 2.5F), 0.01F, "Unoccluded ground retains direct light");

            RenderSystem.shader().shadows().receiver(true, 2);
            SpriteRenderer.solid(3, 2, 1, 1, 0, 1, 1, 1, 1);
            assertEquals(1F, sample(3.5F, 2.5F), 0.03F, "PCF must not shadow the receiving billboard itself");
            RenderSystem.shader().shadows().receiver(false, 0);

            RenderSystem.clearPointLights();
            RenderSystem.setPointLight(0, 5, 3, 5, 1, 0, 0, 1);
            RenderSystem.shadows().render(camera, 4.5F, 15, 9, 5, caster);
            drawFloor();
            SpriteRenderer.solid(0, 0, 9, 5, 0, 0.25F, 0.25F, 0.25F, 1);
            assertTrue(sample(2.75F, 2.5F) - sample(2.75F, 1.8F) > 0.04F,
                    "A billboard must block the local light behind it");
            float occluded = sample(2.75F, 1.8F);
            config.setEffectEnabled(com.pvzce.client.config.PvzceClientConfig.ShaderEffect.LOCAL_SHADOWS, false);
            RenderSystem.shadows().configure(config);
            int[] passes = {0};
            RenderSystem.shadows().render(camera, 4.5F, 15, 9, 5, () -> { passes[0]++; caster.run(); });
            assertEquals(1, passes[0], "Disabling local shadows must skip their depth passes");
            drawFloor();
            SpriteRenderer.solid(0, 0, 9, 5, 0, 0.25F, 0.25F, 0.25F, 1);
            assertTrue(sample(2.75F, 1.8F) > occluded + 0.05F, "The local light remains enabled without its shadow");
            config.resetLighting();
            config.setEffectEnabled(com.pvzce.client.config.PvzceClientConfig.ShaderEffect.SOFT_SHADOWS, false);
            RenderSystem.shadows().configure(config);
            RenderSystem.shadows().render(camera, 4.5F, 15, 9, 5, caster);
            int radiusUniform = org.lwjgl.opengl.GL20.glGetUniformLocation(RenderSystem.shader().id(), "uShadowFilterRadius");
            assertEquals(0, org.lwjgl.opengl.GL20.glGetUniformi(RenderSystem.shader().id(), radiusUniform),
                    "Soft shadows off selects a single comparison tap");
            config.resetLighting();
            for (int quality = 0; quality <= 2; quality++) {
                config.setShadowQuality(quality);
                RenderSystem.shadows().configure(config);
                RenderSystem.shadows().render(camera, 4.5F, 15, 9, 5, caster);
                org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE2);
                int allocated = GL11.glGetTexLevelParameteri(org.lwjgl.opengl.GL30.GL_TEXTURE_2D_ARRAY, 0, GL11.GL_TEXTURE_WIDTH);
                org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0);
                assertEquals(512 << quality, allocated, "Quality changes the actual depth texture size");
            }
            config.setShadowQuality(com.pvzce.client.config.PvzceClientConfig.ShadowQuality.OFF);
            RenderSystem.shadows().configure(config);
            RenderSystem.shadows().render(camera, 4.5F, 15, 9, 5, () -> fail("Off must skip all depth passes"));
            drawFloor();
            SpriteRenderer.solid(0, 0, 9, 5, 0, 0.25F, 0.25F, 0.25F, 1);
            assertTrue(sample(2.75F, 1.8F) > occluded + 0.05F, "Turning off shadows must retain point lighting");
            config.resetLighting();
            RenderSystem.shadows().configure(config);

            RenderSystem.clearPointLights();
            RenderSystem.shadows().render(camera, 4.5F, 15, 9, 5, () -> {
                RenderSystem.shader().shadows().receiver(true, 2);
                SpriteRenderer.solid(3, 3, 1, 1, 0, 1, 1, 1, 1);
            });
            drawFloor();
            RenderSystem.shader().shadows().surface(SurfaceProfile.flat(1));
            SpriteRenderer.solid(0, 1, 9, 5, 0, 1, 1, 1, 1);
            assertEquals(0.62F, sample(3.5F, 2.84F), 0.03F, "Raised ground receives at its actual height");
            assertEquals(1F, sample(3.5F, 1.84F), 0.03F, "The shadow must not stay on the lower plane");
            RenderSystem.shader().shadows().receiver(false, 0);

            RenderSystem.shadows().render(camera, 4.5F, 15, 9, 5, () -> {
                RenderSystem.shader().shadows().receiver(true, 2);
                SpriteRenderer.solid(3, 2, 1, 1, 0, 1, 1, 1, 0.2F);
                RenderSystem.blendAdditive();
                SpriteRenderer.solid(3, 2, 1, 1, 0, 1, 1, 1, 1);
                RenderSystem.blendNormal();
            });
            drawFloor();
            assertEquals(1F, sample(3.5F, 1.84F), 0.01F, "Transparent and additive parts must not occlude");

            RenderSystem.shadows().render(camera, 4.5F, 15, 9, 5, caster);
            RenderSystem.setGuiShader();
            drawFloor();
            assertEquals(1F, sample(3.5F, 1.84F), 0.01F, "World shadows must not leak into GUI");
            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
        } finally {
            com.pvzce.client.renderer.vertex.Tesselator.INSTANCE.close();
            RenderSystem.shadows().close();
            RenderSystem.shader().close();
            GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
        }
    }
}
