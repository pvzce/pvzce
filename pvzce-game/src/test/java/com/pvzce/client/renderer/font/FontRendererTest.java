package com.pvzce.client.renderer.font;

import com.pvzce.client.renderer.texture.TextureManager;
import com.pvzce.common.resource.PvzceResourceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Font metrics are stored in atlas pixels; the renderer must convert them to
 * logical GUI pixels so a large baked atlas (128px cells) does not draw huge
 * text in-game.
 */
class FontRendererTest {
    private static final int CELL = 128;

    @Test
    void oversizedAtlasIsScaledDownToUiLineHeight(@TempDir Path gameDir) throws Exception {
        FontRenderer font = loadAtlas(gameDir, 128, 76, 120);

        assertEquals(18, font.lineHeight(1F), "scale 1 must stay at the UI line height");
        assertEquals(76F * 18F / CELL, font.width("A", 1F), 0.0001F);
        assertEquals(120F * 18F / CELL, font.width("中", 1F), 0.0001F);
        assertEquals(9, font.lineHeight(0.5F));
    }

    @Test
    void smallAtlasKeepsItsNativeSize(@TempDir Path gameDir) throws Exception {
        FontRenderer font = loadAtlas(gameDir, 9, 5, 9);

        assertEquals(9, font.lineHeight(1F));
        assertEquals(5F, font.width("A", 1F), 0.0001F);
        assertEquals(9F, font.width("中", 1F), 0.0001F);
    }

    /** Builds a throwaway pack with a two-cell atlas ('A' and '中') and loads it. */
    private static FontRenderer loadAtlas(Path gameDir, int lineHeight, int latinAdvance, int cjkAdvance)
            throws Exception {
        Path fontDir = gameDir.resolve("resourcepacks/test/assets/pvzce/font");
        Files.createDirectories(fontDir);

        BufferedImage image = new BufferedImage(CELL * 2, CELL, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(16, 32, 96, 64);   // Latin glyph ink
            graphics.fillRect(CELL + 16, 16, 96, 96); // CJK glyph ink
        } finally {
            graphics.dispose();
        }
        ImageIO.write(image, "png", fontDir.resolve("ui.png").toFile());
        Files.writeString(fontDir.resolve("ui.json"), """
                {"line_height":%d,"glyphs":[
                  {"char":"A","x":0,"y":0,"w":128,"h":128,"advance":%d},
                  {"char":"中","x":128,"y":0,"w":128,"h":128,"advance":%d}
                ]}
                """.formatted(lineHeight, latinAdvance, cjkAdvance));

        PvzceResourceManager resources = new PvzceResourceManager(Thread.currentThread().getContextClassLoader());
        resources.init(gameDir);
        return new FontRenderer(new TextureManager(resources), resources);
    }
}
