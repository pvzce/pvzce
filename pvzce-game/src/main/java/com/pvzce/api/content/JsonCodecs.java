package com.pvzce.api.content;

import com.google.gson.JsonElement;
import com.mojang.serialization.Codec;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.JsonOps;

/** Shared Codecs for the content definition records. */
public final class JsonCodecs {
    /** Raw Gson JSON value codec (used for game rules and environment variables). */
    public static final Codec<JsonElement> RAW_JSON = Codec.PASSTHROUGH.xmap(
            dynamic -> JsonOps.INSTANCE.convertTo(JsonOps.COMPRESSED, (JsonElement) dynamic.getValue()),
            element -> new Dynamic<>(JsonOps.COMPRESSED, element)
    );

    private JsonCodecs() {
    }
}
