package com.pvzce.client.animation;

import com.pvzce.api.util.Identifier;

import java.util.List;
import java.util.Map;

/** Flipbook resource: named frame sequences sharing one on-screen rectangle. */
public record FlipbookFile(
        Map<String, FlipbookClip> clips,
        float sizeX,
        float sizeY,
        float anchorX,
        float anchorY
) implements AnimationFile {
    public FlipbookFile {
        clips = Map.copyOf(clips);
        sizeX = sizeX <= 0F ? 1F : sizeX;
        sizeY = sizeY <= 0F ? 1F : sizeY;
    }

    @Override
    public AnimationType type() {
        return AnimationType.FLIPBOOK;
    }

    @Override
    public Map<String, FlipbookClip> clips() {
        return clips;
    }

    /** Frame rectangle bottom-left corner for a world anchor. */
    public float frameX(float anchorX, float width) {
        return anchorX - width * this.anchorX;
    }

    public float frameY(float anchorY, float height) {
        return anchorY - height * this.anchorY;
    }
}
