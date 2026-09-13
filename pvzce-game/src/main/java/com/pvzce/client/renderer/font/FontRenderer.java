package com.pvzce.client.renderer.font;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pvzce.api.util.Identifier;
import com.pvzce.client.renderer.SpriteRenderer;
import com.pvzce.client.renderer.sprite.Sprite;
import com.pvzce.client.renderer.texture.Texture;
import com.pvzce.client.renderer.texture.TextureManager;
import com.pvzce.common.resource.PackResource;
import com.pvzce.common.resource.PvzceResourceManager;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Bitmap font: glyph atlas PNG + JSON bounds. Visual bounds are measured from
 * the atlas so proportional Latin glyphs neither overlap horizontally nor
 * wobble vertically; all glyphs share one Latin baseline. Atlas pixels are
 * converted to logical GUI pixels via {@link #atlasToGui}, so a high-resolution
 * baked atlas does not draw oversized text.
 */
public final class FontRenderer {
    private static final Identifier FONT_TEXTURE = Identifier.withDefaultNamespace("font/ui");
    private static final Identifier FONT_META = Identifier.withDefaultNamespace("font/ui.json");

    /**
     * Largest logical line height (in GUI pixels) produced by a draw scale of
     * 1. Glyph atlases are often baked much larger than the UI needs (the
     * bundled atlas uses 128px cells) so they stay crisp when scaled up;
     * {@link #atlasToGui} scales such atlases back down to this size while
     * leaving smaller atlases untouched.
     */
    private static final float BASE_LINE_HEIGHT = 18F;

    private record Glyph(char ch, int x, int y, int width, int height, int advance,
                         int visualLeft, int visualTop, int visualRight, int visualBottom, int baseline) {
    }

    private final Map<Character, Glyph> glyphs = new HashMap<>();
    private final TextureManager textures;
    /** Atlas-native line height in pixels, straight from {@code ui.json}. */
    private int lineHeight = Math.round(BASE_LINE_HEIGHT);
    /** Multiplier from atlas pixels to logical GUI pixels. */
    private float atlasToGui = 1F;

    public FontRenderer(TextureManager textures, PvzceResourceManager resources) {
        this.textures = textures;
        try {
            var metaResource = resources.getAsset(FONT_META);
            if (metaResource.isPresent()) {
                loadMeta(metaResource.get());
                measureVisualBounds(resources);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to load font metadata", e);
        }
        atlasToGui = lineHeight > BASE_LINE_HEIGHT ? BASE_LINE_HEIGHT / lineHeight : 1F;
    }

    private void loadMeta(PackResource resource) {
        JsonObject root = JsonParser.parseString(resource.readString()).getAsJsonObject();
        lineHeight = root.has("line_height") ? root.get("line_height").getAsInt() : Math.round(BASE_LINE_HEIGHT);
        JsonArray array = root.getAsJsonArray("glyphs");
        for (JsonElement element : array) {
            JsonObject glyph = element.getAsJsonObject();
            String ch = glyph.get("char").getAsString();
            glyphs.put(ch.charAt(0), new Glyph(ch.charAt(0),
                    glyph.get("x").getAsInt(),
                    glyph.get("y").getAsInt(),
                    glyph.get("w").getAsInt(),
                    glyph.get("h").getAsInt(),
                    glyph.get("advance").getAsInt(),
                    -1, -1, -1, -1, -1));
        }
    }

    private void measureVisualBounds(PvzceResourceManager resources) throws IOException {
        var textureResource = resources.getAsset(FONT_TEXTURE);
        if (textureResource.isEmpty()) {
            textureResource = resources.getResource("assets/" + FONT_TEXTURE.toPath() + ".png");
        }
        if (textureResource.isEmpty()) {
            return;
        }
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(textureResource.get().bytes()));
        if (image == null) {
            return;
        }

        long bottomSum = 0;
        int latinCount = 0;
        for (Glyph glyph : glyphs.values()) {
            if (glyph.ch < 'A' || glyph.ch > 'Z') {
                continue;
            }
            int bottom = findBottom(image, glyph);
            if (bottom >= 0) {
                bottomSum += bottom;
                latinCount++;
            }
        }
        int baseline = latinCount == 0 ? Math.round(image.getHeight() * 0.79F) : (int) Math.round(bottomSum / (double) latinCount) + 1;

        for (Map.Entry<Character, Glyph> entry : glyphs.entrySet()) {
            Glyph glyph = entry.getValue();
            int left = -1;
            int top = -1;
            int right = -1;
            int bottom = -1;
            for (int py = 0; py < glyph.height; py++) {
                for (int px = 0; px < glyph.width; px++) {
                    int argb = image.getRGB(glyph.x + px, glyph.y + py);
                    if (((argb >>> 24) & 0xFF) > 8) {
                        if (top < 0) {
                            top = py;
                        }
                        bottom = py;
                        if (left < 0 || px < left) {
                            left = px;
                        }
                        if (px > right) {
                            right = px;
                        }
                    }
                }
            }
            entry.setValue(new Glyph(glyph.ch, glyph.x, glyph.y, glyph.width, glyph.height, glyph.advance,
                    left, top, right, bottom, baseline));
        }
    }

    private static int findBottom(BufferedImage image, Glyph glyph) {
        for (int py = glyph.height - 1; py >= 0; py--) {
            for (int px = 0; px < glyph.width; px++) {
                if (((image.getRGB(glyph.x + px, glyph.y + py) >>> 24) & 0xFF) > 8) {
                    return py;
                }
            }
        }
        return -1;
    }

    public float draw(String text, float x, float y, float scale, float r, float g, float b, float a) {
        float cursor = x;
        float unit = scale * atlasToGui;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\n') {
                y -= lineHeight * unit;
                cursor = x;
                continue;
            }
            Glyph glyph = glyphs.get(ch);
            if (glyph == null) {
                glyph = glyphs.get('?');
            }
            if (glyph != null) {
                Texture texture = textures.getOrLoad(FONT_TEXTURE);
                float inset;
                float drawWidth;
                float drawHeight;
                float drawY;
                float u0;
                float v1;
                float u1;
                float v0;
                if (glyph.visualLeft >= 0 && glyph.visualRight >= 0
                        && glyph.visualTop >= 0 && glyph.visualBottom >= 0) {
                    inset = glyph.visualLeft;
                    drawWidth = Math.max(1, glyph.visualRight - glyph.visualLeft + 1);
                    drawHeight = Math.max(1, glyph.visualBottom - glyph.visualTop + 1);
                    drawY = y + (glyph.baseline - glyph.visualBottom) * unit;
                    u0 = (glyph.x + inset) / (float) texture.width();
                    v1 = 1F - (glyph.y + glyph.visualTop) / (float) texture.height();
                    u1 = (glyph.x + inset + drawWidth) / (float) texture.width();
                    v0 = 1F - (glyph.y + glyph.visualBottom + 1) / (float) texture.height();
                } else {
                    // Fallback for atlases without readable pixels: center-crop
                    // fixed cells to the advance width.
                    float cellWidth = glyph.width;
                    drawWidth = cellWidth;
                    inset = 0F;
                    if (cellWidth > glyph.advance * 1.25F) {
                        drawWidth = Math.max(1, Math.min(glyph.advance, cellWidth));
                        inset = (cellWidth - drawWidth) / 2F;
                    }
                    drawHeight = glyph.height;
                    drawY = y;
                    u0 = (glyph.x + inset) / (float) texture.width();
                    v1 = 1F - glyph.y / (float) texture.height();
                    u1 = (glyph.x + inset + drawWidth) / (float) texture.width();
                    v0 = 1F - (glyph.y + drawHeight) / (float) texture.height();
                }
                Sprite sprite = new Sprite(texture, u0, v0, u1, v1);
                SpriteRenderer.textured(sprite, cursor, drawY, drawWidth * unit, drawHeight * unit, 0, r, g, b, a);
                cursor += glyph.advance * unit;
            } else {
                cursor += 10 * scale;
            }
        }
        return cursor;
    }

    public float width(String text, float scale) {
        float width = 0;
        float maxWidth = 0;
        float unit = scale * atlasToGui;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\n') {
                maxWidth = Math.max(maxWidth, width);
                width = 0;
                continue;
            }
            Glyph glyph = glyphs.get(ch);
            width += glyph != null ? glyph.advance * unit : 10 * scale;
        }
        return Math.max(maxWidth, width);
    }

    public int lineHeight(float scale) {
        return Math.round(lineHeight * scale * atlasToGui);
    }

    /**
     * Breaks text into lines that each fit {@code maxWidth}, honouring explicit newlines.
     *
     * <p>Breaks between characters rather than at spaces: this is a CJK UI, where a
     * sentence has no spaces to break at, and a Latin word that overflows is still
     * better split than left running off the panel. A single character wider than the
     * limit gets its own line rather than an empty one before it.
     *
     * <p>Shared because two callers need the same answer to "what are the lines":
     * {@code ModsScreen} draws them into a band, and {@code DialogueOverlay} measures
     * them to size a speech bubble. Measuring and drawing used to be one function, so
     * anything that only wanted the lines had to write its own wrapper.
     */
    public java.util.List<String> wrapLines(String text, float maxWidth, float scale) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        if (text == null) {
            return lines;
        }
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\n') {
                lines.add(line.toString());
                line.setLength(0);
                continue;
            }
            int before = line.length();
            line.append(ch);
            if (before > 0 && width(line.toString(), scale) > maxWidth) {
                line.setLength(before);
                lines.add(line.toString());
                line.setLength(0);
                line.append(ch);
            }
        }
        lines.add(line.toString());
        return lines;
    }
}
