package com.pvzce.client.gui.components;

import com.pvzce.api.util.Identifier;
import com.pvzce.client.PvzceClient;

/**
 * Shared nine-slice renderer for textured GUI widgets.
 *
 * <p>Both {@link Button} and {@link Dialog} draw their frames through this
 * helper, so the sliced PNGs under {@code textures/gui/...} are never
 * hard-coded inside individual screens. Missing textures automatically fall
 * back to solid quads through {@link PvzceClient#drawTexture}.</p>
 */
public final class NinePatch {
    /** Native slice sizes for the button textures in {@code textures/gui/button}. */
    public static final float BUTTON_NATIVE_HEIGHT = 46F;
    public static final float BUTTON_LEFT = 36F;
    public static final float BUTTON_RIGHT = 35F;

    /** Native slice sizes for the dialog textures in {@code textures/gui/dialog}. */
    public static final float DIALOG_LEFT = 107F;
    public static final float DIALOG_RIGHT = 120F;
    public static final float DIALOG_TOP = 97F;
    public static final float DIALOG_BOTTOM = 112F;
    public static final float DIALOG_BIG_BOTTOM = 150F;

    private NinePatch() {
    }

    /**
     * Draws a three-slice horizontal frame (left cap + stretched middle +
     * right cap). Edge widths are given in pixels; if the target is narrower
     * than the two caps, both caps shrink proportionally.
     */
    public static void drawThreeSlice(PvzceClient client,
                                      Identifier left, Identifier middle, Identifier right,
                                      float x, float y, float width, float height, float z,
                                      float leftWidth, float rightWidth,
                                      float r, float g, float b, float a) {
        float leftW = Math.max(0F, leftWidth);
        float rightW = Math.max(0F, rightWidth);
        float capWidth = leftW + rightW;
        if (width < capWidth && capWidth > 0F) {
            float factor = width / capWidth;
            leftW *= factor;
            rightW *= factor;
        }
        float middleW = Math.max(0F, width - leftW - rightW);
        client.drawTexture(left, x, y, leftW, height, z, r, g, b, a);
        client.drawTexture(middle, x + leftW, y, middleW, height, z, r, g, b, a);
        client.drawTexture(right, x + leftW + middleW, y, rightW, height, z, r, g, b, a);
    }

    /**
     * Draws a full nine-slice panel. The four border thicknesses are kept at
     * their natural size and the center slices stretch; if the target is too
     * small, the borders shrink uniformly instead of overlapping.
     */
    public static void drawNineSlice(PvzceClient client,
                                     Identifier topLeft, Identifier top, Identifier topRight,
                                     Identifier left, Identifier center, Identifier right,
                                     Identifier bottomLeft, Identifier bottom, Identifier bottomRight,
                                     float x, float y, float width, float height, float z,
                                     float leftWidth, float rightWidth, float topHeight, float bottomHeight,
                                     float r, float g, float b, float a) {
        float leftW = Math.max(0F, leftWidth);
        float rightW = Math.max(0F, rightWidth);
        float topH = Math.max(0F, topHeight);
        float bottomH = Math.max(0F, bottomHeight);

        float borderWidth = leftW + rightW;
        if (width < borderWidth && borderWidth > 0F) {
            float factor = width / borderWidth;
            leftW *= factor;
            rightW *= factor;
        }
        float borderHeight = topH + bottomH;
        if (height < borderHeight && borderHeight > 0F) {
            float factor = height / borderHeight;
            topH *= factor;
            bottomH *= factor;
        }

        float middleW = Math.max(0F, width - leftW - rightW);
        float middleH = Math.max(0F, height - topH - bottomH);
        float bottomY = y;
        float middleY = y + bottomH;
        float topY = y + bottomH + middleH;
        float rightX = x + leftW + middleW;

        // Bottom row (from left to right).
        client.drawTexture(bottomLeft, x, bottomY, leftW, bottomH, z, r, g, b, a);
        client.drawTexture(bottom, x + leftW, bottomY, middleW, bottomH, z, r, g, b, a);
        client.drawTexture(bottomRight, rightX, bottomY, rightW, bottomH, z, r, g, b, a);
        // Middle row.
        client.drawTexture(left, x, middleY, leftW, middleH, z, r, g, b, a);
        client.drawTexture(center, x + leftW, middleY, middleW, middleH, z, r, g, b, a);
        client.drawTexture(right, rightX, middleY, rightW, middleH, z, r, g, b, a);
        // Top row.
        client.drawTexture(topLeft, x, topY, leftW, topH, z, r, g, b, a);
        client.drawTexture(top, x + leftW, topY, middleW, topH, z, r, g, b, a);
        client.drawTexture(topRight, rightX, topY, rightW, topH, z, r, g, b, a);
    }

    /**
     * Nine-slice a single texture atlas (for example the seed-chooser wood
     * panel). The border slices keep their native aspect and only the centre
     * is stretched, so enlarging a panel does not distort its carved frame.
     */
    public static void drawNineSlice(PvzceClient client, Identifier texture,
                                     float x, float y, float width, float height, float z,
                                     float textureWidth, float textureHeight,
                                     float leftWidth, float rightWidth,
                                     float topHeight, float bottomHeight,
                                     float r, float g, float b, float a) {
        float texW = Math.max(1F, textureWidth);
        float texH = Math.max(1F, textureHeight);
        float leftW = Math.max(0F, leftWidth);
        float rightW = Math.max(0F, rightWidth);
        float topH = Math.max(0F, topHeight);
        float bottomH = Math.max(0F, bottomHeight);

        float borderWidth = leftW + rightW;
        if (width < borderWidth && borderWidth > 0F) {
            float factor = width / borderWidth;
            leftW *= factor;
            rightW *= factor;
        }
        float borderHeight = topH + bottomH;
        if (height < borderHeight && borderHeight > 0F) {
            float factor = height / borderHeight;
            topH *= factor;
            bottomH *= factor;
        }

        float middleW = Math.max(0F, width - leftW - rightW);
        float middleH = Math.max(0F, height - topH - bottomH);
        float bottomY = y;
        float middleY = y + bottomH;
        float topY = y + bottomH + middleH;
        float rightX = x + leftW + middleW;

        float uLeft = leftW / texW;
        float uRight = 1F - rightW / texW;
        float vBottom = bottomH / texH;
        float vTop = 1F - topH / texH;

        // Bottom row (from left to right).
        client.drawTextureRegion(texture, 0F, 0F, uLeft, vBottom,
                x, bottomY, leftW, bottomH, z, r, g, b, a);
        client.drawTextureRegion(texture, uLeft, 0F, uRight, vBottom,
                x + leftW, bottomY, middleW, bottomH, z, r, g, b, a);
        client.drawTextureRegion(texture, uRight, 0F, 1F, vBottom,
                rightX, bottomY, rightW, bottomH, z, r, g, b, a);
        // Middle row.
        client.drawTextureRegion(texture, 0F, vBottom, uLeft, vTop,
                x, middleY, leftW, middleH, z, r, g, b, a);
        client.drawTextureRegion(texture, uLeft, vBottom, uRight, vTop,
                x + leftW, middleY, middleW, middleH, z, r, g, b, a);
        client.drawTextureRegion(texture, uRight, vBottom, 1F, vTop,
                rightX, middleY, rightW, middleH, z, r, g, b, a);
        // Top row.
        client.drawTextureRegion(texture, 0F, vTop, uLeft, 1F,
                x, topY, leftW, topH, z, r, g, b, a);
        client.drawTextureRegion(texture, uLeft, vTop, uRight, 1F,
                x + leftW, topY, middleW, topH, z, r, g, b, a);
        client.drawTextureRegion(texture, uRight, vTop, 1F, 1F,
                rightX, topY, rightW, topH, z, r, g, b, a);
    }

    /**
     * Nine-slice a single texture while tiling (instead of stretching) the
     * middle slices at a fixed texel scale. Use this for decorative wood/stone
     * panels whose grain and bevel should keep their original proportions even
     * when the target rectangle is wider or taller than the source image.
     */
    public static void drawNineSliceTiled(PvzceClient client, Identifier texture,
                                          float x, float y, float width, float height, float z,
                                          float textureWidth, float textureHeight,
                                          float leftWidth, float rightWidth,
                                          float topHeight, float bottomHeight,
                                          float scale, float r, float g, float b, float a) {
        float texW = Math.max(1F, textureWidth);
        float texH = Math.max(1F, textureHeight);
        float pixelScale = Math.max(0.001F, scale);
        float left = Math.max(0F, leftWidth) * pixelScale;
        float right = Math.max(0F, rightWidth) * pixelScale;
        float top = Math.max(0F, topHeight) * pixelScale;
        float bottom = Math.max(0F, bottomHeight) * pixelScale;

        float bordersW = left + right;
        if (width < bordersW && bordersW > 0F) {
            float factor = width / bordersW;
            left *= factor;
            right *= factor;
        }
        float bordersH = top + bottom;
        if (height < bordersH && bordersH > 0F) {
            float factor = height / bordersH;
            top *= factor;
            bottom *= factor;
        }

        float middleW = Math.max(0F, width - left - right);
        float middleH = Math.max(0F, height - top - bottom);
        float rightX = x + left + middleW;
        float topY = y + bottom + middleH;

        // Corners.
        client.drawTextureRegion(texture, 0F, 0F,
                leftWidth / texW, bottomHeight / texH,
                x, y, left, bottom, z, r, g, b, a);
        client.drawTextureRegion(texture, 1F - rightWidth / texW, 0F,
                1F, bottomHeight / texH,
                rightX, y, right, bottom, z, r, g, b, a);
        client.drawTextureRegion(texture, 0F, 1F - topHeight / texH,
                leftWidth / texW, 1F,
                x, topY, left, top, z, r, g, b, a);
        client.drawTextureRegion(texture, 1F - rightWidth / texW, 1F - topHeight / texH,
                1F, 1F,
                rightX, topY, right, top, z, r, g, b, a);

        // Stretch-free tiled middle slices.
        drawTiledHorizontal(client, texture, leftWidth / texW, 0F,
                1F - rightWidth / texW, bottomHeight / texH,
                x + left, y, middleW, bottom, z, pixelScale, texW, r, g, b, a);
        drawTiledHorizontal(client, texture, leftWidth / texW, 1F - topHeight / texH,
                1F - rightWidth / texW, 1F,
                x + left, topY, middleW, top, z, pixelScale, texW, r, g, b, a);
        drawTiledVertical(client, texture, 0F, bottomHeight / texH,
                leftWidth / texW, 1F - topHeight / texH,
                x, y + bottom, left, middleH, z, pixelScale, texH, r, g, b, a);
        drawTiledVertical(client, texture, 1F - rightWidth / texW, bottomHeight / texH,
                1F, 1F - topHeight / texH,
                rightX, y + bottom, right, middleH, z, pixelScale, texH, r, g, b, a);
        drawTiledBoth(client, texture, leftWidth / texW, bottomHeight / texH,
                1F - rightWidth / texW, 1F - topHeight / texH,
                x + left, y + bottom, middleW, middleH, z, pixelScale, texW, texH, r, g, b, a);
    }

    private static void drawTiledHorizontal(PvzceClient client, Identifier texture,
                                            float u0, float v0, float u1, float v1,
                                            float x, float y, float width, float height, float z,
                                            float scale, float texW,
                                            float r, float g, float b, float a) {
        float segment = Math.max(0.001F, (u1 - u0) * texW * scale);
        float cursor = x;
        float remaining = width;
        while (remaining > 0.01F) {
            float drawWidth = Math.min(segment, remaining);
            float uEnd = u0 + (drawWidth / scale) / texW;
            client.drawTextureRegion(texture, u0, v0, uEnd, v1,
                    cursor, y, drawWidth, height, z, r, g, b, a);
            cursor += drawWidth;
            remaining -= drawWidth;
        }
    }

    private static void drawTiledVertical(PvzceClient client, Identifier texture,
                                          float u0, float v0, float u1, float v1,
                                          float x, float y, float width, float height, float z,
                                          float scale, float texH,
                                          float r, float g, float b, float a) {
        float segment = Math.max(0.001F, (v1 - v0) * texH * scale);
        float cursor = y;
        float remaining = height;
        while (remaining > 0.01F) {
            float drawHeight = Math.min(segment, remaining);
            float vEnd = v0 + (drawHeight / scale) / texH;
            client.drawTextureRegion(texture, u0, v0, u1, vEnd,
                    x, cursor, width, drawHeight, z, r, g, b, a);
            cursor += drawHeight;
            remaining -= drawHeight;
        }
    }

    private static void drawTiledBoth(PvzceClient client, Identifier texture,
                                      float u0, float v0, float u1, float v1,
                                      float x, float y, float width, float height, float z,
                                      float scale, float texW, float texH,
                                      float r, float g, float b, float a) {
        float segmentW = Math.max(0.001F, (u1 - u0) * texW * scale);
        float segmentH = Math.max(0.001F, (v1 - v0) * texH * scale);
        float cursorY = y;
        float remainingY = height;
        while (remainingY > 0.01F) {
            float drawHeight = Math.min(segmentH, remainingY);
            float vEnd = v0 + (drawHeight / scale) / texH;
            float cursorX = x;
            float remainingX = width;
            while (remainingX > 0.01F) {
                float drawWidth = Math.min(segmentW, remainingX);
                float uEnd = u0 + (drawWidth / scale) / texW;
                client.drawTextureRegion(texture, u0, v0, uEnd, vEnd,
                        cursorX, cursorY, drawWidth, drawHeight, z, r, g, b, a);
                cursorX += drawWidth;
                remainingX -= drawWidth;
            }
            cursorY += drawHeight;
            remainingY -= drawHeight;
        }
    }
}
