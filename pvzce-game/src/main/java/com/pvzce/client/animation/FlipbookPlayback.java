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
                       float xScale, float yScale) {
        if (stopped) {
            return;
        }
        double now = manager.now();
        Identifier frame = currentFrame(now);
        if (frame == null) {
            return;
        }
        float width = flipbookFile.sizeX() * Math.max(0.0001F, xScale);
        float height = flipbookFile.sizeY() * Math.max(0.0001F, yScale);
        float x = anchorX - width * flipbookFile.anchorX();
        float y = anchorY - height * flipbookFile.anchorY();

        // A cross-fade between two frames, which only makes sense when the clip asked for
        // one: both frames are drawn translucent and their alphas sum to 1, so with no
        // transition declared this would be the new frame at alpha 1 - and a needless second
        // draw of the old one. The controller path has no equivalent and needs none: it
        // interpolates the pose itself.
        if (transitionDuration > 0F) {
            float fade = transitionBlend(now);
            if (previousFrame != null && fade < 1F) {
                client.drawTextureRegion(previousFrame, 0F, 0F, 1F, 1F, x, y, width, height, baseZ,
                        1F, 1F, 1F, 1F - fade);
            }
            client.drawTextureRegion(frame, 0F, 0F, 1F, 1F, x, y, width, height, baseZ,
                    1F, 1F, 1F, fade);
            return;
        }
        client.drawTextureRegion(frame, 0F, 0F, 1F, 1F, x, y, width, height, baseZ,
                1F, 1F, 1F, 1F);
    }

    @Override
    public float[] eventPosition(String locator) {
        float[] anchor = manager.anchor(target);
        float height = flipbookFile.sizeY();
        float centerY = anchor[1] - height * flipbookFile.anchorY() + height * 0.5F;
        return new float[]{anchor[0], centerY};
    }
}
