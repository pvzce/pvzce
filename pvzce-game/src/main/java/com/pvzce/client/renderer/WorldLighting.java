package com.pvzce.client.renderer;

import com.pvzce.client.ClientEntity;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.config.PvzceClientConfig;
import com.pvzce.common.PvzceConstants;
import org.lwjgl.opengl.GL20;

/** Linear material lighting, independent geometric micro-height and near-ground contact occlusion. */
public final class WorldLighting {
    public static final String GLSL = """
            uniform int uMaterialLighting;
            uniform int uRelief;
            uniform int uMaterial;
            uniform vec4 uMaterialUv;
            uniform float uSceneNight;
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
            vec3 materialNormal(vec3 p, vec2 uv) {
                if (uReceiverEntity == 1) {
                    // Each animated part is an approximate curved sheet, not a grayscale bump map.
                    vec2 q = ((uv - uMaterialUv.xy) / (uMaterialUv.zw - uMaterialUv.xy + vec2(1e-7))) * 2.0 - 1.0;
                    return normalize(vec3(q.x * 0.65, -0.55, 0.65 + q.y * 0.25));
                }
                vec2 slope = uReceiverEntity == 2 && p.z > uReceiverLimits.x && p.z < uReceiverLimits.y ? uReceiverProfile.yz : vec2(0.0);
                if (uRelief == 1 && uMaterial > 0) {
                    vec3 clump = materialNoise(p.xy * 3.8);
                    vec3 grain = materialNoise(p.xy * vec2(31.0, 17.0));
                    float roughness = uMaterial == 1 ? 0.36 : uMaterial == 2 ? 0.24 : 0.17;
                    slope += clump.yz * roughness + grain.yz * roughness * 0.48;
                }
                return normalize(vec3(-slope, 1.0));
            }
            float materialRoughness() {
                return uReceiverEntity == 1 ? 0.67 : uMaterial == 1 ? 0.93 : uMaterial == 2 ? 0.84 : 0.58;
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
            vec3 materialLighting(vec3 albedo, vec3 p, vec3 normal, float roughness) {
                vec3 sun = normalize(uShadowLights[0].xyz);
                float visibility = lightVisibility(p, 0);
                vec3 sky = mix(vec3(0.78, 0.88, 1.0), vec3(0.44, 0.58, 1.0), uSceneNight);
                vec3 sunlight = mix(vec3(1.0, 0.89, 0.69), vec3(0.52, 0.67, 1.0), uSceneNight);
                float ambient = mix(%s, %s, uSceneNight);
                float direct = mix(%s, %s, uSceneNight);
                float ndl = max(0.0, dot(normal, sun));
                vec3 result = albedo * (sky * ambient + sunlight * direct * ndl * visibility);
                result *= contactVisibility(p);
                vec3 view = normalize(vec3(0.0, -0.85, 1.0));
                vec3 halfway = normalize(view + sun);
                result += sunlight * pow(max(dot(normal, halfway), 0.0), mix(72.0, 10.0, roughness))
                    * (1.0 - roughness) * 0.18 * direct * visibility;
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
                    result += radiance * pow(max(dot(normal, normalize(direction + view)), 0.0),
                            mix(64.0, 10.0, roughness)) * (1.0 - roughness) * 0.15;
                }
                return result;
            }
            """.formatted(PvzceConstants.LIGHT_MAX_CONTACTS, PvzceConstants.LIGHT_MAX_CONTACTS,
                    PvzceConstants.LIGHT_CONTACT_STRENGTH,
                    PvzceConstants.LIGHT_DAY_AMBIENT, PvzceConstants.LIGHT_NIGHT_AMBIENT,
                    PvzceConstants.LIGHT_DAY_DIRECT, PvzceConstants.LIGHT_NIGHT_DIRECT,
                    PvzceConstants.LIGHT_LOCAL_DAY_GAIN, PvzceConstants.LIGHT_LOCAL_NIGHT_GAIN);

    public static final class Uniforms {
        private final int enabled, relief, material, uv, night, emission, additive, count, contacts, weights;
        public Uniforms(int id) {
            enabled = GL20.glGetUniformLocation(id, "uMaterialLighting");
            relief = GL20.glGetUniformLocation(id, "uRelief");
            material = GL20.glGetUniformLocation(id, "uMaterial");
            uv = GL20.glGetUniformLocation(id, "uMaterialUv");
            night = GL20.glGetUniformLocation(id, "uSceneNight");
            emission = GL20.glGetUniformLocation(id, "uEntityEmission");
            additive = GL20.glGetUniformLocation(id, "uAdditiveLight");
            count = GL20.glGetUniformLocation(id, "uContactCount");
            contacts = GL20.glGetUniformLocation(id, "uContacts[0]");
            weights = GL20.glGetUniformLocation(id, "uContactWeight[0]");
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

    private boolean active, relief;
    private float night;
    private int count;
    private final float[] contacts = new float[PvzceConstants.LIGHT_MAX_CONTACTS * 4];
    private final float[] weights = new float[PvzceConstants.LIGHT_MAX_CONTACTS];

    public boolean active() { return active; }
    public void begin(PvzceClient client) {
        active = true;
        relief = client.config().effectEnabled(PvzceClientConfig.ShaderEffect.MATERIAL_LIGHTING);
        night = client.worldNightBlend();
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
        GL20.glUniform1i(uniforms.enabled, active ? 1 : 0);
        GL20.glUniform1i(uniforms.relief, relief ? 1 : 0);
        GL20.glUniform1f(uniforms.night, night);
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
