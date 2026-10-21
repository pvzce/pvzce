package com.pvzce.client.renderer;

import com.pvzce.client.config.PvzceClientConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;
import java.nio.ByteBuffer;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Display-color regressions for neutral daylight and readable, directional moonlight. */
class WorldLightingTest {
    @TempDir Path directory;
    private static final int SIZE = 256;

    private static void uniform(String name, float value) {
        GL20.glUniform1f(GL20.glGetUniformLocation(RenderSystem.shader().id(), name), value);
    }
    private static void flag(String name, int value) {
        GL20.glUniform1i(GL20.glGetUniformLocation(RenderSystem.shader().id(), name), value);
    }
    private float[] frame(PvzceClientConfig config, float night, boolean entity, float value) {
        return frame(config, night, entity, value, 4.5F, 15F);
    }
    private float[] frame(PvzceClientConfig config, float night, boolean entity, float value, float lightX, float lightY) {
        var camera = new PvzceCamera(SIZE, SIZE, 9, 5);
        RenderSystem.setShader();
        RenderSystem.setGuiShader();
        RenderSystem.shadows().configure(config);
        RenderSystem.shadows().render(camera, lightX, lightY, 9, 5, () -> {});
        RenderSystem.viewport(0, 0, SIZE, SIZE);
        RenderSystem.setProjectionMatrix(Matrix4f.ortho(0, 1, 0, 1, -10, 10));
        RenderSystem.post().begin(SIZE, SIZE, config, camera, night);
        flag("uMaterialLighting", 1);
        flag("uRelief", 0);
        flag("uMaterial", 0);
        flag("uContactCount", 0);
        uniform("uSceneNight", night);
        RenderSystem.shader().lighting().celestial(TimeOfDayLighting.celestial(lightX, lightY, 9, 5, night),
                config.effectEnabled(PvzceClientConfig.ShaderEffect.DAY_NIGHT));
        uniform("uSpecularStrength", 1);
        RenderSystem.shader().shadows().receiver(entity, 0);
        RenderSystem.shader().lighting().uv(0, 0, 1, 1);
        SpriteRenderer.solid(0, 0, 1, 1, 0, value, value, value, 1);
        RenderSystem.post().finish();
        ByteBuffer pixel = ByteBuffer.allocateDirect(4);
        // Sample the curved sheet's right side so azimuth changes its incident light.
        GL11.glReadPixels(entity ? SIZE*3/4 : SIZE/2, SIZE/2, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixel);
        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
        return new float[]{(pixel.get(0)&255)/255F, (pixel.get(1)&255)/255F, (pixel.get(2)&255)/255F};
    }
    private static float luminance(float[] rgb) { return rgb[0]*0.2126F + rgb[1]*0.7152F + rgb[2]*0.0722F; }

    @Test void defaultsPreserveDayColorsAndKeepMoonlitEntitiesReadable() {
        assumeTrue(Boolean.getBoolean("pvzce.testGraphics"));
        assertTrue(GLFW.glfwInit());
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        long window = GLFW.glfwCreateWindow(SIZE, SIZE, "Day and moon lighting", 0, 0);
        assertNotEquals(0, window);
        GLFW.glfwMakeContextCurrent(window);
        GL.createCapabilities();
        try {
            RenderSystem.init();
            var config = PvzceClientConfig.load(directory);
            config.setShadowQuality(PvzceClientConfig.ShadowQuality.OFF);
            for (boolean entity : new boolean[]{false, true}) {
                float[] day = frame(config, 0, entity, 0.5F);
                float[] night = frame(config, 1, entity, 0.5F);
                float[] dark = frame(config, 1, entity, 0.2F);
                System.out.printf("%s day=%s night=%s dark=%s%n", entity ? "entity" : "ground",
                        java.util.Arrays.toString(day), java.util.Arrays.toString(night), java.util.Arrays.toString(dark));
                assertEquals(0.5F, luminance(day), 0.065F, "Daylight must preserve the painted middle grey");
                assertTrue(Math.abs(day[0]-day[2]) < 0.035F, "Neutral paint must not become yellow");
                assertTrue(luminance(night) > luminance(day)*0.7F, "Moonlit midtones remain legible");
                assertTrue(luminance(night) < luminance(day), "Night still reads darker than day");
                assertTrue(night[2] > night[0]+0.025F, "Moonlight has a cool tint");
                assertTrue(luminance(dark) > 0.13F, "Dark entity details must not be crushed to black");
                float[] sunrise = frame(config, 0, entity, 0.5F, 4.5F, 2.5F);
                System.out.printf("low sun=%s%n", java.util.Arrays.toString(sunrise));
                assertTrue(sunrise[0] > sunrise[2]+0.015F, "Low sunlight is warmer than noon");
                assertTrue(luminance(sunrise) < luminance(day)-0.03F, "Low sun is weaker and grazes the surface");
                config.setEffectEnabled(PvzceClientConfig.ShaderEffect.DAY_NIGHT, false);
                float[] untinted = frame(config, 0, entity, 0.5F, 4.5F, 2.5F);
                assertTrue(Math.abs(untinted[0]-untinted[2]) < 0.015F, "Disabling celestial tint also removes the dawn tint");
                config.setEffectEnabled(PvzceClientConfig.ShaderEffect.DAY_NIGHT, true);
                float[] moonrise = frame(config, 1, entity, 0.5F, 4.5F, 2.5F);
                assertTrue(luminance(moonrise) < luminance(night)-0.025F, "Moon altitude affects incident light too");
                if (entity) {
                    // Below the zenith, changing azimuth visibly lights the sheet's other side.
                    float[] moonFront = frame(config, 1, true, 0.5F, 0.45F, 6.5F);
                    float[] moonSide = frame(config, 1, true, 0.5F, 8.55F, 6.5F);
                    System.out.printf("moon azimuth front=%s side=%s%n", java.util.Arrays.toString(moonFront), java.util.Arrays.toString(moonSide));
                    assertTrue(Math.abs(luminance(moonFront)-luminance(moonSide)) > 0.025F,
                            "Moonlit sheets respond to azimuth");
                }
            }
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
