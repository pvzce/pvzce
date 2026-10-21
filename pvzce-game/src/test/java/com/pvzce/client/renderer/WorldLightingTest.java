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

    private float[] materialImage(PvzceClientConfig config, com.pvzce.client.renderer.texture.Texture texture,
                                  int material, boolean entity, boolean relief, float specular, float direct,
                                  int pointColor, int orientation, String capture) throws Exception {
        var camera = new PvzceCamera(SIZE, SIZE, 9, 5);
        RenderSystem.setShader();
        RenderSystem.setGuiShader();
        RenderSystem.shadows().reset();
        RenderSystem.shadows().configure(config);
        if (pointColor != 0) {
            RenderSystem.shadows().pointLight(0, 0.5F, 0.25F, 3F,
                    ((pointColor >> 16)&255)/255F, ((pointColor >> 8)&255)/255F, (pointColor&255)/255F, 1F);
            RenderSystem.shadows().pointHeight(0, 0.25F, 2F);
        }
        RenderSystem.shadows().render(camera, 4.5F, 10.5F, 9, 5, () -> {});
        RenderSystem.viewport(0, 0, SIZE, SIZE);
        RenderSystem.setProjectionMatrix(Matrix4f.ortho(0, 1, 0, 1, -10, 10));
        RenderSystem.post().begin(SIZE, SIZE, config, camera, 0);
        flag("uMaterialLighting", 1);
        flag("uMaterialTexture", 0);
        flag("uRelief", relief ? 1 : 0);
        flag("uMaterial", material);
        flag("uContactCount", 0);
        uniform("uSceneNight", 0);
        RenderSystem.shader().lighting().celestial(TimeOfDayLighting.celestial(4.5F, 10.5F, 9, 5, 0), true);
        uniform("uCelestialStrength", direct);
        uniform("uSpecularStrength", specular);
        RenderSystem.shader().shadows().receiver(entity, 0);
        if (texture == null) {
            RenderSystem.shader().lighting().uv(0, 0, 1, 1);
            SpriteRenderer.solid(0, 0, 1, 1, 0, 0.5F, 0.5F, 0.5F, 1);
        } else if (orientation == 1) {
            SpriteRenderer.texturedQuad(texture, 1, 0, 1, 1, 0, 1, 0, 0,
                    0, 0, 1, 0, 1, 1, 0, 1, 0, 1, 1, 1, 1);
        } else if (orientation == 2) {
            SpriteRenderer.texturedRegion(texture, 1, 0, 0, 1, 0, 0, 1, 1, 0, 1, 1, 1, 1);
        } else {
            SpriteRenderer.texturedRegion(texture, 0, 0, 1, 1, 0, 0, 1, 1, 0, 1, 1, 1, 1);
        }
        RenderSystem.post().finish();
        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
        String folder = System.getProperty("pvzce.materialCapture");
        if (folder != null && capture != null) {
            java.nio.file.Files.createDirectories(Path.of(folder));
            assertTrue(com.pvzce.client.FramebufferCapture.writePng(Path.of(folder).resolve(capture+".png").toString(), SIZE, SIZE));
        }
        ByteBuffer pixels = ByteBuffer.allocateDirect(SIZE*SIZE*4);
        GL11.glReadPixels(0, 0, SIZE, SIZE, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        float[] result = new float[SIZE*SIZE*3];
        for (int i=0; i<SIZE*SIZE; i++) for (int channel=0; channel<3; channel++)
            result[i*3+channel] = (pixels.get(i*4+channel)&255)/255F;
        return result;
    }

    private static float difference(float[] a, float[] b) {
        float sum = 0;
        for (int i=0; i<a.length; i++) sum += Math.abs(a[i]-b[i]);
        return sum/a.length;
    }

    private void normalAndReflectionRegressions(PvzceClientConfig config) throws Exception {
        config.setEffectEnabled(PvzceClientConfig.ShaderEffect.BLOOM, false);
        float[] grassFlat = materialImage(config, null, 1, false, false, 1, 0.78F, 0, 0, "grass-flat");
        float[] grassRelief = materialImage(config, null, 1, false, true, 1, 0.78F, 0, 0, "grass-normals");
        float[] grassDiffuse = materialImage(config, null, 1, false, false, 0, 0.78F, 0, 0, null);
        float[] stoneGloss = materialImage(config, null, 3, false, false, 1, 0.78F, 0, 0, "stone-reflection");
        float[] stoneDiffuse = materialImage(config, null, 3, false, false, 0, 0.78F, 0, 0, null);
        assertTrue(difference(grassFlat, grassRelief) > 0.01F, "Ground normals must visibly change lighting");
        assertTrue(difference(stoneGloss, stoneDiffuse) > difference(grassFlat, grassDiffuse)*1.5F,
                "Stone reflects more sharply than rough grass");
        int gl = GL11.glGenTextures();
        RenderSystem.bindTexture(gl);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        ByteBuffer data = ByteBuffer.allocateDirect(SIZE*SIZE*4);
        for (int y=0; y<SIZE; y++) for (int x=0; x<SIZE; x++) {
            float dx=(x+0.5F)/SIZE*2-1, dy=(y+0.5F)/SIZE*2-1;
            data.put((byte)128).put((byte)128).put((byte)128).put((byte)(dx*dx+dy*dy < 0.72F ? 255 : 0));
        }
        data.flip();
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, SIZE, SIZE, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, data);
        var texture = new com.pvzce.client.renderer.texture.Texture(
                com.pvzce.api.util.Identifier.withDefaultNamespace("normal-test"), gl, SIZE, SIZE);
        try {
            float[] sheet = materialImage(config, texture, 0, true, false, 1, 0.78F, 0, 0, "entity-old-normal");
            float[] rounded = materialImage(config, texture, 0, true, true, 1, 0.78F, 0, 0, "entity-normals");
            float[] turned = materialImage(config, texture, 0, true, true, 1, 0.78F, 0, 1, "entity-rotated");
            float[] mirrored = materialImage(config, texture, 0, true, true, 1, 0.78F, 0, 2, null);
            assertTrue(difference(rounded, mirrored) < 0.003F, "Mirroring a symmetric silhouette must preserve its world-space normals");
            assertTrue(difference(sheet, rounded) > 0.015F, "The entity silhouette must affect its surface normal");
            assertTrue(difference(rounded, turned) > 0.005F, "Normals must follow the animated part's axes");
            float[] environment = materialImage(config, texture, 0, true, true, 1, 0, 0, 0, "entity-environment");
            float[] matte = materialImage(config, texture, 0, true, true, 0, 0, 0, 0, "entity-matte");
            assertTrue(difference(environment, matte) > 0.005F, "Entities reflect the environment even without direct light");
            float[] warm = materialImage(config, texture, 0, true, true, 1, 0, 0xFF550F, 0, "entity-fire");
            float[] cool = materialImage(config, texture, 0, true, true, 1, 0, 0x2977FF, 0, "entity-blue-light");
            float[] warmMatte = materialImage(config, texture, 0, true, true, 0, 0, 0xFF550F, 0, null);
            float[] coolMatte = materialImage(config, texture, 0, true, true, 0, 0, 0x2977FF, 0, null);
            int center=(SIZE/2*SIZE+SIZE/2)*3;
            assertTrue(warm[center]-cool[center] > 0.02F && cool[center+2]-warm[center+2] > 0.02F,
                    "Reflected point light must retain its source color");
            assertTrue(warm[center]-warmMatte[center] > 0.01F && cool[center+2]-coolMatte[center+2] > 0.01F,
                    "Point lights must contribute specular reflection in addition to diffuse light");
            System.out.printf("normal difference: ground=%.4f entity=%.4f; environment=%.4f; gloss: grass=%.4f stone=%.4f%n",
                    difference(grassFlat, grassRelief), difference(sheet, rounded), difference(environment, matte),
                    difference(grassFlat, grassDiffuse), difference(stoneGloss, stoneDiffuse));
        } finally { GL11.glDeleteTextures(gl); }
    }

    @Test void defaultsPreserveDayColorsAndKeepMoonlitEntitiesReadable() throws Exception {
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
            normalAndReflectionRegressions(config);
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
