package com.pvzce.client.renderer;

import com.pvzce.client.config.PvzceClientConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;
import java.nio.ByteBuffer;
import java.nio.file.Path;

import static com.pvzce.client.config.PvzceClientConfig.LightingParameter.*;
import static com.pvzce.client.config.PvzceClientConfig.ShaderEffect.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Real GPU output checks: these cannot be replaced by assertions about shader source strings. */
class WorldPostProcessTest {
    @TempDir Path directory;
    private static final int SIZE = 256;
    private PvzceClientConfig config;
    private boolean overhead;
    private final PvzceCamera camera = new PvzceCamera(SIZE, SIZE, 9, 5);

    private float[] pixel(int x, int y) {
        var data = ByteBuffer.allocateDirect(4);
        GL11.glReadPixels(x, y, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, data);
        return new float[]{(data.get(0)&255)/255F, (data.get(1)&255)/255F, (data.get(2)&255)/255F};
    }

    private float[] frame(float r, float g, float b, boolean patch, float night) {
        RenderSystem.setShader();
        RenderSystem.setGuiShader();
        RenderSystem.shadows().configure(config);
        RenderSystem.shadows().render(camera, 4.5F, 15, 9, 5, () -> {
            if (overhead) {
                RenderSystem.shader().shadows().surface(com.pvzce.api.content.SurfaceProfile.flat(3));
                SpriteRenderer.solid(camera.worldLeft()-10, camera.worldBottom()-10+3,
                        camera.worldWidth()+20, camera.worldHeight()+20, 0, 1, 1, 1, 1);
            }
        });
        RenderSystem.shader().shadows().receiver(false, 0);
        // Isolate scattering from surface illumination in the final color comparison.
        RenderSystem.shader().shadows().disable();
        RenderSystem.viewport(0, 0, SIZE, SIZE);
        RenderSystem.setProjectionMatrix(Matrix4f.ortho(0, 1, 0, 1, -10, 10));
        RenderSystem.post().begin(SIZE, SIZE, config, camera, night);
        assertEquals(config.effectEnabled(MSAA) ? Math.min(4, GL11.glGetInteger(GL30.GL_MAX_SAMPLES)) : 0, GL11.glGetInteger(GL13.GL_SAMPLES));
        SpriteRenderer.solid(0, 0, 1, 1, 0, r, g, b, 1);
        if (patch) SpriteRenderer.solid(0.45F, 0.45F, 0.1F, 0.1F, 0, 5, 4, 2, 1);
        RenderSystem.post().finish();
        assertEquals(0, GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING));
        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
        return pixel(SIZE/2, SIZE/2);
    }

    @Test void hdrResolveGradingBloomAndScatteringProduceIsolatedOutput() {
        assumeTrue(Boolean.getBoolean("pvzce.testGraphics"));
        assertTrue(GLFW.glfwInit());
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        long window = GLFW.glfwCreateWindow(SIZE, SIZE, "Post process regression", 0, 0);
        assertNotEquals(0, window);
        GLFW.glfwMakeContextCurrent(window);
        GL.createCapabilities();
        try {
            RenderSystem.init();
            try (var water = new com.pvzce.client.renderer.liquid.LiquidShader()) { water.use(); }
            RenderSystem.setShader();
            config = PvzceClientConfig.load(directory);
            RenderSystem.shadows().configure(config);
            RenderSystem.shadows().render(camera, 4.5F, 15, 9, 5, () -> {});
            config.setEffectEnabled(SUN_SHAFTS, false);
            float neutral = frame(0.18F, 0.18F, 0.18F, false, 0)[0];
            assertTrue(neutral > 0.5F && neutral < 0.6F, "HDR color passes through ACES and gamma");
            config.setLighting(EXPOSURE, 1);
            assertTrue(frame(0.18F, 0.18F, 0.18F, false, 0)[0] > neutral + 0.1F);
            config.setLighting(EXPOSURE, 0);
            config.setEffectEnabled(MSAA, false);
            assertEquals(neutral, frame(0.18F, 0.18F, 0.18F, false, 0)[0], 0.005F);
            config.setEffectEnabled(ACES, false);
            config.setLighting(GAMMA, 1);
            config.setLighting(HUE, 120);
            float[] green = frame(0.8F, 0.1F, 0.1F, false, 0);
            assertTrue(green[1] > 0.75F && green[0] < 0.15F && green[2] < 0.15F, "HSL hue rotates red to green");
            config.setLighting(SATURATION, 0);
            float[] grey = frame(0.8F, 0.1F, 0.1F, false, 0);
            assertEquals(grey[0], grey[1], 0.005F);
            config.resetLighting();
            config.setEffectEnabled(SUN_SHAFTS, false);
            config.setEffectEnabled(BLOOM, false);
            frame(0.01F, 0.01F, 0.01F, true, 0);
            float dark = pixel(145, 128)[0];
            config.setEffectEnabled(BLOOM, true);
            frame(0.01F, 0.01F, 0.01F, true, 0);
            assertTrue(pixel(145, 128)[0] > dark + 0.02F, "Bloom extends outside the emissive patch");
            config.setEffectEnabled(SUN_SHAFTS, true);
            float day = frame(0.1F, 0.1F, 0.1F, false, 0)[0];
            float night = frame(0.1F, 0.1F, 0.1F, false, 1)[0];
            assertTrue(day > night + 0.01F, "Sun scattering is restricted to daytime");
            overhead = true;
            float blocked = frame(0.1F, 0.1F, 0.1F, false, 0)[0];
            assertTrue(day > blocked + 0.01F, "Light-space occluders must also block the scattering ray");
            overhead = false;
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor(0, 0, SIZE, SIZE);
            GL11.glDisable(GL13.GL_MULTISAMPLE);
            frame(0.1F, 0.1F, 0.1F, false, 1);
            assertTrue(GL11.glIsEnabled(GL11.GL_SCISSOR_TEST));
            assertTrue(GL11.glIsEnabled(GL11.GL_BLEND));
            assertFalse(GL11.glIsEnabled(GL13.GL_MULTISAMPLE));
            for (int targetSize : new int[]{192, 256}) {
                RenderSystem.post().begin(targetSize, targetSize, config, camera, 1);
                SpriteRenderer.solid(0, 0, 1, 1, 0, 0.1F, 0.1F, 0.1F, 1);
                RenderSystem.post().finish();
                int[] viewport = new int[4];
                GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
                assertArrayEquals(new int[]{0, 0, SIZE, SIZE}, viewport, "Resize restores the caller's viewport");
                assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
            }
            RenderSystem.setGuiShader();
            SpriteRenderer.solid(0, 0, 1, 1, 0, 1, 0, 0, 1);
            assertArrayEquals(new float[]{1, 0, 0}, pixel(128, 128), 0.005F, "GUI bypasses world grading");
        } finally {
            com.pvzce.client.renderer.vertex.Tesselator.INSTANCE.close();
            RenderSystem.post().close();
            RenderSystem.shadows().close();
            RenderSystem.shader().close();
            GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
        }
    }
}
