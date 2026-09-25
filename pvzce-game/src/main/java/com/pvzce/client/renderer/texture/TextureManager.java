package com.pvzce.client.renderer.texture;

import com.pvzce.api.util.Identifier;
import com.pvzce.common.resource.PackResource;
import com.pvzce.common.resource.PvzceResourceManager;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** id-to-Texture table that loads PNGs from the resource manager. */
public final class TextureManager implements AutoCloseable {
    static {
        // STB decodes top-down; flip so UV (0,0) is the bottom-left corner.
        STBImage.stbi_set_flip_vertically_on_load(true);
    }

    private final PvzceResourceManager resources;
    private final Map<Identifier, Texture> textures = new ConcurrentHashMap<>();

    public TextureManager(PvzceResourceManager resources) {
        this.resources = resources;
    }

    public Texture getOrLoad(Identifier id) {
        return textures.computeIfAbsent(id, this::load);
    }

    public Texture get(Identifier id) {
        return textures.get(id);
    }

    private Texture load(Identifier id) {
        try {
            var resource = resources.getResource("assets/" + id.toPath() + ".png");
            if (resource.isEmpty()) {
                resource = resources.getAsset(id);
            }
            if (resource.isEmpty()) {
                throw new IllegalStateException("Missing texture " + id);
            }
            return upload(id, resource.get());
        } catch (Exception e) {
            throw new RuntimeException("Failed to load texture " + id, e);
        }
    }

    public Texture upload(Identifier id, PackResource resource) {
        byte[] bytes = resource.bytes();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer width = stack.mallocInt(1);
            IntBuffer height = stack.mallocInt(1);
            IntBuffer channels = stack.mallocInt(1);
            ByteBuffer input = BufferUtils.createByteBuffer(bytes.length);
            input.put(bytes).flip();
            ByteBuffer image = STBImage.stbi_load_from_memory(input, width, height, channels, 4);
            if (image == null) {
                throw new IllegalStateException("Failed to decode PNG " + id + ": " + STBImage.stbi_failure_reason());
            }
            int glId = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, glId);
            // Bind through the facade as well, so its cache knows the real binding.
            // Binding directly left the cache pointing at whatever was bound before,
            // and the next RenderSystem.bindTexture of THAT texture was then skipped as
            // redundant - so the caller sampled this one instead. No current frame hits
            // it (every upload site draws its own texture next, which re-syncs), but the
            // invariant the liquid pass relies on has to hold for the next caller.
            com.pvzce.client.renderer.RenderSystem.noteTextureBound(glId);
            // The missing-texture tile is 2x2 on purpose: magnification is what turns it
            // into the checkerboard, and linear filtering would smear the four squares
            // into a purple gradient. Everything else in the game is painted art that
            // wants smoothing.
            int filter = com.pvzce.common.core.EntityArt.MISSING_TEXTURE.equals(id)
                    ? GL11.GL_NEAREST : GL11.GL_LINEAR;
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, filter);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, filter);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, width.get(0), height.get(0), 0,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, image);
            STBImage.stbi_image_free(image);
            return new Texture(id, glId, width.get(0), height.get(0));
        }
    }

    /** Uploads a raw texture and registers it in the id-to-texture table. */
    public Texture uploadAndCache(Identifier id, PackResource resource) {
        Texture texture = upload(id, resource);
        textures.put(id, texture);
        return texture;
    }

    /** Binds a texture through the facade, keeping its binding cache honest. */
    public void bind(Identifier id) {
        Texture texture = getOrLoad(id);
        com.pvzce.client.renderer.RenderSystem.bindTexture(texture.glId());
    }

    /**
     * Drops every uploaded texture, so the next draw re-reads it from the pack stack.
     *
     * <p>The map is keyed by id and never revalidated, which is right for a session that reads
     * its packs once and wrong the moment a pack changes: a texture a pack replaced would keep
     * drawing the old bytes, and one the pack no longer has would keep drawing at all.
     */
    public void invalidate() {
        for (Texture texture : textures.values()) {
            GL11.glDeleteTextures(texture.glId());
        }
        textures.clear();
    }

    @Override
    public void close() {
        invalidate();
    }
}
