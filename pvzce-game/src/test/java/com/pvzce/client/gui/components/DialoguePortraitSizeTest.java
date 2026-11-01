package com.pvzce.client.gui.components;

import com.pvzce.testutil.SourceTree;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialoguePortraitSizeTest {
    @Test
    void builtInPortraitsFitTheResolutionBudget() throws Exception {
        Path portraits = SourceTree.root().resolve(
                "pvzce-game/src/main/resources/assets/pvzce/textures/gui/dialogue");
        int maxHeight = Integer.getInteger("pvzce.portraitHeight");
        try (var paths = Files.walk(portraits)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(p -> !p.getParent().equals(portraits))
                    .filter(p -> p.toString().endsWith(".png")).toList()) {
                var image = ImageIO.read(path.toFile());
                assertNotNull(image, "Cannot read portrait: " + path);
                assertTrue(image.getHeight() <= maxHeight,
                        () -> path + " exceeds " + maxHeight + "px; run python3 tools/resize_dialogue_portraits.py");
            }
        }
    }
}
