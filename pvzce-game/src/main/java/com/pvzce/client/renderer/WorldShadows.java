package com.pvzce.client.renderer;

import com.pvzce.common.PvzceConstants;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/** Light-space depth maps of the current animated billboards, shared by sprites and water. */
public final class WorldShadows implements AutoCloseable {
    public static final int LIGHTS = ShaderProgram.MAX_POINT_LIGHTS + 1;

    /** The same projective transform must be used by the depth and receiving passes. */
    public static final String GLSL = """
            uniform sampler2DArrayShadow uShadowMaps;
            uniform int uShadows;
            uniform int uShadowPass;
            uniform int uCasterEmission;
            uniform int uReceiverEntity;
            uniform float uReceiverGround;
            uniform vec3 uReceiverProfile;
            uniform vec2 uReceiverLimits;
            uniform vec4 uShadowBounds;
            uniform vec4 uShadowPointColor[8];
            uniform float uShadowPointRadius[8];
            uniform vec4 uShadowLights[9]; // xyz: direction or position; w: point light
            const float SHADOW_RANGE = %s;
            const float SHADOW_NEAR = %s;
            const float SHADOW_ALPHA_CUTOFF = %s;
            vec4 shadowClip(vec3 p, int index) {
                vec4 light = uShadowLights[index];
                float w = light.w > 0.5 ? light.z - p.z : light.z;
                vec2 projected = p.xy * light.z - light.xy * p.z;
                vec2 ndc = (projected - uShadowBounds.xy * w) / uShadowBounds.zw * 2.0 - w;
                float depth = light.w > 0.5 ? w - 2.0 * SHADOW_NEAR
                        : (1.0 - 2.0 * p.z / SHADOW_RANGE) * w;
                return vec4(ndc, depth, w);
            }
            vec3 shadowPosition(vec2 world) {
                if (uReceiverEntity == 1)
                    return vec3(world.x, uReceiverGround, max(0.0, world.y - uReceiverGround));
                if (uReceiverEntity == 2) {
                    float elevation = clamp((uReceiverProfile.x + uReceiverProfile.y * world.x
                            + uReceiverProfile.z * world.y) / (1.0 + uReceiverProfile.z),
                            uReceiverLimits.x, uReceiverLimits.y);
                    return vec3(world.x, world.y - elevation, elevation);
                }
                return vec3(world, 0.0);
            }
            """.formatted(PvzceConstants.SHADOW_HEIGHT_RANGE, PvzceConstants.SHADOW_NEAR, PvzceConstants.SHADOW_ALPHA_CUTOFF);

    public static final String FILTER_GLSL = """
            float lightVisibility(vec3 p, int index) {
                if (uShadows == 0) return 1.0;
                vec4 clip = shadowClip(p, index);
                if (clip.w <= SHADOW_NEAR) return 1.0;
                vec3 projected = clip.xyz / clip.w * 0.5 + 0.5;
                if (any(lessThan(projected.xy, vec2(0.0))) || any(greaterThan(projected.xy, vec2(1.0)))) return 1.0;
                // A world-height bias, converted to each projection's depth space.
                float bias = uShadowLights[index].w > 0.5
                        ? SHADOW_NEAR * %s / (clip.w * clip.w) : %s / SHADOW_RANGE;
                // Receiver-plane correction keeps the filter from shadowing the billboard itself.
                vec2 dx = dFdx(projected.xy), dy = dFdy(projected.xy);
                float determinant = dx.x * dy.y - dx.y * dy.x;
                vec2 gradient = abs(determinant) > 1e-10
                        ? vec2(dFdx(projected.z) * dy.y - dFdy(projected.z) * dx.y,
                               dFdy(projected.z) * dx.x - dFdx(projected.z) * dy.x) / determinant
                        : vec2(0.0);
                float visible = 0.0;
                vec2 texel = 1.0 / vec2(textureSize(uShadowMaps, 0).xy);
                // Hardware comparison also filters the four neighbours inside each tap.
                bias += dot(abs(gradient), texel);
                for (int y = -1; y <= 1; y++) {
                    for (int x = -1; x <= 1; x++) {
                        vec2 offset = vec2(x, y) * texel;
                        visible += texture(uShadowMaps, vec4(projected.xy + offset,
                                float(index), projected.z + dot(gradient, offset) - bias));
                    }
                }
                return visible / 9.0;
            }
            vec3 pointContribution(vec3 p, vec3 albedo) {
                if (uShadows == 0) return vec3(0.0);
                vec3 result = vec3(0.0);
                for (int i = 0; i < 8; i++) {
                    float radius = uShadowPointRadius[i];
                    float distanceToLight = distance(p.xy, uShadowLights[i + 1].xy);
                    if (radius > 0.0 && distanceToLight < radius) {
                        float falloff = 1.0 - smoothstep(0.0, radius, distanceToLight);
                        result += albedo * uShadowPointColor[i].rgb * uShadowPointColor[i].a
                                * falloff * lightVisibility(p, i + 1);
                    }
                }
                return result;
            }
            float directExposure(vec3 p) {
                return 1.0 - %s * (1.0 - lightVisibility(p, 0));
            }
            """.formatted(PvzceConstants.SHADOW_DEPTH_BIAS, PvzceConstants.SHADOW_DEPTH_BIAS,
                    PvzceConstants.SHADOW_DIRECT_SHARE);

    public static final class Uniforms {
        private final int enabled, pass, emission, entity, ground, profile, limits, bounds, sampler;
        private final int[] lights = new int[LIGHTS];
        private final int[] colors = new int[LIGHTS - 1], radii = new int[LIGHTS - 1];

        public Uniforms(int program) {
            enabled = GL20.glGetUniformLocation(program, "uShadows");
            pass = GL20.glGetUniformLocation(program, "uShadowPass");
            emission = GL20.glGetUniformLocation(program, "uCasterEmission");
            entity = GL20.glGetUniformLocation(program, "uReceiverEntity");
            ground = GL20.glGetUniformLocation(program, "uReceiverGround");
            profile = GL20.glGetUniformLocation(program, "uReceiverProfile");
            limits = GL20.glGetUniformLocation(program, "uReceiverLimits");
            bounds = GL20.glGetUniformLocation(program, "uShadowBounds");
            sampler = GL20.glGetUniformLocation(program, "uShadowMaps");
            for (int i = 0; i < LIGHTS; i++) lights[i] = GL20.glGetUniformLocation(program, "uShadowLights[" + i + "]");
            for (int i = 0; i < LIGHTS - 1; i++) {
                colors[i] = GL20.glGetUniformLocation(program, "uShadowPointColor[" + i + "]");
                radii[i] = GL20.glGetUniformLocation(program, "uShadowPointRadius[" + i + "]");
            }
        }

        public void receiver(boolean billboard, float baseline) {
            GL20.glUniform1i(entity, billboard ? 1 : 0);
            GL20.glUniform1f(ground, baseline);
        }

        public void surface(com.pvzce.api.content.SurfaceProfile surface) {
            GL20.glUniform1i(entity, 2);
            GL20.glUniform3f(profile, surface.elevation(), surface.slopeX(), surface.slopeY());
            GL20.glUniform2f(limits, surface.min(), surface.max());
        }

        public void disable() { GL20.glUniform1i(enabled, 0); }
    }

    private final float[][] lights = new float[LIGHTS][4];
    private final float[][] colors = new float[LIGHTS - 1][4];
    private final float[] radii = new float[LIGHTS - 1];
    private final boolean[] present = new boolean[LIGHTS];
    private int framebuffer, texture;
    private boolean active, casting;
    private float left, bottom, width, height;

    public boolean active() { return active; }

    public void reset() {
        active = false;
        java.util.Arrays.fill(present, false);
        RenderSystem.shader().shadows().disable();
    }

    public void pointLight(int index, float x, float y, float radius, float r, float g, float b, float strength) {
        colors[index] = new float[]{r, g, b, strength};
        radii[index] = radius;
        lights[index + 1] = new float[]{x, y, PvzceConstants.SHADOW_POINT_HEIGHT, 1F};
        present[index + 1] = true;
    }

    public void render(PvzceCamera camera, float sunX, float sunY, int columns, int rows,
                       Runnable casters) {
        create();
        left = camera.worldLeft() - PvzceConstants.SHADOW_MAP_MARGIN;
        bottom = camera.worldBottom() - PvzceConstants.SHADOW_MAP_MARGIN;
        width = camera.worldWidth() + PvzceConstants.SHADOW_MAP_MARGIN * 2F;
        height = camera.worldHeight() + PvzceConstants.SHADOW_MAP_MARGIN * 2F;
        lights[0] = new float[]{(sunX / columns - 0.5F) * 2F, PvzceConstants.SHADOW_SUN_DEPTH,
                1F + sunY / rows * PvzceConstants.SHADOW_SUN_ELEVATION_FACTOR, 0F};
        present[0] = true;
        int previousFramebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean depthWrite = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        int depthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        var uniforms = RenderSystem.shader().shadows();
        casting = true;
        try {
            GL13.glActiveTexture(GL13.GL_TEXTURE2);
            GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, 0);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(true);
            GL11.glDepthFunc(GL11.GL_LESS);
            GL11.glViewport(0, 0, PvzceConstants.SHADOW_MAP_SIZE, PvzceConstants.SHADOW_MAP_SIZE);
            upload(uniforms);
            GL20.glUniform1i(uniforms.emission, 0);
            for (int i = 0; i < LIGHTS; i++) {
                if (!present[i]) continue;
                GL30.glFramebufferTextureLayer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, texture, 0, i);
                GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
                GL20.glUniform1i(uniforms.pass, i + 1);
                casters.run();
            }
        } finally {
            casting = false;
            GL20.glUniform1i(uniforms.pass, 0);
            uniforms.receiver(false, 0F);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFramebuffer);
            GL11.glDepthMask(depthWrite);
            GL11.glDepthFunc(depthFunc);
            if (!depth) GL11.glDisable(GL11.GL_DEPTH_TEST);
            if (blend) GL11.glEnable(GL11.GL_BLEND);
            if (scissor) GL11.glEnable(GL11.GL_SCISSOR_TEST);
            RenderSystem.blendNormal();
            RenderSystem.viewport(camera.viewportX(), camera.viewportY(), camera.viewportWidth(), camera.viewportHeight());
        }
        active = true;
        GL13.glActiveTexture(GL13.GL_TEXTURE2);
        GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, texture);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        upload(uniforms);
    }

    public void emission(boolean additive) {
        if (casting) GL20.glUniform1i(RenderSystem.shader().shadows().emission, additive ? 1 : 0);
    }

    /** Caller has bound its own program. Unit 2 leaves the water's caustics on unit 1. */
    public void upload(Uniforms uniforms) {
        GL20.glUniform1i(uniforms.enabled, active ? 1 : 0);
        GL20.glUniform1i(uniforms.sampler, 2);
        GL20.glUniform4f(uniforms.bounds, left, bottom, width, height);
        for (int i = 0; i < LIGHTS; i++) {
            float[] light = lights[i];
            GL20.glUniform4f(uniforms.lights[i], light[0], light[1], light[2], light[3]);
        }
        for (int i = 0; i < LIGHTS - 1; i++) {
            float[] color = colors[i];
            GL20.glUniform4f(uniforms.colors[i], color[0], color[1], color[2], color[3]);
            GL20.glUniform1f(uniforms.radii[i], present[i + 1] ? radii[i] : 0F);
        }
    }

    private void create() {
        if (framebuffer != 0) return;
        framebuffer = GL30.glGenFramebuffers();
        texture = GL11.glGenTextures();
        GL13.glActiveTexture(GL13.GL_TEXTURE2);
        GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, texture);
        GL12.glTexImage3D(GL30.GL_TEXTURE_2D_ARRAY, 0, GL14.GL_DEPTH_COMPONENT24,
                PvzceConstants.SHADOW_MAP_SIZE, PvzceConstants.SHADOW_MAP_SIZE, LIGHTS,
                0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, (java.nio.ByteBuffer) null);
        GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL14.GL_TEXTURE_COMPARE_MODE, GL30.GL_COMPARE_REF_TO_TEXTURE);
        GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL14.GL_TEXTURE_COMPARE_FUNC, GL11.GL_LEQUAL);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        int previous = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
        GL11.glDrawBuffer(GL11.GL_NONE);
        GL11.glReadBuffer(GL11.GL_NONE);
        GL30.glFramebufferTextureLayer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, texture, 0, 0);
        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previous);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) throw new IllegalStateException("Shadow framebuffer: 0x" + Integer.toHexString(status));
    }

    @Override public void close() {
        if (framebuffer != 0) GL30.glDeleteFramebuffers(framebuffer);
        if (texture != 0) GL11.glDeleteTextures(texture);
        framebuffer = texture = 0;
        active = false;
    }
}
