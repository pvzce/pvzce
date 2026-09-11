package com.pvzce.api.util;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;

import java.util.Objects;

/**
 * PVZCE resource identifier ({@code namespace:path}). Rules mirror
 * {@code net.minecraft.resources.Identifier}: {@code [a-z0-9_.-]} for the
 * namespace and additionally {@code /} for the path.
 */
public final class Identifier implements Comparable<Identifier> {
    public static final String DEFAULT_NAMESPACE = "pvzce";
    public static final char NAMESPACE_SEPARATOR = ':';
    public static final Codec<Identifier> CODEC = Codec.STRING.comapFlatMap(Identifier::read, Identifier::toString).stable();

    private final String namespace;
    private final String path;

    private Identifier(String namespace, String path) {
        if (!isValidNamespace(namespace)) {
            throw new IllegalArgumentException("Invalid namespace: " + namespace);
        }
        if (!isValidPath(path)) {
            throw new IllegalArgumentException("Invalid path: " + path);
        }
        this.namespace = namespace;
        this.path = path;
    }

    public static Identifier parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Identifier must not be null");
        }
        int idx = value.indexOf(NAMESPACE_SEPARATOR);
        if (idx >= 0) {
            String path = value.substring(idx + 1);
            if (idx == 0) {
                return of(DEFAULT_NAMESPACE, path);
            }
            return of(value.substring(0, idx), path);
        }
        return of(DEFAULT_NAMESPACE, value);
    }

    public static Identifier tryParse(String value) {
        try {
            return parse(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static Identifier withDefaultNamespace(String path) {
        return of(DEFAULT_NAMESPACE, path);
    }

    public static Identifier of(String namespace, String path) {
        return new Identifier(namespace, path);
    }

    public static DataResult<Identifier> read(String input) {
        try {
            return DataResult.success(parse(input));
        } catch (IllegalArgumentException e) {
            return DataResult.error(() -> "Not a valid resource location: " + input + " " + e.getMessage());
        }
    }

    public static boolean isAllowedInIdentifier(char c) {
        return c >= '0' && c <= '9'
                || c >= 'a' && c <= 'z'
                || c == '_' || c == ':' || c == '/' || c == '.' || c == '-';
    }

    public static boolean isValidPath(String path) {
        // An empty path used to be "valid", so tryParse("") and tryParse("pvzce:")
        // both returned a real identifier. Every caller treats tryParse's null as
        // "missing or malformed", so an empty string silently became a usable id:
        // a save with no "id" key resolved to pvzce: and a data file with "id": ""
        // registered content under pvzce:.
        if (path == null || path.isEmpty()) {
            return false;
        }
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c >= '0' && c <= '9' || c >= 'a' && c <= 'z' || c == '_' || c == '/' || c == '.' || c == '-') {
                continue;
            }
            return false;
        }
        return true;
    }

    public static boolean isValidNamespace(String namespace) {
        if (namespace == null || namespace.isEmpty()) {
            return false;
        }
        for (int i = 0; i < namespace.length(); i++) {
            char c = namespace.charAt(i);
            if (c >= '0' && c <= '9' || c >= 'a' && c <= 'z' || c == '_' || c == '.' || c == '-') {
                continue;
            }
            return false;
        }
        return true;
    }

    public String namespace() {
        return namespace;
    }

    public String path() {
        return path;
    }

    public Identifier withPath(String newPath) {
        return new Identifier(namespace, newPath);
    }

    public Identifier withSuffix(String suffix) {
        return new Identifier(namespace, path + suffix);
    }

    /** Resource path: {@code namespace/path}. */
    public String toPath() {
        return namespace + '/' + path;
    }

    @Override
    public String toString() {
        return namespace + ':' + path;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Identifier that)) return false;
        return namespace.equals(that.namespace) && path.equals(that.path);
    }

    @Override
    public int hashCode() {
        return Objects.hash(namespace, path);
    }

    @Override
    public int compareTo(Identifier o) {
        int cmp = path.compareTo(o.path);
        return cmp != 0 ? cmp : namespace.compareTo(o.namespace);
    }
}
