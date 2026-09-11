package com.pvzce.common.resource;

import com.pvzce.api.util.Identifier;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** A resolved resource from a specific pack. */
public record PackResource(String packName, String path, byte[] bytes) {
    public InputStream open() {
        return new ByteArrayInputStream(bytes);
    }

    public String readString() {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
