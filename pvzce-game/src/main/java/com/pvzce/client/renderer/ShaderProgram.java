package com.pvzce.client.renderer;

import org.lwjgl.opengl.GL20;

import java.io.Closeable;

/**
 * A compiled GLSL program (position/color/uv sprite pipeline with time-of-day
 * tint plus optional world-space point lights, e.g. sun drops).
 */
public final class ShaderProgram implements Closeable {
    public static final int MAX_POINT_LIGHTS = 8;

    public static final String VERTEX = """
            #version 150 core
            in vec3 aPos;
            in vec4 aColor;
            in vec2 aUV;
            uniform mat4 uProj;
            out vec4 vColor;
            out vec2 vUV;
            out vec2 vWorld;
            void main() {
                gl_Position = uProj * vec4(aPos, 1.0);
                vColor = aColor;
                vUV = aUV;
                vWorld = aPos.xy;
            }
            """;

    public static final String FRAGMENT = """
            #version 150 core
            const int MAX_POINT_LIGHTS = 8;
            uniform sampler2D uTexture;
            uniform int uTextured;
            uniform vec4 uTint;                     // rgb multiply, a luminance lift
            uniform vec2 uSunCenter;                // world-space day/night sun
            uniform float uSunRadius;
            uniform vec3 uSunColor;
            uniform float uSunStrength;
            uniform vec2 uLightCenter[MAX_POINT_LIGHTS]; // entity lights (sun drops)
            uniform float uLightRadius[MAX_POINT_LIGHTS];
            uniform vec3 uLightColor[MAX_POINT_LIGHTS];
            uniform float uLightStrength[MAX_POINT_LIGHTS];
            uniform int uShadowMode;              // 1 = projected entity shadow pass
            uniform vec4 uShadowColor;            // shadow tint (rgb) + opacity
            uniform int uTextEffect;              // 0 = none, 1 = drop shadow, 2 = outline
            uniform vec2 uTextOffset;             // shadow offset / outline thickness, in uv
            uniform vec3 uTextEffectColor;
            in vec4 vColor;
            in vec2 vUV;
            in vec2 vWorld;
            out vec4 fragColor;
            void main() {
                vec4 tex = texture(uTexture, vUV);
                if (uShadowMode == 1) {
                    // Shadow pass: keep the sprite silhouette, tint it with the
                    // time-of-day shadow color and fade with vertex alpha.
                    float silhouette = tex.a;
                    fragColor = vec4(uShadowColor.rgb, silhouette * vColor.a * uShadowColor.a);
                    return;
                }
                if (uTextEffect != 0) {
                    // Text effects work on the glyph's coverage, not its colour: the
                    // atlas holds white ink whose alpha is the glyph's coverage, so a
                    // sample's alpha is how much of the glyph is here. (The ordinary
                    // branch below reads the same channel, which is what keeps a plain
                    // glyph and an outlined one the same shape.) One fragment shader
                    // pass beats the old approach of drawing the whole string again per
                    // effect, which smeared CJK strokes together as soon as the copies
                    // were offset by a whole pixel.
                    float ink = tex.a * vColor.a;
                    if (uTextEffect == 1) {
                        // Shadow behind a clean glyph: the offset sample only fills in
                        // where the glyph itself has no ink.
                        float behind = texture(uTexture, vUV - uTextOffset).a * vColor.a;
                        float shadowAlpha = max(behind - ink, 0.0);
                        vec3 shadowPremul = uTextEffectColor * shadowAlpha;
                        vec3 inkPremul = vColor.rgb * ink;
                        float alpha = shadowAlpha + ink * (1.0 - shadowAlpha);
                        vec3 rgb = alpha > 0.0 ? (shadowPremul + inkPremul * (1.0 - shadowAlpha)) / alpha
                                               : vec3(0.0);
                        fragColor = vec4(rgb * uTint.rgb + vec3(uTint.a), alpha);
                        return;
                    }
                    // Outline: the halo is the sample's coverage minus the glyph's own,
                    // so the strokes keep their shape instead of fattening, and the
                    // result is identical for Latin and CJK.
                    float around = texture(uTexture, vUV + vec2(uTextOffset.x, 0.0)).a;
                    around = max(around, texture(uTexture, vUV - vec2(uTextOffset.x, 0.0)).a);
                    around = max(around, texture(uTexture, vUV + vec2(0.0, uTextOffset.y)).a);
                    around = max(around, texture(uTexture, vUV - vec2(0.0, uTextOffset.y)).a);
                    float halo = max(around * vColor.a - ink, 0.0);
                    vec3 premul = uTextEffectColor * halo + vColor.rgb * ink;
                    float alpha = halo + ink * (1.0 - halo);
                    vec3 rgb = alpha > 0.0 ? premul / alpha : vec3(0.0);
                    fragColor = vec4(rgb * uTint.rgb + vec3(uTint.a), alpha);
                    return;
                }
                vec4 base = uTextured == 1 ? tex * vColor : vColor;
                vec3 rgb = base.rgb * uTint.rgb + vec3(uTint.a);
                if (uSunStrength > 0.001 && uSunRadius > 0.001) {
                    float d = distance(vWorld, uSunCenter);
                    float glow = smoothstep(uSunRadius, 0.0, d) * uSunStrength;
                    rgb += glow * uSunColor;
                }
                for (int i = 0; i < MAX_POINT_LIGHTS; i++) {
                    if (uLightStrength[i] > 0.001 && uLightRadius[i] > 0.001) {
                        float d = distance(vWorld, uLightCenter[i]);
                        float glow = smoothstep(uLightRadius[i], 0.0, d) * uLightStrength[i];
                        rgb += glow * uLightColor[i];
                    }
                }
                fragColor = vec4(rgb, base.a);
            }
            """;

    private final int id;
    private final int projectionLocation;
    private final int texturedLocation;
    private final int tintLocation;
    private final int sunCenterLocation;
    private final int sunRadiusLocation;
    private final int sunColorLocation;
    private final int sunStrengthLocation;
    private final int[] lightCenterLocations = new int[MAX_POINT_LIGHTS];
    private final int[] lightRadiusLocations = new int[MAX_POINT_LIGHTS];
    private final int[] lightColorLocations = new int[MAX_POINT_LIGHTS];
    private final int[] lightStrengthLocations = new int[MAX_POINT_LIGHTS];
    private final int shadowModeLocation;
    private final int shadowColorLocation;
    private final int textEffectLocation;
    private final int textOffsetLocation;
    private final int textEffectColorLocation;

    public ShaderProgram() {
        int vertex = compile(GL20.GL_VERTEX_SHADER, VERTEX);
        int fragment = compile(GL20.GL_FRAGMENT_SHADER, FRAGMENT);
        id = GL20.glCreateProgram();
        GL20.glAttachShader(id, vertex);
        GL20.glAttachShader(id, fragment);
        GL20.glBindAttribLocation(id, 0, "aPos");
        GL20.glBindAttribLocation(id, 1, "aColor");
        GL20.glBindAttribLocation(id, 2, "aUV");
        GL20.glLinkProgram(id);
        if (GL20.glGetProgrami(id, GL20.GL_LINK_STATUS) == 0) {
            throw new IllegalStateException("Shader link failed: " + GL20.glGetProgramInfoLog(id));
        }
        GL20.glDeleteShader(vertex);
        GL20.glDeleteShader(fragment);
        // ★ 必须先把自己 bind 上：下面那一批初始 uniform 走的是 glUniform*（作用于**当前程序**），
        //   而此刻绑着的还是上一个程序（或 0）。没绑自己时它们要么落到 0 号程序上（GL_INVALID_OPERATION，
        //   KHR_debug 会直接点名 "glUniform(program not linked)"），要么**静默改掉上一个程序的 uniform**。
        GL20.glUseProgram(id);
        projectionLocation = GL20.glGetUniformLocation(id, "uProj");
        texturedLocation = GL20.glGetUniformLocation(id, "uTextured");
        tintLocation = GL20.glGetUniformLocation(id, "uTint");
        sunCenterLocation = GL20.glGetUniformLocation(id, "uSunCenter");
        sunRadiusLocation = GL20.glGetUniformLocation(id, "uSunRadius");
        sunColorLocation = GL20.glGetUniformLocation(id, "uSunColor");
        sunStrengthLocation = GL20.glGetUniformLocation(id, "uSunStrength");
        for (int i = 0; i < MAX_POINT_LIGHTS; i++) {
            lightCenterLocations[i] = GL20.glGetUniformLocation(id, "uLightCenter[" + i + "]");
            lightRadiusLocations[i] = GL20.glGetUniformLocation(id, "uLightRadius[" + i + "]");
            lightColorLocations[i] = GL20.glGetUniformLocation(id, "uLightColor[" + i + "]");
            lightStrengthLocations[i] = GL20.glGetUniformLocation(id, "uLightStrength[" + i + "]");
        }
        shadowModeLocation = GL20.glGetUniformLocation(id, "uShadowMode");
        shadowColorLocation = GL20.glGetUniformLocation(id, "uShadowColor");
        textEffectLocation = GL20.glGetUniformLocation(id, "uTextEffect");
        textOffsetLocation = GL20.glGetUniformLocation(id, "uTextOffset");
        textEffectColorLocation = GL20.glGetUniformLocation(id, "uTextEffectColor");
        setShadowMode(false);
        setShadowColor(0.02F, 0.03F, 0.08F, 0.34F);
        setTextEffect(false, false, 0F, 0F, 1F, 1F, 1F);
        setTimeOfDay(1F, 1F, 1F, 0F, 0F, 0F, 0F, 1F, 1F, 1F, 0F);
        clearPointLights();
    }

    public void use() {
        GL20.glUseProgram(id);
    }

    public void setProjection(Matrix4f matrix) {
        GL20.glUniformMatrix4fv(projectionLocation, false, matrix.toBuffer());
    }

    public void setTextured(boolean textured) {
        GL20.glUniform1i(texturedLocation, textured ? 1 : 0);
    }

    /** Enables/disables the projected entity-shadow pass. */
    public void setShadowMode(boolean enabled) {
        GL20.glUniform1i(shadowModeLocation, enabled ? 1 : 0);
    }

    /** Shadow silhouette tint; alpha is multiplied by the sprite alpha. */
    public void setShadowColor(float r, float g, float b, float a) {
        GL20.glUniform4f(shadowColorLocation, r, g, b, a);
    }

    /**
     * Per-glyph text effect, with both offsets in texture coordinates (one glyph texel
     * is {@code 1/2048}, and a texel is a device pixel because a glyph is rasterised at
     * the size it is drawn at - the text renderer does that conversion and caps the
     * distance at the atlas gutter). Disabled by default; the text renderer turns it on
     * for one glyph at a time and off again, so a sprite drawn in between is unaffected.
     *
     * @param outline false = drop shadow, true = outline
     * @param offsetX shadow's x offset / the outline's horizontal thickness
     * @param offsetY shadow's y offset / the outline's vertical thickness
     */
    public void setTextEffect(boolean enabled, boolean outline, float offsetX, float offsetY,
                              float r, float g, float b) {
        GL20.glUniform1i(textEffectLocation, enabled ? (outline ? 2 : 1) : 0);
        GL20.glUniform2f(textOffsetLocation, offsetX, offsetY);
        GL20.glUniform3f(textEffectColorLocation, r, g, b);
    }

    /** Recommended time-of-day shader: tint + directional sun glow. */
    public void setTimeOfDay(float tintR, float tintG, float tintB, float tintLift,
                             float sunX, float sunY, float sunRadius,
                             float sunR, float sunG, float sunB, float sunStrength) {
        GL20.glUniform4f(tintLocation, tintR, tintG, tintB, tintLift);
        GL20.glUniform2f(sunCenterLocation, sunX, sunY);
        GL20.glUniform1f(sunRadiusLocation, sunRadius);
        GL20.glUniform3f(sunColorLocation, sunR, sunG, sunB);
        GL20.glUniform1f(sunStrengthLocation, sunStrength);
    }

    /** Sets one world-space point light; index must be below {@link #MAX_POINT_LIGHTS}. */
    public void setPointLight(int index, float x, float y, float radius,
                              float r, float g, float b, float strength) {
        if (index < 0 || index >= MAX_POINT_LIGHTS) {
            return;
        }
        GL20.glUniform2f(lightCenterLocations[index], x, y);
        GL20.glUniform1f(lightRadiusLocations[index], radius);
        GL20.glUniform3f(lightColorLocations[index], r, g, b);
        GL20.glUniform1f(lightStrengthLocations[index], strength);
    }

    public void clearPointLights() {
        for (int i = 0; i < MAX_POINT_LIGHTS; i++) {
            GL20.glUniform2f(lightCenterLocations[i], 0F, 0F);
            GL20.glUniform1f(lightRadiusLocations[i], 0F);
            GL20.glUniform3f(lightColorLocations[i], 0F, 0F, 0F);
            GL20.glUniform1f(lightStrengthLocations[i], 0F);
        }
    }

    public int id() {
        return id;
    }

    @Override
    public void close() {
        GL20.glDeleteProgram(id);
    }

    private static int compile(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) {
            throw new IllegalStateException("Shader compile failed: " + GL20.glGetShaderInfoLog(shader));
        }
        return shader;
    }
}
