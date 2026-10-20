package com.pvzce.client.renderer;

import com.pvzce.common.PvzceConstants;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/** World-only linear HDR target, quarter-resolution bloom and display transform. */
public final class WorldPostProcess implements AutoCloseable {
    private static final String VERTEX = """
            #version 150 core
            out vec2 uv;
            void main() {
                vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
                uv = p;
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
            """;
    private static final String FRAGMENT = """
            #version 150 core
            uniform sampler2D scene;
            uniform sampler2D glow;
            uniform int mode;
            uniform vec2 direction;
            uniform float bloom;
            in vec2 uv;
            out vec4 color;
            void main() {
                vec3 c = texture(scene, uv).rgb;
                if (mode == 0) {
                    // Downsample before filtering, with a soft brightness threshold.
                    vec2 pixel = 1.0 / vec2(textureSize(scene, 0));
                    c = vec3(0.0);
                    for (int x = -1; x <= 2; x++) for (int y = -1; y <= 2; y++) {
                        vec3 s = texture(scene, uv + vec2(x, y) * pixel).rgb;
                        float peak = max(s.r, max(s.g, s.b));
                        c += s * max(peak - %s, 0.0) / max(peak, 0.001) / 16.0;
                    }
                } else if (mode == 1) {
                    c *= 0.227027;
                    c += texture(scene, uv + direction * 1.384615).rgb * 0.316216;
                    c += texture(scene, uv - direction * 1.384615).rgb * 0.316216;
                    c += texture(scene, uv + direction * 3.230769).rgb * 0.070270;
                    c += texture(scene, uv - direction * 3.230769).rgb * 0.070270;
                } else {
                    c = (c + texture(glow, uv).rgb * bloom) * %s;
                    c = clamp((c * (2.51 * c + 0.03)) / (c * (2.43 * c + 0.59) + 0.14), 0.0, 1.0);
                    c = pow(c, vec3(1.0 / 2.2));
                }
                color = vec4(c, 1.0);
            }
            """.formatted(PvzceConstants.LIGHT_BLOOM_THRESHOLD, PvzceConstants.LIGHT_EXPOSURE);
    private final int[] textures = new int[3], targets = new int[3];
    private int width, height, program, vao, previousTarget;
    private int modeLocation, directionLocation, bloomLocation;
    private int[] viewport;
    private boolean active, bloom;

    public void begin(int width, int height, boolean bloom) {
        previousTarget = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        ensureSize(width, height);
        this.bloom = bloom;
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, targets[0]);
        boolean clipped = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        RenderSystem.clear(0.006F, 0.01F, 0.014F, 1F);
        if (clipped) GL11.glEnable(GL11.GL_SCISSOR_TEST);
        active = true;
    }

    public void finish() {
        if (!active) return;
        active = false;
        RenderSystem.lighting().end();
        boolean clipped = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        boolean blended = GL11.glIsEnabled(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glDisable(GL11.GL_BLEND);
        int oldVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        GL30.glBindVertexArray(vao);
        GL20.glUseProgram(program);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        if (bloom) {
            GL11.glViewport(0, 0, Math.max(1, width / 4), Math.max(1, height / 4));
            draw(1, textures[0], 0, 0F, 0F);
            for (int i = 0; i < 3; i++) {
                draw(2, textures[1], 1, 4F / width, 0F);
                draw(1, textures[2], 1, 0F, 4F / height);
            }
        }
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousTarget);
        GL11.glViewport(0, 0, width, height);
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textures[1]);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textures[0]);
        GL20.glUniform1i(modeLocation, 2);
        GL20.glUniform1f(bloomLocation, bloom ? PvzceConstants.LIGHT_BLOOM_STRENGTH : 0F);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        RenderSystem.noteTextureBound(0);
        GL30.glBindVertexArray(oldVao);
        RenderSystem.setShader();
        GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        if (clipped) GL11.glEnable(GL11.GL_SCISSOR_TEST);
        if (blended) GL11.glEnable(GL11.GL_BLEND);
    }

    private void draw(int target, int texture, int mode, float dx, float dy) {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, targets[target]);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL20.glUniform1i(modeLocation, mode);
        GL20.glUniform2f(directionLocation, dx, dy);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
    }

    private void ensureSize(int width, int height) {
        if (program == 0) {
            int vertex = compile(GL20.GL_VERTEX_SHADER, VERTEX), fragment = compile(GL20.GL_FRAGMENT_SHADER, FRAGMENT);
            program = GL20.glCreateProgram();
            GL20.glAttachShader(program, vertex);
            GL20.glAttachShader(program, fragment);
            GL20.glLinkProgram(program);
            if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == 0)
                throw new IllegalStateException("HDR link: " + GL20.glGetProgramInfoLog(program));
            GL20.glDeleteShader(vertex);
            GL20.glDeleteShader(fragment);
            vao = GL30.glGenVertexArrays();
            GL20.glUseProgram(program);
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "scene"), 0);
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "glow"), 1);
            modeLocation = GL20.glGetUniformLocation(program, "mode");
            directionLocation = GL20.glGetUniformLocation(program, "direction");
            bloomLocation = GL20.glGetUniformLocation(program, "bloom");
            RenderSystem.setShader();
        }
        if (this.width == width && this.height == height) return;
        this.width = width;
        this.height = height;
        for (int i = 0; i < 3; i++) {
            if (textures[i] == 0) { textures[i] = GL11.glGenTextures(); targets[i] = GL30.glGenFramebuffers(); }
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, textures[i]);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F,
                    i == 0 ? width : Math.max(1, width / 4), i == 0 ? height : Math.max(1, height / 4),
                    0, GL11.GL_RGBA, GL11.GL_FLOAT, (java.nio.ByteBuffer) null);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL13.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL13.GL_CLAMP_TO_EDGE);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, targets[i]);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, textures[i], 0);
            if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE)
                throw new IllegalStateException("Incomplete HDR target " + i);
        }
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        RenderSystem.noteTextureBound(0);
    }

    private static int compile(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0)
            throw new IllegalStateException("HDR shader: " + GL20.glGetShaderInfoLog(shader));
        return shader;
    }

    @Override public void close() {
        for (int i = 0; i < 3; i++) {
            GL11.glDeleteTextures(textures[i]); GL30.glDeleteFramebuffers(targets[i]);
            textures[i] = 0; targets[i] = 0;
        }
        GL20.glDeleteProgram(program); GL30.glDeleteVertexArrays(vao);
        program = vao = width = height = 0;
    }
}
