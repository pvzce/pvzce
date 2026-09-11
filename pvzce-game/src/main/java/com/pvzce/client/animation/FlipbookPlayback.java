package com.pvzce.client.animation;

import com.pvzce.api.util.Identifier;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.api.Animatable;

/** Runtime playback for a flipbook clip. */
public final class FlipbookPlayback extends AnimationPlayback {
    private final FlipbookFile flipbookFile;
    private final FlipbookClip flipbookClip;
    private final Identifier previousFrame;

    public FlipbookPlayback(AnimationManager manager, Animatable target, FlipbookFile file, FlipbookClip clip,
                            String requestedState, String activeName, AnimationPlayback previous, double now) {
        super(manager, target, file, clip, requestedState, activeName, previous, now);
        this.flipbookFile = file;
        this.flipbookClip = clip;
        this.previousFrame = previous instanceof FlipbookPlayback old ? old.currentFrame(now) : null;
    }

    public Identifier currentFrame(double now) {
        return flipbookClip.frame(flipbookClip.frameIndex(localTime(now)));
    }

    @Override
    public void render(PvzceClient client, float anchorX, float anchorY, float baseZ,
                       float xScale) {
        if (stopped) {
            return;
        }
        double now = manager.now();
        Identifier frame = currentFrame(now);
        if (frame == null) {
            return;
        }
        float scaleX = Math.max(0.0001F, xScale);
        float width = flipbookFile.sizeX() * scaleX;
        float height = flipbookFile.sizeY();
        float x = anchorX - width * flipbookFile.anchorX();
        float y = anchorY - height * flipbookFile.anchorY();

        float blend = transitionBlend(now);
        if (previousFrame != null && blend < 1F) {
            client.drawTextureRegion(previousFrame, 0F, 0F, 1F, 1F, x, y, width, height, baseZ,
                    1F, 1F, 1F, 1F - blend);
        }
        client.drawTextureRegion(frame, 0F, 0F, 1F, 1F, x, y, width, height, baseZ,
                1F, 1F, 1F, blend);
    }

    @Override
    public float[] eventPosition(String locator) {
        float[] anchor = manager.anchor(target);
        float height = flipbookFile.sizeY();
        float centerY = anchor[1] - height * flipbookFile.anchorY() + height * 0.5F;
        return new float[]{anchor[0], centerY};
    }
}
