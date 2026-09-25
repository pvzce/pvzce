package com.pvzce.client.renderer.liquid;

import org.lwjgl.opengl.GL20;

import java.io.Closeable;

/**
 * The liquid shader: a self-contained GLSL program that draws a body of water.
 *
 * <p>It is separate from {@link com.pvzce.client.renderer.ShaderProgram} because it
 * needs a fourth vertex attribute the sprite shader has no use for and a uniform
 * set (waves, caustics, fresnel, ripples) that would otherwise be dead weight on
 * every sprite draw. It binds its own attribute locations instead of relying on
 * the shared program's, so neither program constrains the other.
 *
 * <p>What the fragment stage does, in the order the terms are combined:
 * <ol>
 *   <li>tiles the base texture over the WHOLE body of water, deriving the tiling
 *       from the cell coordinates so neighbouring cells continue the pattern
 *       instead of restarting it;</li>
 *   <li>mixes a shallow and a deep colour by the cell's distance from land;</li>
 *   <li>adds caustics - two layers of periodic value noise scrolling against each
 *       other;</li>
 *   <li>adds the sun or moon's specular glitter, positioned from the same light
 *       vector the sprite shader uses;</li>
 *   <li>mixes in a reflection colour by a fresnel term, so grazing angles pick up
 *       the sky;</li>
 *   <li>draws shoreline foam and pulls the cell outline in on the sides that face
 *       land, which is what stops the water from reading as a grid of tiles;</li>
 *   <li>applies the same day/night tint and point lights the sprites get, so a
 *       night level's water is dark without a second uniform set.</li>
 * </ol>
 *
 * <p>The noise is generated in the shader on purpose. A texture would need a
 * second asset to keep in sync with the wave parameters, would have to be
 * tileable, and would quantise the animation; two or three octaves of hashed
 * value noise are enough at this scale and cost nothing to retune.
 */
public final class LiquidShader implements Closeable {
    /** Attribute locations this program binds; the batch writes matching offsets. */
    public static final int ATTRIB_POSITION = 0;
    public static final int ATTRIB_COLOR = 1;
    public static final int ATTRIB_UV = 2;
    public static final int ATTRIB_LIQUID = 3;

    public static final int MAX_RIPPLES = 16;

    /** Feature bits for {@code uFeatures}; the water quality setting maps to these. */
    public static final int FEATURE_WAVES = 1;
    public static final int FEATURE_CAUSTICS = 2;
    public static final int FEATURE_FRESNEL = 4;
    public static final int FEATURE_SPECULAR = 8;
    public static final int FEATURE_RIPPLES = 16;
    /** Bit for the extra caustic sheet; see {@link LiquidRenderer#featuresFor}. */
    public static final int FEATURE_CAUSTIC_SHEET = 64;
    public static final int FEATURE_SHORE = 32;

    /**
     * Shapes the shallow-to-deep ramp.
     *
     * <p>Depth arrives as a distance-to-land fraction, so a linear mix spends the
     * whole first cell at nearly the shallow colour and then falls off a cliff at
     * the boundary between the shore ring and open water - on a three-row level,
     * whose middle row is the only water more than one step from land, that reads
     * as a flat pale moat around a dark stripe. Gamma below 1 lifts the middle of
     * the ramp so the transition is gradual.
     *
     * <p>It is a constant rather than a content field on purpose: it is a property
     * of mixing two colours under this metric, not a per-liquid look, and every
     * liquid would want the same value.
     */
    public static final float DEPTH_GAMMA = 0.55F;

    /**
     * How much of the shoreline foam's strength survives.
     *
     * <p>The foam marks where water meets land, and without it the surface just stops
     * at a hard edge. At full strength, though, it is a near-white band about a sixth
     * of a cell wide, and a player reads that as a line drawn on the water rather than
     * as a shore - which is what "还是存在十分明显的白线" was about. Damping the whole
     * term keeps the cue and loses the outline.
     */
    public static final float FOAM_STRENGTH = 0.30F;

    /**
     * Largest depth factor one vertex can carry, and the number of steps used to pack
     * it. Must match {@link LiquidCell#MAX_DEPTH_FACTOR} and
     * {@link LiquidCell#DEPTH_LEVELS}: the CPU rounds every corner through those, and
     * these two turn the packed integer back into the same number. A mismatch would
     * silently rescale the whole depth ramp.
     */
    public static final float DEPTH_FACTOR_MAX = LiquidCell.MAX_DEPTH_FACTOR;
    /** Levels minus one, i.e. the divisor that turns a packed integer back into a factor. */
    public static final float DEPTH_LEVEL_STEPS = LiquidCell.DEPTH_LEVELS - 1;

    /**
     * The GLSL text with the two packing constants written into it.
     *
     * <p>The alternative - a uniform, or a literal repeated in the GLSL - is how a CPU
     * and a GPU come to disagree about the scale of the same number, which silently
     * stretches or compresses the whole depth ramp with nothing to see in the source.
     * Substituting at class-initialisation time keeps {@link LiquidCell} the single
     * place those two numbers are defined.
     */
    private static String glsl(String source) {
        return source
                .replace("DEPTH_FACTOR_MAX", glslFloat(DEPTH_FACTOR_MAX))
                .replace("DEPTH_LEVEL_STEPS", glslFloat(DEPTH_LEVEL_STEPS))
                .replace("FOAM_STRENGTH", glslFloat(FOAM_STRENGTH));
    }

    private static String glslFloat(float value) {
        return String.format(java.util.Locale.ROOT, "%.9g", value);
    }

    public static final String VERTEX = """
            #version 150 core
            in vec3 aPos;
            in vec4 aColor;
            in vec2 aUV;
            in vec4 aLiquid;
            uniform mat4 uProj;
            out vec2 vColorUv;
            out vec2 vWorld;
            out vec4 vLiquid;
            out float vShore;
            void main() {
                gl_Position = uProj * vec4(aPos, 1.0);
                vColorUv = aUV;
                vWorld = aPos.xy;
                vLiquid = aLiquid;
                vShore = aColor.a;      // 1 = this cell touches land, 0 = open water
            }
            """;

    
    public static final String FRAGMENT = glsl("""
            #version 150 core
            const int MAX_RIPPLES = 16;
            uniform sampler2D uTexture;
            uniform int uHasTexture;
            uniform sampler2D uCausticTexture;
            uniform int uHasCausticTexture;
            uniform float uCausticScale;    // caustic tiles per world cell
            uniform float uCausticScroll;   // caustic tiles per second
            uniform float uCausticGain;
            uniform vec3 uShallow;
            uniform vec3 uDeep;
            uniform float uOpacity;
            uniform float uDepthScale;
            uniform float uDepthGamma;
            uniform vec3 uFoam;
            uniform float uFoamWidth;
            uniform float uEdgeFeather;
            uniform float uTime;
            uniform float uWaveSpeed;
            uniform float uWaveAmplitude;
            uniform float uWaveDensity;
            uniform float uCaustics;
            uniform vec3 uReflect;
            uniform float uFresnel;
            uniform float uSpecular;
            uniform float uSpecularPower;
            uniform float uBaseScale;       // base-texture tiles per world cell
            uniform vec2 uLightCenter;      // sun/moon, in world cells
            uniform vec3 uLightColor;
            uniform float uLightStrength;
            uniform vec3 uTint;             // day/night tint, rgb multiply
            uniform float uTintLift;
            uniform float uNight;
            uniform int uFeatures;
            uniform int uRippleCount;
            uniform vec4 uRipples[MAX_RIPPLES];   // xy = centre (world cells), z = age 0..1, w = strength
            in vec2 vColorUv;
            in vec2 vWorld;
            in vec4 vLiquid;
            in float vShore;
            out vec4 fragColor;

            float hash(vec2 p) {
                return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
            }

            float valueNoise(vec2 p) {
                vec2 i = floor(p);
                vec2 f = fract(p);
                vec2 u = f * f * (3.0 - 2.0 * f);
                float a = hash(i);
                float b = hash(i + vec2(1.0, 0.0));
                float c = hash(i + vec2(0.0, 1.0));
                float d = hash(i + vec2(1.0, 1.0));
                return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
            }

            float fbm(vec2 p) {
                float sum = 0.0;
                float amplitude = 0.5;
                for (int i = 0; i < 3; i++) {
                    sum += valueNoise(p) * amplitude;
                    p *= 2.03;
                    amplitude *= 0.5;
                }
                return sum;
            }

            /* Unsigned distance to the part of the cell outline that faces land.
               GLSL 150 has no isinf, so an absent side is represented by a large
               finite value: it never wins the min() against a real side, and the
               max() against 0 covers the case where no side is a shore. */
            float shoreField(vec2 uv, int borderMask, int cornerMask) {
                float far = 1.0e6;
                float north = (borderMask & 1) != 0 ? 1.0 - uv.y : far;
                float east  = (borderMask & 2) != 0 ? uv.x       : far;
                float south = (borderMask & 4) != 0 ? uv.y       : far;
                float west  = (borderMask & 8) != 0 ? 1.0 - uv.x : far;
                float minDistance = min(min(north, east), min(south, west));
                if ((cornerMask & 1) != 0) minDistance = min(minDistance, distance(uv, vec2(1.0, 1.0)));
                if ((cornerMask & 2) != 0) minDistance = min(minDistance, distance(uv, vec2(1.0, 0.0)));
                if ((cornerMask & 4) != 0) minDistance = min(minDistance, distance(uv, vec2(0.0, 0.0)));
                if ((cornerMask & 8) != 0) minDistance = min(minDistance, distance(uv, vec2(0.0, 1.0)));
                return max(minDistance, 0.0);
            }

            void main() {
                /* Unpack the cell attribute. The +1 the CPU added keeps the packed
                   value away from zero, so its sign can carry "the north (x) / west
                   (y) neighbour continues the liquid" without a second channel. */
                int packedBorders = int(vLiquid.z + 0.5);
                int packedParts = int(vLiquid.w + 0.5);
                int borderMask = packedBorders & 15;
                int cornerMask = packedParts & 15;
                float cellX = abs(vLiquid.x) - 1.0;
                float cellY = abs(vLiquid.y) - 1.0;

                /* Local coordinate: aUV arrives as the vertex's plain position inside
                   its cell, 0..1, with no folding. (Earlier revisions of this shader and
                   of LiquidVertexFormat claimed aUV was sign-flipped and folded back
                   through [0,2); it never was, so the two branches that read it were
                   dead code. See LiquidVertexFormat for the axes note - the mask
                   measures north on y and west on x, so any future fold must swap them
                   or the foam lands on the wrong edges.) */
                vec2 uv = vec2(fract(vColorUv.x), fract(vColorUv.y));

                /* DEPTH IS INTERPOLATED BETWEEN THE CELL'S FOUR CORNERS, not taken from
                   the cell as a whole.

                   The CPU measures the distance to land at each corner and packs that
                   corner's factor into that corner's vertices, so vLiquid.w is already
                   a per-vertex value that the rasteriser blends across the quad. A
                   single per-cell value - which is what this used to be, an integer
                   count of grid steps to land - can only ever paint a flat plate per
                   cell: the whole lake resolves into two or three tones with a hard
                   step at every cell boundary, and on a 5x4 pool it collapsed to
                   exactly two (the normaliser was min(depth_scale, the body's deepest
                   cell), and a pool that size is one step deep). That is the visible
                   seam this replaced. Per-corner values give a gradient ACROSS each
                   cell as well as between them, so shallow-to-deep is continuous
                   everywhere. */
                int depthBits = packedParts >> 4;
                float depthFactor = float(depthBits) * (DEPTH_FACTOR_MAX / DEPTH_LEVEL_STEPS);

                /* The water body is opaque; only the rim that faces land feathers out,
                   and only that rim foams. `shoreField` measures the distance to the
                   part of THIS cell's outline that faces land, so a cell in open water
                   has none and gets the sentinel: far larger than any feather or foam
                   band, which is exactly what makes the interior solid.

                   This sentinel is load-bearing. Leaving `field` at its 0.0 default for
                   open water was a real bug: 0 is also the value that means "this
                   fragment IS on a shore", so alpha came out 0 on every interior cell,
                   the dirt showed straight through the middle of a lake, and only the
                   rim - the cells that do touch land - rendered as water. */
                const float OPEN_WATER_FIELD = 1.0e6;
                bool shoreCell = vShore > 0.02;
                float field = OPEN_WATER_FIELD;
                if (shoreCell && (uFeatures & 32) != 0) {
                    field = shoreField(uv, borderMask, cornerMask);
                }

                /* Continuous tiling: the pattern is a function of the world cell
                   coordinate, never of the cell's own uv, so neighbouring cells
                   continue one pattern instead of restarting it. */
                /* BASE TEXTURE FIRST, AND KEPT SHARP.
                   The order matters more than any single parameter. Mixing the
                   seabed toward a flat water colour - which is how this started -
                   multiplies the texture's contrast by (1 - opacity), so at 0.82
                   opacity 82% of the only detailed thing in the surface was thrown
                   away and what remained was a uniform wash. Measured: the texture's
                   per-channel std is 0.09 and the water colour's is 0.0, so the
                   result read as a grey smear rather than as water.
                   Water absorbs light, so it is a tint OVER a visible floor. */
                vec2 baseUv = (vec2(cellX, cellY) + uv) * uBaseScale;
                vec3 base = uHasTexture == 1
                        ? texture(uTexture, fract(baseUv)).rgb
                        : vec3(0.62, 0.58, 0.42);

                /* THE RAMP IS ASYMPTOTIC, AND THAT IS THE POINT.

                   `depthFactor` is the interpolated distance to land in units of
                   uDepthScale, so 1.0 means "one depth_scale from shore". The obvious
                   ramp is clamp(depthFactor, 0, 1), and it is wrong at exactly the size
                   the game draws: it REACHES full deep and then goes flat, so every
                   fragment past that distance is the identical colour and the ramp
                   reads as a band with a boundary around it rather than as water
                   getting deeper. d / (d + 1) instead approaches full depth without
                   ever arriving: at 1.0 it is half deep, at 2.0 two thirds, and it keeps
                   responding to real distance for as long as there is any. depth_scale
                   therefore still means what it says - the distance at which the water
                   is half deep - it just no longer doubles as a cut-off.

                   The curve is also deliberately SHALLOW NEAR THE SHORE. d / (d + 1)
                   rises fastest at d = 0, so at the waterline it spends its whole
                   gradient inside the first fraction of a cell: the shoreline cell ends
                   up visibly lighter than the one behind it, and that step is what reads
                   as a line tracing the edge of a lake. `1 - exp(-d)` starts off linear
                   and then eases, so the same distance buys a much more even fade and the
                   ramp reaches further into the body rather than spending itself at the
                   rim.

                   `1 - (1 + d) * exp(-d)` does both: it approaches 1 without arriving
                   (no plate), and near the shore it behaves like d^2/2, so the waterline
                   itself is the SHALLOWEST point of the curve with no gradient spent on
                   a bright first pixel. */
                float ramp = 1.0 - (1.0 + depthFactor) * exp(-depthFactor);
                float shaped = pow(clamp(ramp, 0.0, 1.0), uDepthGamma);
                vec3 water = mix(uShallow, uDeep, shaped);
                /* Absorption, plus a small ambient term so deep water never goes to
                   black. uOpacity is how much of the surface is water rather than
                   floor, NOT an alpha. At 0.82 the floor contributed only
                   (1-0.82)*1.25 = 0.225 of the water colour, so the texture's detail
                   reached the output at about a tenth of its own contrast and the
                   per-cell colour still dominated - the "flat mosaic" complaint. 0.68
                   roughly doubles the floor's weight while leaving the water in
                   charge. */
                vec3 col = mix(base * water * 2.2, water * 0.75, clamp(uOpacity, 0.0, 1.0));

                /* Ripples disturb the surface normal, so the sun's reflection
                   wriggles through them rather than merely brightening a ring. */
                vec2 rippleNormal = vec2(0.0);

                /* Waves. Two scrolling layers of value noise, used to REFRACT the
                   light rather than to tint the water: a small offset that distorts
                   the caustics keeps the seabed itself sharp, which is what makes
                   the body read as still water instead of jelly. */
                float waveTilt = 0.0;
                vec2 flow = vec2(uTime * uWaveSpeed, uTime * uWaveSpeed * 0.55);
                /* + body, NOT just the cell index. Without the local coordinate every
                   one of these is constant across its cell, so the surface becomes a
                   mosaic of flat squares with a hard step at each cell boundary - the
                   exact artefact that made the first version read as "深浅非常不流畅".
                   A boundary-jump check cannot see it: the step is small, it is the
                   LACK of variation inside the cell that is wrong. */
                vec2 world = vec2(cellX, cellY) + uv;
                float causticTerm = 0.0;
                if ((uFeatures & 1) != 0) {
                    float coarse = fbm(world * uWaveDensity + flow * 0.7);
                    float fine = fbm(world * uWaveDensity * 2.7 - flow);
                    waveTilt = (coarse - 0.5) * uWaveAmplitude + (fine - 0.5) * uWaveAmplitude * 0.4;
                }
                if ((uFeatures & 16) != 0 && uRippleCount > 0) {
                    for (int i = 0; i < MAX_RIPPLES; i++) {
                        if (i >= uRippleCount) {
                            break;
                        }
                        vec4 ripple = uRipples[i];
                        float age = ripple.z;
                        if (age <= 0.0 || age >= 1.0) {
                            continue;
                        }
                        vec2 offset = world - ripple.xy;
                        float radius = age * 1.35;
                        float band = 1.0 - smoothstep(0.0, 0.18, abs(length(offset) - radius));
                        float decay = (1.0 - age) * ripple.w;
                        col += band * decay * vec3(0.75, 0.88, 0.95) * 0.30;
                        rippleNormal += normalize(offset + vec2(1.0e-4)) * band * decay;
                    }
                }

                if ((uFeatures & 2) != 0) {
                    /* Caustics as a REFRACTED NETWORK: three cosine ripple trains,
                       each bent by the same noise the waves use (plus the ripples),
                       summed and sharpened into light lines. The first attempt used a
                       single noise octave, which produces isolated bright blobs that
                       read as grain on the sand rather than as light on a surface;
                       crossing trains are what make the web pattern.

                       THE FREQUENCIES ARE GEOMETRY, NOT TASTE. A board cell is about
                       80 pixels, so a coefficient of N puts a band every 80/N pixels;
                       the 19/17/26 first chosen gave roughly 4 pixels per cycle, which
                       undersamples at the resolution the game actually draws at and
                       turned the cell boundary - a quarter-pixel shift - into a visible
                       line. Measured with tools/preview_water.py --seams, which
                       attributes the jump across a cell join to the term that caused
                       it: at these values the seam is gone. */
                    vec2 bent = world + waveTilt * 0.22 + rippleNormal * 0.30;
                    float web = 0.0;
                    web += 0.5 + 0.5 * cos(7.0 * (bent.x + 0.35 * bent.y) + uTime * 0.55);
                    web += 0.5 + 0.5 * cos(6.3 * (bent.y - 0.30 * bent.x) - uTime * 0.47);
                    web += 0.5 + 0.5 * cos(9.0 * (bent.x - 0.55 * bent.y) + uTime * 0.33);
                    /* The exponent sharpens the summed trains into THIN bright lines -
                       a caustic web is sparse highlights, not a glow. At 2.4 the sum of
                       three cosines is broad and round, so raising the gain (the first
                       attempt at making it visible) blew it into white blobs that also
                       clipped away the continuity at every cell join. Sharper and
                       quieter is what makes both the pattern and the seams right. */
                    float caustic = pow(clamp(web / 3.0, 0.0, 1.0), 4.0);
                    float lit = 0.35 + 0.65 * max(uLightStrength, 0.12);
                    caustic *= lit * mix(1.0, 0.4, shaped);
                    /* 3.4x, not 1.6x: at 1.6 the pattern was structurally correct but
                       contributed about a third of the floor texture's contrast, so the
                       surface still read as "sand with a wash over it" rather than as
                       water. The water's own detail has to be at least as strong as the
                       floor's, or the floor is all the eye sees. */
                    col += (caustic * uCaustics * 1.5 + uCaustics * waveTilt * 0.14)
                            * vec3(0.72, 0.95, 0.88);
                    causticTerm = caustic * uCaustics * 1.5 + uCaustics * waveTilt * 0.14;
                }

                /* THE ORIGINAL'S OWN CAUSTIC SHEET, drifting.
                   The procedural web above is additive light, and on this surface there is
                   almost nothing left to add to: the basin art sits at 240-255 in G and B over
                   most of its area, so the measured per-pixel change over time was a median of
                   1.3 of 255 - a pool that does not visibly move however the clock runs. The
                   sheet is drawn as its own texture instead, and it is a MODULATION: the
                   original's drawing has broad dark lanes between the light cells, so it can
                   take light away as well as give it, which is what makes the motion read.

                   The two axes drift at different rates, so the pattern travels diagonally
                   rather than sliding along one edge. Sampled in world-cell space like the
                   base art, so neighbouring cells continue one another. */
                if ((uFeatures & 64) != 0 && uHasCausticTexture == 1) {
                    vec2 causticUv = world * uCausticScale
                            + vec2(uTime * uCausticScroll, uTime * uCausticScroll * 0.35);
                    float sheet = texture(uCausticTexture, fract(causticUv)).r;
                    /* Centred first: the sheet's own average is a mid grey, and adding that
                       straight would lift the whole pool instead of laying a web on it. */
                    float web = (sheet - 0.5) * uCausticGain * mix(1.0, 0.45, shaped);
                    col += web * vec3(0.86, 1.0, 0.98);
                    causticTerm += web;
                }

                if ((uFeatures & 8) != 0 && uLightStrength > 0.001) {
                    vec2 offset = world - uLightCenter;
                    float distanceToLight = max(length(offset), 1.0e-3);
                    vec2 surfaceNormal = offset / distanceToLight + rippleNormal * 1.6;
                    /* A low slope keeps the glitter a narrow band instead of a
                       full disc around the light. */
                    /* The slope must be able to reach the mirror condition.
                       With a light at z = 0.35 and a surface normal near vertical, the
                       reflection lines up when the normal is tilted by roughly 2.9
                       times the horizontal offset direction - but `distance * 0.08`
                       only reaches 0.5 even at the far corner of the board, so the dot
                       product peaked at 0.88 and dot^48 was ~1e-4: the term was
                       invisible at every quality tier. 0.45 reaches the band a few
                       cells out from the light, where a glitter path should be. */
                    float slope = distanceToLight * 0.45 + waveTilt * 0.9;
                    vec3 normal = normalize(vec3(surfaceNormal.x * slope,
                                                 surfaceNormal.y * slope, 1.0));
                    vec3 lightDir = normalize(vec3(surfaceNormal, 0.35));
                    float highlight = pow(max(dot(normal, lightDir), 0.0), uSpecularPower);
                    float falloff = 1.0 / (1.0 + distanceToLight * 0.35);
                    col += highlight * uSpecular * uLightStrength * falloff * uLightColor;
                }

                if ((uFeatures & 4) != 0) {
                    /* The reflection follows the DISTANCE FROM SHORE, not the cell's own
                       v. The previous version used abs(fract(vColorUv.y) * 2 - 1), which
                       is a periodic function of the cell: it peaked in a horizontal
                       stripe across the middle of EVERY cell and died at every cell
                       boundary, and it was the largest within-cell variation in the
                       shader (measured std 39/255). An orthographic board view has no
                       view direction to take a grazing angle from, so a real fresnel is
                       not available; distance from shore at least means "the part of
                       the surface that faces open water", which is the intent.

                       It reads the INTERPOLATED depth factor rather than `field`, so the
                       reflection fades in smoothly over open water instead of snapping
                       on at the cell that stops touching land. */
                    float openness = clamp(uDepthScale * 0.75 - field, 0.0, 1.0);
                    float fresnel = openness * uFresnel;
                    col = mix(col, uReflect * mix(vec3(0.55, 0.68, 0.88), uLightColor, uNight),
                            clamp(fresnel * 0.30, 0.0, 0.35));
                }

                /* THE WATER BODY IS OPAQUE; only the waterline feathers out.
                   An earlier version feathered with `mix(uOpacity, 1.0, inset)`, which
                   made the whole surface 82% transparent - every pixel was mostly the
                   grass behind it and the water could never look like water, whatever
                   the colours were. A dish of water seen from above is not a window.
                   The feather is the ONLY place alpha drops below 1. */
                /* THE FEATHER COMES FROM THE CONTINUOUS DISTANCE FIELD, NOT FROM `field`.

                   This is the second time this code has had to learn the same lesson.
                   `field` is measured against the CURRENT CELL's own outline, so it is
                   not a property of the body: two cells that share an edge compute
                   completely different values there - 255 on one side, 0 on the other -
                   because the border and corner masks describe the cell, not the border.
                   Feathering on it therefore punches a transparent slit along every edge
                   where a shore-owning cell meets a cell that owns no shore, and the
                   lawn behind shows through: measured as a 15px band of pure background
                   colour running the full height of the water.

                   `depthFactor` has no such problem. It is the distance to land sampled
                   at the cell's CORNERS, so neighbours share those samples exactly and
                   the interpolated value is continuous across the whole body - which is
                   what makes it usable for a waterline as well as for a depth ramp.

                   Only the very shallowest water fades: uEdgeFeather is how far out
                   that fade reaches. It is deliberately much shorter than the depth
                   ramp, which is allowed to spread over the whole body. */
                float shoreCells = depthFactor * uDepthScale;
                float featherNorm = clamp(shoreCells / max(uEdgeFeather, 1.0e-3), 0.0, 1.0);
                float alpha = featherNorm * featherNorm * (3.0 - 2.0 * featherNorm);

                if (shoreCell && (uFeatures & 32) != 0) {
                    float foamWidth = max(uFoamWidth, 1.0e-3);
                    float foamNorm = clamp(field / foamWidth, 0.0, 1.0);
                    float foam = 1.0 - (foamNorm * foamNorm * (3.0 - 2.0 * foamNorm));
                    /* A brighter crest just inside the rim; two smoothsteps rather
                       than one so the foam has a shape instead of being a flat cap. */
                    float crestNorm = clamp((field - foamWidth * 0.5) / max(foamWidth * 0.65, 1.0e-3),
                                            0.0, 1.0);
                    /* Windowed so it is ZERO at the outline and peaks at the foam band's
                       inner edge. `inset` (the old texture remap) was the wrong window:
                       it was 1 at the outline, so the crest stacked onto the foam there
                       and the two together were a flat cap - the shape the comment above
                       claims to avoid. */
                    float crest = (1.0 - (crestNorm * crestNorm * (3.0 - 2.0 * crestNorm)))
                            * smoothstep(0.0, uFoamWidth * 0.5, field);
                    /* vShore is the per-cell foam strength LiquidCell.shoreStrength
                       computed (0.75..1.0, so a one-cell inlet does not become a solid
                       white blob). `0.72 + 0.28 * depth` was dead: foam is only drawn
                       for shore cells and every shore cell is depth 0 by construction,
                       so it was the constant 0.72 and the authored field was discarded. */
                    float strength = mix(0.72, 1.0, vShore) * FOAM_STRENGTH;
                    col = mix(col, uFoam, clamp(foam * strength, 0.0, 0.85));
                    col = mix(col, uFoam, clamp(crest * strength, 0.0, 0.30));
                }

                /* The same tint maths the sprite shader applies, so water does not
                   drift away from the rest of the board at dusk. */
                col = col * uTint + vec3(uTintLift);
                col = mix(col, col * vec3(0.72, 0.80, 1.06), clamp(uNight, 0.0, 1.0) * 0.45);
                fragColor = vec4(col, alpha);
            }
            """);

    private final int id;
    private final int projectionLocation;
    private final int textureLocation;
    private final int hasTextureLocation;
    private final int causticTextureLocation;
    private final int hasCausticTextureLocation;
    private final int causticScaleLocation;
    private final int causticScrollLocation;
    private final int causticGainLocation;
    private final int shallowLocation;
    private final int deepLocation;
    private final int opacityLocation;
    private final int depthScaleLocation;
    private final int depthGammaLocation;
    private final int foamLocation;
    private final int foamWidthLocation;
    private final int edgeFeatherLocation;
    private final int timeLocation;
    private final int waveSpeedLocation;
    private final int waveAmplitudeLocation;
    private final int waveDensityLocation;
    private final int causticsLocation;
    private final int reflectLocation;
    private final int fresnelLocation;
    private final int specularLocation;
    private final int specularPowerLocation;
    private final int baseScaleLocation;
    private final int lightCenterLocation;
    private final int lightColorLocation;
    private final int lightStrengthLocation;
    private final int tintLocation;
    private final int tintLiftLocation;
    private final int nightLocation;
    private final int featuresLocation;
    private final int rippleCountLocation;
    private final int ripplesLocation;

    public LiquidShader() {
        int vertex = compile(GL20.GL_VERTEX_SHADER, VERTEX);
        int fragment = compile(GL20.GL_FRAGMENT_SHADER, FRAGMENT);
        id = GL20.glCreateProgram();
        GL20.glAttachShader(id, vertex);
        GL20.glAttachShader(id, fragment);
        GL20.glBindAttribLocation(id, ATTRIB_POSITION, "aPos");
        GL20.glBindAttribLocation(id, ATTRIB_COLOR, "aColor");
        GL20.glBindAttribLocation(id, ATTRIB_UV, "aUV");
        GL20.glBindAttribLocation(id, ATTRIB_LIQUID, "aLiquid");
        GL20.glLinkProgram(id);
        if (GL20.glGetProgrami(id, GL20.GL_LINK_STATUS) == 0) {
            throw new IllegalStateException("Liquid shader link failed: "
                    + GL20.glGetProgramInfoLog(id));
        }
        GL20.glDeleteShader(vertex);
        GL20.glDeleteShader(fragment);

        projectionLocation = uniform("uProj");
        textureLocation = uniform("uTexture");
        hasTextureLocation = uniform("uHasTexture");
        causticTextureLocation = uniform("uCausticTexture");
        hasCausticTextureLocation = uniform("uHasCausticTexture");
        causticScaleLocation = uniform("uCausticScale");
        causticScrollLocation = uniform("uCausticScroll");
        causticGainLocation = uniform("uCausticGain");
        shallowLocation = uniform("uShallow");
        deepLocation = uniform("uDeep");
        opacityLocation = uniform("uOpacity");
        depthScaleLocation = uniform("uDepthScale");
        depthGammaLocation = uniform("uDepthGamma");
        foamLocation = uniform("uFoam");
        foamWidthLocation = uniform("uFoamWidth");
        edgeFeatherLocation = uniform("uEdgeFeather");
        timeLocation = uniform("uTime");
        waveSpeedLocation = uniform("uWaveSpeed");
        waveAmplitudeLocation = uniform("uWaveAmplitude");
        waveDensityLocation = uniform("uWaveDensity");
        causticsLocation = uniform("uCaustics");
        reflectLocation = uniform("uReflect");
        fresnelLocation = uniform("uFresnel");
        specularLocation = uniform("uSpecular");
        specularPowerLocation = uniform("uSpecularPower");
        baseScaleLocation = uniform("uBaseScale");
        lightCenterLocation = uniform("uLightCenter");
        lightColorLocation = uniform("uLightColor");
        lightStrengthLocation = uniform("uLightStrength");
        tintLocation = uniform("uTint");
        tintLiftLocation = uniform("uTintLift");
        nightLocation = uniform("uNight");
        featuresLocation = uniform("uFeatures");
        rippleCountLocation = uniform("uRippleCount");
        ripplesLocation = uniform("uRipples[0]");
    }

    private int uniform(String name) {
        return GL20.glGetUniformLocation(id, name);
    }

    public void use() {
        GL20.glUseProgram(id);
    }

    public void setProjection(com.pvzce.client.renderer.Matrix4f matrix) {
        GL20.glUniformMatrix4fv(projectionLocation, false, matrix.toBuffer());
    }

    public void setTexture(int textureId, boolean hasTexture) {
        GL20.glUniform1i(textureLocation, 0);
        GL20.glUniform1i(hasTextureLocation, hasTexture ? 1 : 0);
    }

    /**
     * Points the caustic layer at a texture unit, or switches it off.
     *
     * @param textureUnit the unit the sheet is bound to, or -1 for "no sheet"
     */
    public void setCausticTexture(int textureUnit, boolean hasTexture, float scale, float scroll,
                                  float gain) {
        GL20.glUniform1i(causticTextureLocation, Math.max(0, textureUnit));
        GL20.glUniform1i(hasCausticTextureLocation, hasTexture ? 1 : 0);
        GL20.glUniform1f(causticScaleLocation, scale);
        GL20.glUniform1f(causticScrollLocation, scroll);
        GL20.glUniform1f(causticGainLocation, gain);
    }

    public void setBaseScale(float tilesPerCell) {
        GL20.glUniform1f(baseScaleLocation, tilesPerCell);
    }

    public void setColors(float[] shallow, float[] deep, float[] foam, float[] reflect, float opacity) {
        GL20.glUniform3f(shallowLocation, shallow[0], shallow[1], shallow[2]);
        GL20.glUniform3f(deepLocation, deep[0], deep[1], deep[2]);
        GL20.glUniform3f(foamLocation, foam[0], foam[1], foam[2]);
        GL20.glUniform3f(reflectLocation, reflect[0], reflect[1], reflect[2]);
        GL20.glUniform1f(opacityLocation, opacity);
    }

    /** Wave density is in samples per world cell; 2 gives waves about half a cell across. */
    /**
     * How far the waterline fade reaches inland, in world cells.
     *
     * <p>Short on purpose. This is the soft silhouette at the shore, not the depth
     * ramp - the ramp is free to spread over the whole body, while a wide feather
     * would make the water look like it is dissolving everywhere. At 0.12 cells the
     * fade is about ten screen pixels at the game's scale, and the interior seams
     * that a longer feather would open up stay invisible.
     */
    public static final float FEATHER_CELLS = 0.12F;

    public void setSurface(float depthScale, float foamWidth, float waveSpeed, float waveAmplitude,
                           float waveDensity, float caustics, float fresnel, float specular,
                           float specularPower) {
        GL20.glUniform1f(depthScaleLocation, depthScale);
        GL20.glUniform1f(depthGammaLocation, DEPTH_GAMMA);
        GL20.glUniform1f(edgeFeatherLocation, FEATHER_CELLS);
        GL20.glUniform1f(foamWidthLocation, foamWidth);
        GL20.glUniform1f(waveSpeedLocation, waveSpeed);
        GL20.glUniform1f(waveAmplitudeLocation, waveAmplitude);
        GL20.glUniform1f(waveDensityLocation, waveDensity);
        GL20.glUniform1f(causticsLocation, caustics);
        GL20.glUniform1f(fresnelLocation, fresnel);
        GL20.glUniform1f(specularLocation, specular);
        GL20.glUniform1f(specularPowerLocation, specularPower);
    }

    public void setTime(float seconds) {
        GL20.glUniform1f(timeLocation, seconds);
    }

    /** Scene lighting, shared with the sprite shader's day/night values. */
    public void setLighting(float tintR, float tintG, float tintB, float tintLift, float night,
                            float lightX, float lightY, float lightR, float lightG, float lightB,
                            float lightStrength) {
        GL20.glUniform3f(tintLocation, tintR, tintG, tintB);
        GL20.glUniform1f(tintLiftLocation, tintLift);
        GL20.glUniform1f(nightLocation, night);
        GL20.glUniform2f(lightCenterLocation, lightX, lightY);
        GL20.glUniform3f(lightColorLocation, lightR, lightG, lightB);
        GL20.glUniform1f(lightStrengthLocation, lightStrength);
    }

    public void setFeatures(int features) {
        GL20.glUniform1i(featuresLocation, features);
    }

    /** Uploads the active ripples; extra entries are dropped, not queued. */
    public void setRipples(float[] packed, int count) {
        int capped = Math.max(0, Math.min(MAX_RIPPLES, count));
        GL20.glUniform1i(rippleCountLocation, capped);
        int floats = MAX_RIPPLES * 4;
        if (packed != null && packed.length >= floats) {
            GL20.glUniform4fv(ripplesLocation, java.util.Arrays.copyOf(packed, floats));
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
            throw new IllegalStateException("Liquid shader compile failed: "
                    + GL20.glGetShaderInfoLog(shader));
        }
        return shader;
    }
}
