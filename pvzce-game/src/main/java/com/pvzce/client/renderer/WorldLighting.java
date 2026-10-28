package com.pvzce.client.renderer;

import com.pvzce.client.ClientEntity;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.config.PvzceClientConfig;
import com.pvzce.common.PvzceConstants;
import org.lwjgl.opengl.GL20;

/** Normal-driven diffuse, microfacet and hemispherical environment lighting for 2.5D surfaces. */
public final class WorldLighting {
    public static final String GLSL = """
            uniform int uMaterialLighting;
            uniform sampler2D uMaterialTexture; // the sprite color texture on unit 0; only its alpha shapes normals
            uniform float uSpecularStrength;
            uniform int uRelief;
            uniform int uMaterial;
            uniform vec4 uMaterialUv;
            uniform float uSceneNight;
            uniform vec3 uCelestialColor;
            uniform float uCelestialStrength;
            uniform float uCelestialElevation;
            uniform float uCelestialTint;
            uniform float uEntityEmission;
            uniform int uAdditiveLight;
            uniform int uContactCount;
            uniform vec4 uContacts[%d]; // world x,y,height,radius
            uniform float uContactWeight[%d];
            float materialHash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
            // Value and analytic derivatives: these heights never read the painted texture's luminance.
            vec3 materialNoise(vec2 p) {
                vec2 i = floor(p), f = fract(p), s = f * f * (3.0 - 2.0 * f);
                vec2 ds = 6.0 * f * (1.0 - f);
                float a = materialHash(i), b = materialHash(i + vec2(1, 0));
                float c = materialHash(i + vec2(0, 1)), d = materialHash(i + vec2(1, 1));
                return vec3(mix(mix(a, b, s.x), mix(c, d, s.x), s.y),
                    mix(b - a, d - c, s.y) * ds.x, mix(c - a, d - b, s.x) * ds.y);
            }
            float silhouette(vec2 uv) {
                vec2 low = min(uMaterialUv.xy, uMaterialUv.zw), high = max(uMaterialUv.xy, uMaterialUv.zw);
                vec2 inset = 0.5 / vec2(textureSize(uMaterialTexture, 0));
                low += inset;
                high -= inset;
                vec2 blur = (high - low) * 0.035;
                float alpha = texture(uMaterialTexture, clamp(uv, low, high)).a * 0.4;
                alpha += texture(uMaterialTexture, clamp(uv + vec2(blur.x, 0), low, high)).a * 0.15;
                alpha += texture(uMaterialTexture, clamp(uv - vec2(blur.x, 0), low, high)).a * 0.15;
                alpha += texture(uMaterialTexture, clamp(uv + vec2(0, blur.y), low, high)).a * 0.15;
                alpha += texture(uMaterialTexture, clamp(uv - vec2(0, blur.y), low, high)).a * 0.15;
                return alpha;
            }
            vec3 materialNormal(vec3 p, vec2 uv, vec2 projected) {
                if (uReceiverEntity == 1) {
                    vec2 span = uMaterialUv.zw - uMaterialUv.xy;
                    vec2 partUv = (uv - uMaterialUv.xy) / span;
                    vec2 q = partUv * 2.0 - 1.0;
                    if (uRelief == 0) return normalize(vec3(q.x * 0.65, -0.25, 0.9 + q.y * 0.2));
                    // Alpha contours round the actual silhouette; painted bright/dark ink is never height.
                    vec2 stepUv = span * 0.06;
                    vec2 edge = vec2(silhouette(uv + vec2(stepUv.x, 0)) - silhouette(uv - vec2(stepUv.x, 0)),
                                     silhouette(uv + vec2(0, stepUv.y)) - silhouette(uv - vec2(0, stepUv.y)));
                    vec2 curve = q * vec2(0.80, 0.55) - edge * 0.35;
                    float front = 0.14 + 0.30 * sqrt(max(0.12, 1.0 - dot(q, q) * 0.45));
                    // Derive the part's axes from its projected geometry: rotation and mirrored UVs follow animation.
                    vec2 ux = dFdx(partUv), uy = dFdy(partUv), px = dFdx(projected), py = dFdy(projected);
                    float determinant = ux.x * uy.y - ux.y * uy.x;
                    vec2 tx = normalize((px * uy.y - py * ux.y) / determinant);
                    vec2 ty = normalize((py * ux.x - px * uy.x) / determinant);
                    return normalize(vec3(tx.x * curve.x + ty.x * curve.y, -front,
                                          1.0 + tx.y * curve.x + ty.y * curve.y));
                }
                vec2 slope = uReceiverEntity == 2 && p.z > uReceiverLimits.x && p.z < uReceiverLimits.y ? uReceiverProfile.yz : vec2(0.0);
                if (uRelief == 1 && uMaterial > 0) {
                    vec3 clump = materialNoise(p.xy * 3.8);
                    vec3 grain = materialNoise(p.xy * vec2(31.0, 17.0));
                    float relief = uMaterial == 1 ? 0.42 : uMaterial == 2 ? 0.30 : 0.21;
                    float resolved = 1.0 - smoothstep(0.25, 0.75, max(fwidth(p.x * 31.0), fwidth(p.y * 17.0)));
                    slope += clump.yz * relief + grain.yz * relief * 0.48 * resolved;
                }
                return normalize(vec3(-slope, 1.0));
            }
            float materialRoughness() {
                return uReceiverEntity == 1 ? %s : uMaterial == 1 ? %s : uMaterial == 2 ? %s : uMaterial == 0 ? 0.98 : %s;
            }
            float contactVisibility(vec3 p) {
                if (uReceiverEntity == 1 || uRelief == 0) return 1.0;
                float occlusion = 0.0;
                for (int i = 0; i < uContactCount; i++) {
                    vec4 contact = uContacts[i];
                    vec2 d = (p.xy - contact.xy) / vec2(contact.w, contact.w * 0.38);
                    if (abs(d.x) > 2.7 || abs(d.y) > 2.7 || abs(p.z - contact.z) > 0.2) continue;
                    occlusion = max(occlusion, exp(-dot(d, d) * 1.6) * uContactWeight[i]);
                }
                return 1.0 - occlusion * %s;
            }
            vec3 illuminationColor(int rgb) {
                return vec3((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255) / 255.0;
            }
            // GGX distribution, Smith masking and Schlick Fresnel, evaluated as reflected radiance.
            float surfaceReflection(vec3 n, vec3 v, vec3 l, float roughness) {
                float nl = max(dot(n, l), 0.0), nv = max(dot(n, v), 0.001);
                if (nl <= 0.0) return 0.0;
                vec3 h = normalize(v + l);
                float nh = max(dot(n, h), 0.0), vh = max(dot(v, h), 0.0);
                float a2 = pow(roughness, 4.0);
                float denominator = nh * nh * (a2 - 1.0) + 1.0;
                float distribution = a2 / (3.14159265 * denominator * denominator);
                float maskingV = 2.0 * nv / (nv + sqrt(a2 + (1.0 - a2) * nv * nv));
                float maskingL = 2.0 * nl / (nl + sqrt(a2 + (1.0 - a2) * nl * nl));
                float fresnel = 0.04 + 0.96 * pow(1.0 - vh, 5.0);
                // Our diffuse convention absorbs 1/pi, so use the same incident-light units here.
                return distribution * maskingV * maskingL * fresnel * 3.14159265 / (4.0 * nv);
            }
            vec3 materialLighting(vec3 albedo, vec3 p, vec3 normal, float roughness, float reflectionStrength) {
                vec3 sun = normalize(uShadowLights[0].xyz);
                float visibility = lightVisibility(p, 0);
                float highSun = smoothstep(%s, %s, uCelestialElevation);
                float golden = (1.0 - highSun) * (1.0 - uSceneNight) * uCelestialTint;
                // Let the illuminant define the phase palette without discarding painted luminance detail.
                float chromaReduction = uSceneNight * %s + golden * %s;
                albedo = mix(albedo, vec3(dot(albedo, vec3(0.2126, 0.7152, 0.0722))), chromaReduction);
                vec3 daySky = mix(vec3(1.0), mix(illuminationColor(%d), illuminationColor(%d), highSun), uCelestialTint);
                vec3 sky = mix(daySky, illuminationColor(%d), uSceneNight);
                vec3 sunlight = mix(vec3(1.0), uCelestialColor, uCelestialTint);
                float ambient = mix(%s, %s, uSceneNight);
                float direct = uCelestialStrength;
                vec3 view = normalize(vec3(0.0, -0.85, 1.0));
                // Suppress subpixel sparkle when fine normal variations are smaller than a pixel.
                float variance = max(dot(dFdx(normal), dFdx(normal)), dot(dFdy(normal), dFdy(normal)));
                roughness = clamp(sqrt(roughness * roughness + min(0.15, variance)), 0.18, 0.98);
                float ndl = max(0.0, dot(normal, sun));
                vec3 ground = illuminationColor(%d) * sunlight * (ambient + direct * 0.55);
                vec3 bounced = ground * %s * (1.0 - normal.z * 0.6);
                vec3 result = albedo * (sky * ambient + sunlight * direct * ndl * visibility + bounced);
                result *= contactVisibility(p);
                result += sunlight * direct * visibility * surfaceReflection(normal, view, sun, roughness) * reflectionStrength;
                // An analytic sky/ground environment, not a screen-space copy of the current frame.
                vec3 reflected = reflect(-view, normal);
                float horizon = smoothstep(-0.25, 0.65, reflected.z);
                vec3 environment = mix(ground, sky * (ambient + direct * 0.35), horizon);
                // The bright sky around the active sun/moon reflects as a roughness-filtered lobe.
                // Visibility removes this directional light in shadow; the dim sky fill remains.
                float celestialLobe = pow(max(dot(reflected, sun), 0.0), mix(64.0, 4.0, roughness));
                float reflectedGain = mix(%s, %s, uSceneNight) * mix(1.0, %s, golden);
                environment += sunlight * direct * visibility * celestialLobe * reflectedGain;
                float nv = max(dot(normal, view), 0.0);
                float environmentFresnel = 0.04 + (max(0.04, 1.0 - roughness) - 0.04) * pow(1.0 - nv, 5.0);
                result += environment * environmentFresnel * (1.0 - roughness * 0.55)
                    * %s * reflectionStrength * contactVisibility(p);
                for (int i = 0; i < 8; i++) {
                    float radius = uShadowPointRadius[i];
                    if (radius <= 0.0) continue;
                    vec3 delta = uShadowLights[i + 1].xyz - p;
                    float distanceToLight = length(delta);
                    if (distanceToLight >= radius) continue;
                    vec3 direction = delta / max(0.001, distanceToLight);
                    float falloff = pow(1.0 - smoothstep(0.0, radius, distanceToLight), 2.0)
                        / (1.0 + distanceToLight * distanceToLight * 0.45);
                    float diffuse = 0.18 + 0.82 * max(dot(normal, direction), 0.0);
                    float visible = lightVisibility(p, i + 1);
                    vec3 radiance = uShadowPointColor[i].rgb * uShadowPointColor[i].a
                        * mix(%s, %s, uSceneNight) * falloff * visible;
                    result += albedo * radiance * diffuse;
                    result += radiance * surfaceReflection(normal, view, direction, roughness) * reflectionStrength;
                }
                return result;
            }
            """.formatted(PvzceConstants.LIGHT_MAX_CONTACTS, PvzceConstants.LIGHT_MAX_CONTACTS,
                    PvzceConstants.LIGHT_ENTITY_ROUGHNESS, PvzceConstants.LIGHT_GRASS_ROUGHNESS,
                    PvzceConstants.LIGHT_SOIL_ROUGHNESS, PvzceConstants.LIGHT_STONE_ROUGHNESS,
                    PvzceConstants.LIGHT_CONTACT_STRENGTH,
                    PvzceConstants.LIGHT_GOLDEN_HOUR_START, PvzceConstants.LIGHT_GOLDEN_HOUR_END,
                    PvzceConstants.LIGHT_NIGHT_CHROMA_REDUCTION, PvzceConstants.LIGHT_GOLDEN_CHROMA_REDUCTION,
                    PvzceConstants.LIGHT_HORIZON_SKY_COLOR, PvzceConstants.LIGHT_DAY_SKY_COLOR,
                    PvzceConstants.LIGHT_NIGHT_SKY_COLOR,
                    PvzceConstants.LIGHT_DAY_AMBIENT, PvzceConstants.LIGHT_NIGHT_AMBIENT,
                    PvzceConstants.LIGHT_GROUND_REFLECTANCE, PvzceConstants.LIGHT_GROUND_BOUNCE,
                    PvzceConstants.LIGHT_DAY_CELESTIAL_REFLECTION, PvzceConstants.LIGHT_NIGHT_CELESTIAL_REFLECTION,
                    PvzceConstants.LIGHT_GOLDEN_REFLECTION_GAIN,
                    PvzceConstants.LIGHT_ENVIRONMENT_REFLECTION,
                    PvzceConstants.LIGHT_LOCAL_DAY_GAIN, PvzceConstants.LIGHT_LOCAL_NIGHT_GAIN);

    public static final class Uniforms {
        private final int texture, enabled, specular, relief, material, uv, night, celestialColor, celestialStrength, celestialElevation, celestialTint, emission, additive, count, contacts, weights;
        public Uniforms(int id) {
            texture = GL20.glGetUniformLocation(id, "uMaterialTexture");
            enabled = GL20.glGetUniformLocation(id, "uMaterialLighting");
            specular = GL20.glGetUniformLocation(id, "uSpecularStrength");
            relief = GL20.glGetUniformLocation(id, "uRelief");
            material = GL20.glGetUniformLocation(id, "uMaterial");
            uv = GL20.glGetUniformLocation(id, "uMaterialUv");
            night = GL20.glGetUniformLocation(id, "uSceneNight");
            celestialColor = GL20.glGetUniformLocation(id, "uCelestialColor");
            celestialStrength = GL20.glGetUniformLocation(id, "uCelestialStrength");
            celestialElevation = GL20.glGetUniformLocation(id, "uCelestialElevation");
            celestialTint = GL20.glGetUniformLocation(id, "uCelestialTint");
            emission = GL20.glGetUniformLocation(id, "uEntityEmission");
            additive = GL20.glGetUniformLocation(id, "uAdditiveLight");
            count = GL20.glGetUniformLocation(id, "uContactCount");
            contacts = GL20.glGetUniformLocation(id, "uContacts[0]");
            weights = GL20.glGetUniformLocation(id, "uContactWeight[0]");
        }
        public void celestial(TimeOfDayLighting.CelestialLight light, boolean tinted) {
            GL20.glUniform3f(celestialColor, light.red(), light.green(), light.blue());
            GL20.glUniform1f(celestialStrength, light.strength());
            GL20.glUniform1f(celestialElevation, light.elevation());
            GL20.glUniform1f(celestialTint, tinted ? 1F : 0F);
        }
        public void uv(float u0, float v0, float u1, float v1) { GL20.glUniform4f(uv, u0, v0, u1, v1); }
        public void material(String surface) {
            int kind = switch (surface) { case "GRASS" -> 1; case "GROUND" -> 2; default -> 3; };
            GL20.glUniform1i(material, kind);
        }
        public void backdrop() { GL20.glUniform1i(material, 0); }
        public void emission(float value) { GL20.glUniform1f(emission, value); }
        public void additive(boolean value) { GL20.glUniform1i(additive, value ? 1 : 0); }
    }

    private boolean active, relief, tinted;
    private float night, specular = 1F;
    private TimeOfDayLighting.CelestialLight celestial;
    private int count;
    private final float[] contacts = new float[PvzceConstants.LIGHT_MAX_CONTACTS * 4];
    private final float[] weights = new float[PvzceConstants.LIGHT_MAX_CONTACTS];

    public boolean active() { return active; }
    public void begin(PvzceClient client) {
        active = true;
        relief = client.config().effectEnabled(PvzceClientConfig.ShaderEffect.MATERIAL_LIGHTING);
        tinted = client.config().effectEnabled(PvzceClientConfig.ShaderEffect.DAY_NIGHT);
        night = client.worldNightBlend();
        celestial = TimeOfDayLighting.celestial(client.worldLightX(), client.worldLightY(),
                client.level().width(), client.level().height(), night);
        specular = client.config().lighting(PvzceClientConfig.LightingParameter.SPECULAR);
        count = 0;
        for (ClientEntity entity : client.level().entities().values()) {
            if (count == weights.length) break;
            if ((!"plant".equals(entity.kind()) && !"zombie".equals(entity.kind())) || entity.layer() < 0) continue;
            float x = entity.visualCellX(), y = entity.visualCellY();
            float elevation = client.level().sceneBoard().elevationAt(entity.surfaceId(), x, y);
            float size = com.pvzce.common.core.EntityArt.renderScale(entity.defId()) * entity.renderScale();
            int offset = count * 4;
            contacts[offset] = x;
            contacts[offset + 1] = y - EntityVisuals.anchorLift(entity.kind());
            contacts[offset + 2] = elevation;
            contacts[offset + 3] = 0.33F * size;
            weights[count++] = (float) Math.exp(-Math.max(0F, entity.visualHeight() - elevation) * 2F);
        }
        RenderSystem.shader().lighting().backdrop();
        upload(RenderSystem.shader().lighting());
    }
    public void upload(Uniforms uniforms) {
        GL20.glUniform1i(uniforms.texture, 0);
        GL20.glUniform1i(uniforms.enabled, active ? 1 : 0);
        GL20.glUniform1f(uniforms.specular, specular);
        GL20.glUniform1i(uniforms.relief, relief ? 1 : 0);
        GL20.glUniform1f(uniforms.night, night);
        if (active) uniforms.celestial(celestial, tinted);
        GL20.glUniform1i(uniforms.count, count);
        if (count > 0) {
            GL20.glUniform4fv(uniforms.contacts, contacts);
            GL20.glUniform1fv(uniforms.weights, weights);
        }
    }
    public void end() {
        active = false;
        GL20.glUniform1i(RenderSystem.shader().lighting().enabled, 0);
        RenderSystem.shader().lighting().emission(0F);
    }
}
