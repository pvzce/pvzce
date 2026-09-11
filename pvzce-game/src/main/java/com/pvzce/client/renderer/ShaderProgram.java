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
        setShadowMode(false);
        setShadowColor(0.02F, 0.03F, 0.08F, 0.34F);
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
