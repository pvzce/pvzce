package com.pvzce.client.renderer;

import com.pvzce.common.PvzceConstants;
import com.pvzce.client.config.PvzceClientConfig;
import static com.pvzce.client.config.PvzceClientConfig.LightingParameter.*;
import static com.pvzce.client.config.PvzceClientConfig.ShaderEffect.*;
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
            """ + WorldShadows.GLSL + """
            uniform sampler2D scene;
            uniform sampler2D glow;
            uniform int mode;
            uniform vec2 direction;
            uniform float bloom;
            uniform float threshold;
            uniform vec4 tone; // exposure EV, brightness, gamma, contrast
            uniform vec4 grading; // vibrance, saturation, highlights, shadows
            uniform vec3 hsl;
            uniform int aces;
            uniform sampler2D shafts;
            uniform vec4 stage; // normalized screen rectangle
            uniform vec4 worldRect;
            uniform float shaftStrength;
            uniform vec3 shaftColor;
            uniform float day;
            vec3 rgbToHsl(vec3 c) {
                float hi = max(c.r, max(c.g, c.b)), lo = min(c.r, min(c.g, c.b));
                float d = hi - lo, l = (hi + lo) * 0.5;
                if (d < 0.00001) return vec3(0.0, 0.0, l);
                float h = hi == c.r ? (c.g-c.b)/d : hi == c.g ? (c.b-c.r)/d+2.0 : (c.r-c.g)/d+4.0;
                return vec3(fract(h/6.0), d / max(0.00001, 1.0-abs(2.0*l-1.0)), l);
            }
            vec3 hslToRgb(vec3 v) {
                vec3 rgb = clamp(abs(mod(v.x * 6.0 + vec3(0,4,2), 6.0)-3.0)-1.0, 0.0, 1.0);
                return v.z + (rgb-0.5) * (1.0-abs(2.0*v.z-1.0)) * v.y;
            }
            vec3 acesFit(vec3 c) {
                return clamp((c * (2.51 * c + 0.03)) / (c * (2.43 * c + 0.59) + 0.14), 0.0, 1.0);
            }
            vec3 displayTone(vec3 c) {
                // Painted assets already carry their midtone contrast. Compress only the HDR shoulder.
                const float pivot = %s;
                vec3 anchor = acesFit(vec3(pivot));
                vec3 shoulder = pivot + (acesFit(c) - anchor) * (1.0 - pivot) / (1.0 - anchor);
                return mix(c, shoulder, greaterThan(c, vec3(pivot)));
            }
            float scatter(vec2 screenUv) {
                vec2 q = (screenUv-stage.xy)/stage.zw;
                if (any(lessThan(q, vec2(0))) || any(greaterThan(q, vec2(1))) || uShadows == 0) return 0.0;
                vec2 ground = worldRect.xy + q * worldRect.zw;
                float sum = 0.0, leastVisible = 1.0;
                // Along the side-view ray: projected Y = world Y + height.
                for (int i=0; i<%d; i++) {
                    float z = (float(i)+0.5)/float(%d) * %s;
                    vec4 clip = shadowClip(vec3(ground.x, ground.y-z, z), 0);
                    vec3 p = clip.xyz/clip.w * 0.5+0.5;
                    float visible = texture(uShadowMaps, vec4(p.xy, 0.0, p.z-0.0001));
                    leastVisible = min(leastVisible, visible);
                    float density = 0.7 + 0.3*sin(ground.x*2.1+ground.y*1.7+z*2.4);
                    sum += visible * exp(-z*0.65) * density;
                }
                // A fully open ray has no shaft boundary: avoid a constant veil over the whole scene.
                return sum / float(%d) * %s * day * (1.0 - leastVisible);
            }
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
                        c += s * max(peak - threshold, 0.0) / max(peak, 0.001) / 16.0;
                    }
                } else if (mode == 1) {
                    c *= 0.227027;
                    c += texture(scene, uv + direction * 1.384615).rgb * 0.316216;
                    c += texture(scene, uv - direction * 1.384615).rgb * 0.316216;
                    c += texture(scene, uv + direction * 3.230769).rgb * 0.070270;
                    c += texture(scene, uv - direction * 3.230769).rgb * 0.070270;
                } else if (mode == 3) {
                    c = vec3(scatter(uv));
                } else {
                    if (bloom > 0.0) c += texture(glow, uv).rgb * bloom;
                    if (shaftStrength > 0.0) c += texture(shafts, uv).rgb * shaftStrength * shaftColor;
                    c = max(c * exp2(tone.x), vec3(0.0));
                    if (aces == 1)
                        c = displayTone(c);
                    c = pow(clamp(c, 0.0, 1.0), vec3(1.0 / tone.z));
                    float luma = dot(c, vec3(0.2126, 0.7152, 0.0722));
                    c += grading.w * 0.25 * pow(1.0-luma, 2.0)
                       + grading.z * 0.25 * luma*luma;
                    c = (c-0.5)*tone.w + 0.5 + tone.y;
                    luma = dot(c, vec3(0.2126, 0.7152, 0.0722));
                    float chroma = max(c.r,max(c.g,c.b))-min(c.r,min(c.g,c.b));
                    c = mix(vec3(luma), c, max(0.0, grading.y*(1.0+grading.x*(1.0-clamp(chroma,0.0,1.0)))));
                    vec3 v = rgbToHsl(clamp(c,0.0,1.0));
                    v.x = fract(v.x + hsl.x / 360.0);
                    v.yz = clamp(v.yz + hsl.yz, 0.0, 1.0);
                    c = hslToRgb(v);
                }
                color = vec4(c, 1.0);
            }
            """.formatted(PvzceConstants.LIGHT_TONEMAP_SHOULDER,
                    PvzceConstants.LIGHT_SHAFT_STEPS, PvzceConstants.LIGHT_SHAFT_STEPS,
                    PvzceConstants.LIGHT_SHAFT_HEIGHT, PvzceConstants.LIGHT_SHAFT_STEPS,
                    PvzceConstants.LIGHT_SHAFT_DENSITY);
    private final int[] textures = new int[4], targets = new int[4];
    private int width, height, program, vao, previousTarget, previousReadTarget;
    private int multisampleTarget, multisampleColor, samples = 1;
    private boolean previousMultisample;
    private WorldShadows.Uniforms shadowUniforms;
    private PvzceClientConfig config;
    private PvzceCamera camera;
    private float daylight;
    private int modeLocation, directionLocation, bloomLocation;
    private int thresholdLocation, shaftLocation, shaftColorLocation, dayLocation, stageLocation, worldLocation;
    private int toneLocation, gradingLocation, hslLocation, acesLocation;
    private int[] viewport;
    private boolean active, bloom;

    public void begin(int width, int height, PvzceClientConfig config, PvzceCamera camera, float night) {
        this.config = config;
        this.camera = camera;
        daylight = 1F - night;
        previousTarget = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        previousReadTarget = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        int requested = config.effectEnabled(MSAA) ? Math.min(PvzceConstants.LIGHT_MSAA_SAMPLES,
                GL11.glGetInteger(GL30.GL_MAX_SAMPLES)) : 1;
        ensureSize(width, height, Math.max(1, requested));
        this.bloom = config.effectEnabled(BLOOM);
        previousMultisample = GL11.glIsEnabled(org.lwjgl.opengl.GL13.GL_MULTISAMPLE);
        if (samples > 1) GL11.glEnable(org.lwjgl.opengl.GL13.GL_MULTISAMPLE);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, samples > 1 ? multisampleTarget : targets[0]);
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
        if (samples > 1) {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, multisampleTarget);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, targets[0]);
            GL30.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        }
        int oldVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        GL30.glBindVertexArray(vao);
        GL20.glUseProgram(program);
        GL20.glUniform1f(thresholdLocation, config.lighting(BLOOM_THRESHOLD));
        boolean scattering = config.effectEnabled(SUN_SHAFTS) && daylight > 0F && RenderSystem.shadows().active();
        float shaftGain = 0F;
        if (scattering) {
            var sun = RenderSystem.shadows().sunlight();
            shaftGain = config.lighting(SHAFT_STRENGTH) * sun.strength() / PvzceConstants.LIGHT_DAY_DIRECT;
            GL20.glUniform3f(shaftColorLocation, sun.red(), sun.green(), sun.blue());
        }
        GL20.glUniform1f(shaftLocation, shaftGain);
        GL20.glUniform1f(dayLocation, daylight);
        GL20.glUniform4f(stageLocation, camera.viewportX()/(float)width,
                camera.viewportY()/(float)height, camera.viewportWidth()/(float)width, camera.viewportHeight()/(float)height);
        GL20.glUniform4f(worldLocation, camera.worldLeft(), camera.worldBottom(), camera.worldWidth(), camera.worldHeight());
        GL20.glUniform4f(toneLocation, config.lighting(EXPOSURE), config.lighting(BRIGHTNESS), config.lighting(GAMMA), config.lighting(CONTRAST));
        GL20.glUniform4f(gradingLocation, config.lighting(VIBRANCE), config.lighting(SATURATION), config.lighting(HIGHLIGHTS), config.lighting(SHADOWS));
        GL20.glUniform3f(hslLocation, config.lighting(HUE), config.lighting(HSL_SATURATION), config.lighting(LIGHTNESS));
        GL20.glUniform1i(acesLocation, config.effectEnabled(ACES) ? 1 : 0);
        RenderSystem.shadows().upload(shadowUniforms);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glViewport(0, 0, Math.max(1, width / 4), Math.max(1, height / 4));
        if (scattering) draw(3, textures[0], 3, 0F, 0F);
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
        GL13.glActiveTexture(GL13.GL_TEXTURE3);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textures[3]);
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textures[1]);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textures[0]);
        GL20.glUniform1i(modeLocation, 2);
        GL20.glUniform1f(bloomLocation, bloom ? config.lighting(BLOOM_STRENGTH) : 0F);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        GL13.glActiveTexture(GL13.GL_TEXTURE3);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        RenderSystem.noteTextureBound(0);
        GL30.glBindVertexArray(oldVao);
        RenderSystem.setShader();
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousReadTarget);
        GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        if (clipped) GL11.glEnable(GL11.GL_SCISSOR_TEST);
        if (blended) GL11.glEnable(GL11.GL_BLEND);
        if (!previousMultisample) GL11.glDisable(GL13.GL_MULTISAMPLE);
    }

    private void draw(int target, int texture, int mode, float dx, float dy) {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, targets[target]);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL20.glUniform1i(modeLocation, mode);
        GL20.glUniform2f(directionLocation, dx, dy);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
    }

    private void ensureSize(int width, int height, int samples) {
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
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "shafts"), 3);
            shadowUniforms = new WorldShadows.Uniforms(program);
            modeLocation = GL20.glGetUniformLocation(program, "mode");
            directionLocation = GL20.glGetUniformLocation(program, "direction");
            bloomLocation = GL20.glGetUniformLocation(program, "bloom");
            thresholdLocation = GL20.glGetUniformLocation(program, "threshold");
            shaftLocation = GL20.glGetUniformLocation(program, "shaftStrength");
            shaftColorLocation = GL20.glGetUniformLocation(program, "shaftColor");
            dayLocation = GL20.glGetUniformLocation(program, "day");
            stageLocation = GL20.glGetUniformLocation(program, "stage");
            worldLocation = GL20.glGetUniformLocation(program, "worldRect");
            toneLocation = GL20.glGetUniformLocation(program, "tone");
            gradingLocation = GL20.glGetUniformLocation(program, "grading");
            hslLocation = GL20.glGetUniformLocation(program, "hsl");
            acesLocation = GL20.glGetUniformLocation(program, "aces");
            RenderSystem.setShader();
        }
        if (this.width == width && this.height == height && this.samples == samples) return;
        this.samples = samples;
        this.width = width;
        this.height = height;
        for (int i = 0; i < 4; i++) {
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
        if (samples > 1) {
            if (multisampleTarget == 0) {
                multisampleTarget = GL30.glGenFramebuffers();
                multisampleColor = GL30.glGenRenderbuffers();
            }
            int oldRenderbuffer = GL11.glGetInteger(GL30.GL_RENDERBUFFER_BINDING);
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, multisampleColor);
            GL30.glRenderbufferStorageMultisample(GL30.GL_RENDERBUFFER, samples, GL30.GL_RGBA16F, width, height);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, multisampleTarget);
            GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL30.GL_RENDERBUFFER, multisampleColor);
            if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE)
                throw new IllegalStateException("Incomplete multisample HDR target");
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, oldRenderbuffer);
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
        for (int i = 0; i < 4; i++) {
            GL11.glDeleteTextures(textures[i]); GL30.glDeleteFramebuffers(targets[i]);
            textures[i] = 0; targets[i] = 0;
        }
        GL30.glDeleteFramebuffers(multisampleTarget);
        GL30.glDeleteRenderbuffers(multisampleColor);
        multisampleTarget = multisampleColor = 0;
        GL20.glDeleteProgram(program); GL30.glDeleteVertexArrays(vao);
        program = vao = width = height = 0;
    }
}
