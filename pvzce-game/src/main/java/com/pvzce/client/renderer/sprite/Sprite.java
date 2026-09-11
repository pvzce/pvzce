package com.pvzce.client.renderer.sprite;

import com.pvzce.client.renderer.texture.Texture;

/** A texture region (phase 1: whole image; phase 2: atlas regions). */
public record Sprite(Texture texture, float u0, float v0, float u1, float v1) {
    public static Sprite whole(Texture texture) {
        return new Sprite(texture, 0F, 0F, 1F, 1F);
    }
}
