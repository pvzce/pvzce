package com.pvzce.client.renderer;

import com.pvzce.common.util.MathUtil;
/**
 * The board's day/night lighting as pure numbers, for any caller that has to draw a board.
 *
 * <p>Extracted from {@code PvzceClient.applyTimeOfDayShader} when the seed chooser needed the
 * same look: the chooser draws a level's lawn before the level exists, so it has no clock and
 * no {@code LevelInitS2C} to read - it has the level's file, and this turns "what the clock
 * would say" into the tint, lift and sun/moon glow the shader takes. Two copies of these
 * numbers would drift the moment one of them was tuned, which is exactly what the chooser is
 * for: showing the board the player is about to get.
 *
 * <p>Coordinates are world cells. A screen that draws in its own space (the chooser draws in
 * GUI pixels) scales the sun centre and radius into that space; only the glow depends on the
 * space, the tint and lift do not.
 */
public final class TimeOfDayLighting {
    /**
     * The shader's inputs for one moment of one level.
     *
     * @param nightBlend 0 in full day, 1 in full night - also what the shadows and the sun
     *                   drops' colour are crossfaded by
     */
    public record Lighting(float tintR, float tintG, float tintB, float lift,
                           float sunX, float sunY, float sunRadius,
                           float sunR, float sunG, float sunB, float strength,
                           float nightBlend) {
    }

    private TimeOfDayLighting() {
    }

    /**
     * The lighting at {@code dayTicks} of a level with these lengths.
     *
     * <p>A level with no day at all (`dayLength <= 0` with a positive night) is night from its
     * first tick: {@code DayNightCycle} answers 1 for the blend and the night parameters win
     * outright.
     */
    public static Lighting compute(float dayTicks, int dayLength, int nightLength,
                                   int worldWidth, int worldHeight) {
        int w = Math.max(1, worldWidth);
        int h = Math.max(1, worldHeight);
        float nightBlend = com.pvzce.common.level.DayNightCycle.nightBlend(
                (long) Math.floor(dayTicks), dayLength, nightLength);

        float dayTintR;
        float dayTintG;
        float dayTintB;
        float dayLift;
        float daySunX;
        float daySunY;
        float daySunR;
        float daySunG;
        float daySunB;
        float dayStrength;
        float dayRadius = Math.max(w, h) * 0.9F;

        if (dayLength <= 0) {
            // Permanent day: sun directly overhead, neutral warm light.
            dayTintR = 1.04F;
            dayTintG = 1.01F;
            dayTintB = 0.94F;
            dayLift = 0.015F;
            dayStrength = 0.20F;
            daySunX = w * 0.5F;
            daySunY = h * 3F;
            daySunR = 1F;
            daySunG = 1F;
            daySunB = 0.9F;
        } else {
            int cycle = Math.max(1, dayLength + Math.max(0, nightLength));
            long dayPos = Math.floorMod((long) Math.floor(dayTicks), cycle);
            int day = Math.max(1, dayLength);
            int night = Math.max(0, nightLength);
            int window = Math.max(1, Math.min(120, Math.min(day, Math.max(1, night)) / 4));
            // Keep the setting sun in the west through dusk; use the pre-sunrise
            // east position for the final dawn blend.
            float progress = dayPos <= day + window
                    ? Math.min(1F, dayPos / (float) day)
                    : 0F;
            daySunX = w * (0.05F + 0.9F * progress); // east(left) -> west(right)
            daySunY = h * (0.5F + 1.6F * Math.abs((float) Math.sin(Math.PI * progress)));
            dayStrength = 0.22F + 0.16F * (float) Math.sin(Math.PI * progress);
            float morning = Math.max(0F, 1F - progress * 2F);
            float evening = Math.max(0F, (progress - 0.5F) * 2F);
            dayTintR = 1F + 0.08F * morning + 0.10F * evening;
            dayTintG = 1F + 0.02F * morning - 0.15F * evening;
            dayTintB = 1F - 0.10F * morning - 0.30F * evening;
            dayLift = 0.01F;
            daySunR = 1F;
            daySunG = 0.75F + 0.25F * morning - 0.15F * evening;
            daySunB = 0.45F + 0.25F * morning;
        }

        // Night parameters: PvZ-style cool blue moonlight.
        //
        // Blue-led but *not* dark: the original's night levels are the same lawn under a
        // cold wash, and a lawn the player cannot read is not atmosphere, it is a handicap -
        // every plant on it is a dark shape, and the one thing the player is doing at night
        // is deciding what to plant where. Red comes down the most, blue stays at full, and
        // the small positive lift keeps the darkest grass out of pure black.
        //
        // Entities are lifted back out of this tint on their own (see
        // EntityVisuals#NIGHT_LIFT), so the lawn and the street wear all of it and the plants
        // and zombies wear about half.
        float nightTintR = 0.62F;
        float nightTintG = 0.72F;
        float nightTintB = 1.00F;
        float nightLift = 0.02F;
        float nightStrength = 0.34F;
        float nightSunX = w * 0.72F; // moon above the western side
        float nightSunY = h * 2.1F;
        float nightSunR = 0.68F;
        float nightSunG = 0.80F;
        float nightSunB = 1.0F;
        float nightRadius = Math.max(w, h) * 1.05F;

        return new Lighting(
                MathUtil.lerp(dayTintR, nightTintR, nightBlend),
                MathUtil.lerp(dayTintG, nightTintG, nightBlend),
                MathUtil.lerp(dayTintB, nightTintB, nightBlend),
                MathUtil.lerp(dayLift, nightLift, nightBlend),
                MathUtil.lerp(daySunX, nightSunX, nightBlend),
                MathUtil.lerp(daySunY, nightSunY, nightBlend),
                MathUtil.lerp(dayRadius, nightRadius, nightBlend),
                MathUtil.lerp(daySunR, nightSunR, nightBlend),
                MathUtil.lerp(daySunG, nightSunG, nightBlend),
                MathUtil.lerp(daySunB, nightSunB, nightBlend),
                MathUtil.lerp(dayStrength, nightStrength, nightBlend),
                nightBlend);
    }

    /**
     * The same lighting with its glow moved into another coordinate space.
     *
     * <p>Used by a screen that draws the board in its own pixels: the tint is a multiply and
     * does not care, but the sun's centre and radius are distances, so they have to be scaled
     * or the glow lands off screen (which is what a screen can also rely on deliberately - see
     * the seed chooser's zombie preview, drawn in its own little world).
     */
    public static Lighting inRect(Lighting lighting, float x, float y,
                                  float cellWidth, float cellHeight) {
        float scale = Math.max(0.0001F, (cellWidth + cellHeight) / 2F);
        return new Lighting(lighting.tintR(), lighting.tintG(), lighting.tintB(), lighting.lift(),
                x + lighting.sunX() * cellWidth, y + lighting.sunY() * cellHeight,
                lighting.sunRadius() * scale,
                lighting.sunR(), lighting.sunG(), lighting.sunB(), lighting.strength(),
                lighting.nightBlend());
    }

    /** The lighting with no glow at all: the tint and lift of a level, nothing positional. */
    public static Lighting withoutGlow(Lighting lighting) {
        return new Lighting(lighting.tintR(), lighting.tintG(), lighting.tintB(), lighting.lift(),
                0F, 0F, 0F, lighting.sunR(), lighting.sunG(), lighting.sunB(), 0F,
                lighting.nightBlend());
    }

}
