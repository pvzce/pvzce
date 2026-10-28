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
    private boolean overheadOccluder;

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
        return materialImage(config, texture, material, entity, relief, specular, direct, pointColor, orientation,
                capture, 0, 4.5F, 10.5F, false);
    }

    private float[] materialImage(PvzceClientConfig config, com.pvzce.client.renderer.texture.Texture texture,
                                  int material, boolean entity, boolean relief, float specular, float direct,
                                  int pointColor, int orientation, String capture,
                                  float night, float lightX, float lightY, boolean lawnPatch) throws Exception {
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
        RenderSystem.shadows().render(camera, lightX, lightY, 9, 5, () -> {
            if (overheadOccluder) {
                RenderSystem.shader().shadows().surface(com.pvzce.api.content.SurfaceProfile.flat(3));
                SpriteRenderer.solid(camera.worldLeft()-20, camera.worldBottom()-20+3,
                        camera.worldWidth()+40, camera.worldHeight()+40, 0, 1, 1, 1, 1);
            }
        });
        RenderSystem.viewport(0, 0, SIZE, SIZE);
        RenderSystem.setProjectionMatrix(Matrix4f.ortho(0, 1, 0, 1, -10, 10));
        RenderSystem.post().begin(SIZE, SIZE, config, camera, night);
        flag("uMaterialLighting", 1);
        flag("uMaterialTexture", 0);
        flag("uRelief", relief ? 1 : 0);
        flag("uMaterial", material);
        flag("uContactCount", 0);
        uniform("uSceneNight", night);
        RenderSystem.shader().lighting().celestial(TimeOfDayLighting.celestial(lightX, lightY, 9, 5, night), true);
        uniform("uCelestialStrength", direct);
        uniform("uSpecularStrength", specular);
        RenderSystem.shader().shadows().receiver(entity, 0);
        if (texture == null) {
            RenderSystem.shader().lighting().uv(0, 0, 1, 1);
            SpriteRenderer.solid(0, 0, 1, 1, 0, 0.5F, 0.5F, 0.5F, 1);
        } else if (lawnPatch) {
            SpriteRenderer.texturedRegion(texture, 0.25F, 0.25F, 0.62F, 0.75F, 0, 0, 1, 1, 0, 1, 1, 1, 1);
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

    private static float[] mean(float[] pixels) {
        float[] sum = new float[3];
        for (int i=0; i<pixels.length; i++) sum[i%3] += pixels[i]/(pixels.length/3);
        return sum;
    }

    private static float[] meanLinear(float[] pixels) {
        float[] sum = new float[3];
        for (int i=0; i<pixels.length; i++) sum[i%3] += (float)Math.pow(pixels[i], 2.2)/(pixels.length/3);
        return sum;
    }

    private void paintedDayAndNight(PvzceClientConfig config) throws Exception {
        try (var resources = new com.pvzce.common.resource.PvzceResourceManager(Thread.currentThread().getContextClassLoader());
             var textures = new com.pvzce.client.renderer.texture.TextureManager(resources)) {
            resources.init(directory);
            var dayArt = textures.getOrLoad(com.pvzce.api.util.Identifier.withDefaultNamespace("textures/gui/screen/level/background1"));
            var nightArt = textures.getOrLoad(com.pvzce.api.util.Identifier.withDefaultNamespace("textures/gui/screen/level/background2"));
            var leaf = textures.getOrLoad(com.pvzce.api.util.Identifier.withDefaultNamespace("textures/entities/plant/producer/sunflower/peashooter_frontleaf"));
            for (var texture : java.util.List.of(dayArt, nightArt, leaf)) {
                boolean entity = texture == leaf;
                float[][] images = new float[4][];
                int index = 0;
                for (int tick : new int[]{240, 1800, 3360, 5400}) {
                    var time = TimeOfDayLighting.compute(tick, 3600, 3600, 9, 5);
                    var light = TimeOfDayLighting.celestial(time.sunX(), time.sunY(), 9, 5, time.nightBlend());
                    String phase = new String[]{"dawn", "noon", "dusk", "moon"}[index];
                    images[index++] = materialImage(config, texture, 1, entity, true, 1, light.strength(), 0, 0,
                            texture.id().path().substring(texture.id().path().lastIndexOf('/')+1)+"-"+phase,
                            time.nightBlend(), time.sunX(), time.sunY(), !entity);
                }
                var time = TimeOfDayLighting.compute(5400, 3600, 3600, 9, 5);
                var moon = TimeOfDayLighting.celestial(time.sunX(), time.sunY(), 9, 5, 1);
                float[] matte = materialImage(config, texture, 1, entity, true, 0, moon.strength(), 0, 0, null,
                        1, time.sunX(), time.sunY(), !entity);
                float[] noon = mean(images[1]), night = mean(images[3]);
                System.out.printf("paint %s noon=%s moon=%s reflected=%.4f%n", texture.id(),
                        java.util.Arrays.toString(noon), java.util.Arrays.toString(night), difference(images[3], matte));
                if (!entity) {
                    assertTrue(difference(images[3], matte) > 0.025F, "Painted ground must show a visible moon reflection");
                    float[] reflectedLinear = meanLinear(images[3]), unreflected = meanLinear(matte);
                    assertTrue(reflectedLinear[2]-unreflected[2] > (reflectedLinear[0]-unreflected[0])*1.25F,
                            "The moon reflection must visibly shift the paint toward blue");
                    if (texture == nightArt) assertTrue(night[2] > night[0]+0.08F, "The night lawn must read as cool moonlight");
                    if (texture == dayArt) assertTrue(luminance(noon) > 0.50F, "The painted daytime lawn must remain bright");
                    if (texture == dayArt) {
                        float[] morning = mean(images[0]), evening = mean(images[2]);
                        assertTrue(morning[0]/morning[1] > noon[0]/noon[1]+0.08F
                                && evening[0]/evening[1] > noon[0]/noon[1]+0.08F,
                                "Golden hours must add visible warmth rather than only darkening the lawn");
                    }
                    assertTrue(luminance(night) > 0.25F, "Already dark night artwork must retain readable light and color");
                    if (texture == nightArt) {
                        config.setShadowQuality(PvzceClientConfig.ShadowQuality.MEDIUM);
                        overheadOccluder = true;
                        float[] shaded = materialImage(config, texture, 1, false, true, 1, moon.strength(), 0, 0, "moon-in-shadow",
                                1, time.sunX(), time.sunY(), true);
                        float[] shadedMatte = materialImage(config, texture, 1, false, true, 0, moon.strength(), 0, 0, null,
                                1, time.sunX(), time.sunY(), true);
                        float directionalReflection = difference(images[3], matte)-difference(shaded, shadedMatte);
                        System.out.printf("moon reflection removed by occluder=%.4f%n", directionalReflection);
                        assertTrue(directionalReflection > 0.025F,
                                "An occluder removes the visible directional moon reflection while sky reflection remains");
                        float[] shadow = mean(shaded);
                        assertTrue(luminance(shadow) > 0.12F && shadow[2] > shadow[0]+0.03F,
                                "Shadows retain a readable cool sky fill");
                        overheadOccluder = false;
                        config.setShadowQuality(PvzceClientConfig.ShadowQuality.OFF);
                    }
                }
                if (texture != nightArt) assertTrue(difference(images[0], images[1]) > 0.04F && difference(images[2], images[1]) > 0.04F,
                        "Morning and evening must visibly differ from noon on real artwork");
            }
        } finally { RenderSystem.bindTexture(0); }
    }

    private float[] stageFrame(com.pvzce.client.PvzceClient client, com.pvzce.client.renderer.texture.Texture texture,
                               PvzceCamera camera, int tick, boolean overlay, String capture) throws Exception {
        client.level().setTimeOfDay(new com.pvzce.common.network.packet.TimeOfDayS2C(tick, 3600, 3600));
        RenderSystem.setShader();
        RenderSystem.setGuiShader();
        client.beginWorldView(camera);
        RenderSystem.shadows().render(camera, client.worldLightX(), client.worldLightY(), 9, 5, () -> {});
        RenderSystem.post().begin(960, 540, client.config(), camera, client.worldNightBlend());
        RenderSystem.lighting().begin(client);
        SpriteRenderer.texturedRegion(texture, 0, 0, 1, 1, camera.worldLeft(), camera.worldBottom(),
                camera.worldWidth(), camera.worldHeight(), 0, 1, 1, 1, 1);
        if (overlay) SceneTileRenderer.renderBackdropLighting(client, camera, texture.id());
        assertEquals(ShaderProgram.TerrainBlend.NONE, RenderSystem.shader().terrainBlend(), "Backdrop feathering must not leak into entities");
        RenderSystem.post().finish();
        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
        String folder = System.getProperty("pvzce.materialCapture");
        if (folder != null && capture != null)
            assertTrue(com.pvzce.client.FramebufferCapture.writePng(Path.of(folder).resolve(capture+".png").toString(), 960, 540));
        ByteBuffer data = ByteBuffer.allocateDirect(960*540*4);
        GL11.glReadPixels(0, 0, 960, 540, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, data);
        float[] result = new float[960*540*3];
        for (int j=0; j<960*540; j++) for (int c=0; c<3; c++) result[j*3+c]=(data.get(j*4+c)&255)/255F;
        return result;
    }

    private static float stageDifferenceAt(float[] a, float[] b, PvzceCamera camera, float x, float y) {
        int px=Math.round(camera.screenX(x)), py=Math.round(camera.screenY(y)), offset=(py*960+px)*3;
        return (Math.abs(a[offset]-b[offset])+Math.abs(a[offset+1]-b[offset+1])+Math.abs(a[offset+2]-b[offset+2]))/3;
    }

    private void backdropLightingJoinsItsPaintedFringe() throws Exception {
        com.pvzce.common.tag.TestContent.loadBuiltInContentAndTags();
        int target=GL30.glGenFramebuffers(), color=GL11.glGenTextures();
        RenderSystem.bindTexture(color);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 960, 540, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer)null);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, target);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, color, 0);
        assertEquals(GL30.GL_FRAMEBUFFER_COMPLETE, GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER));
        try (var harness=com.pvzce.testutil.ClientHarness.create("celestial-fringe");
             var resources=new com.pvzce.common.resource.PvzceResourceManager(Thread.currentThread().getContextClassLoader());
             var textures=new com.pvzce.client.renderer.texture.TextureManager(resources)) {
            resources.init(directory);
            var client=harness.client();
            var field=client.getClass().getDeclaredField("textures");
            field.setAccessible(true);
            field.set(client,textures);
            client.config().setShadowQuality(PvzceClientConfig.ShadowQuality.OFF);
            client.config().setEffectEnabled(PvzceClientConfig.ShaderEffect.BLOOM,false);
            var cells=new java.util.ArrayList<com.pvzce.common.network.packet.SceneSyncS2C.Cell>();
            for (int y=0;y<5;y++) for(int x=0;x<9;x++) cells.add(new com.pvzce.common.network.packet.SceneSyncS2C.Cell(x,y,"pvzce:grass"));
            var camera=new PvzceCamera(960,540,9,5);
            for (int background : new int[]{1,2}) {
                var id=com.pvzce.api.util.Identifier.withDefaultNamespace("textures/gui/screen/level/background"+background);
                var texture=textures.getOrLoad(id);
                client.level().init("pvzce:test",9,5,java.util.List.of(),java.util.List.of(),java.util.List.of(),0,java.util.List.of(),
                        cells,"pvzce:plant_team","植物方",java.util.List.of(),id,java.util.List.of("pvzce:grass"),false);
                if (background==1) {
                    stageFrame(client,texture,camera,240,true,"stage-dawn");
                    stageFrame(client,texture,camera,1800,true,"stage-noon");
                    stageFrame(client,texture,camera,3360,true,"stage-dusk");
                    stageFrame(client,texture,camera,5400,true,"stage-cycle-moon");
                }
                float[] bare=stageFrame(client,texture,camera,5400,false,null);
                float[] lit=stageFrame(client,texture,camera,5400,true,"stage-moon-"+background);
                assertTrue(stageDifferenceAt(bare,lit,camera,4.5F,0.02F)<0.01F,
                        "Lighting must fade at the lower lawn fringe instead of exposing a rectangle");
                assertTrue(stageDifferenceAt(bare,lit,camera,0.02F,2.5F)<0.01F,
                        "Lighting must also fade at the side fringe");
                assertTrue(stageDifferenceAt(bare,lit,camera,4.5F,2.5F)>0.025F,
                        "Feathering must retain reflected moonlight inside the board");
            }
        } finally {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,0);
            GL30.glDeleteFramebuffers(target);
            GL11.glDeleteTextures(color);
            RenderSystem.bindTexture(0);
        }
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
            paintedDayAndNight(config);
            backdropLightingJoinsItsPaintedFringe();
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
