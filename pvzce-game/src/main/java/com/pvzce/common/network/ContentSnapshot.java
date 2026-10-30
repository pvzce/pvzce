package com.pvzce.common.network;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import com.pvzce.api.registry.*;
import com.pvzce.api.tag.TagKey;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.resource.PvzceDataLoader;
import com.pvzce.common.network.packet.ContentS2C;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.function.Consumer;

/** A session-scoped registry view. Decode everything before touching shared registries. */
public final class ContentSnapshot implements AutoCloseable {
    private final List<Runnable> restorers = new ArrayList<>();
    private final StringBuilder chunks = new StringBuilder();
    private int nextChunk;
    private static final int CHUNK_CHARS = 4096;
    private static final int MAX_CHARS = 8 * 1024 * 1024;

    public static String digest(String text) {
        return digest(text.getBytes(StandardCharsets.UTF_8));
    }
    public static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }

    public static String capture() {
        JsonObject root = new JsonObject();
        JsonObject registries = new JsonObject();
        for (var data : PvzceDataLoader.CONTENT_REGISTRIES) captureRegistry(data, registries);
        root.add("registries", registries);
        return root.toString();
    }

    private static <T> void captureRegistry(PvzceDataLoader.RegistryData<T> data, JsonObject root) {
        Registry<T> registry = BuiltInRegistries.ACCESS.get(data.key());
        JsonObject entries = new JsonObject();
        for (Identifier id : registry.keySet().stream().sorted(Comparator.comparing(Identifier::toString)).toList())
            entries.add(id.toString(), data.codec().encodeStart(JsonOps.INSTANCE, registry.get(id)).getOrThrow());
        JsonObject tags = new JsonObject();
        for (TagKey<T> key : registry.tagKeys()) {
            JsonArray ids = new JsonArray();
            registry.getTag(key).orElseThrow().ids().stream().map(Identifier::toString).sorted().forEach(ids::add);
            tags.add(key.id().toString(), ids);
        }
        JsonObject view = new JsonObject(); view.add("entries", entries); view.add("tags", tags);
        root.add(data.contentPath(), view);
    }

    public static void send(String snapshot, Consumer<PvzcePacket> sink) {
        int index = 0;
        for (int offset = 0; offset < snapshot.length(); offset += CHUNK_CHARS)
            sink.accept(new ContentS2C(index++, snapshot.substring(offset, Math.min(snapshot.length(), offset + CHUNK_CHARS)), false));
        sink.accept(new ContentS2C(index, digest(snapshot), true));
    }

    public boolean accept(ContentS2C part) {
        if (!restorers.isEmpty() || part.index() != nextChunk++) throw new IllegalArgumentException("内容快照顺序无效");
        if (!part.last()) {
            if (chunks.length() + part.data().length() > MAX_CHARS) throw new IllegalArgumentException("内容快照过大");
            chunks.append(part.data()); return false;
        }
        String text = chunks.toString();
        if (!digest(text).equals(part.data())) throw new IllegalArgumentException("内容快照校验失败");
        JsonObject root = JsonParser.parseString(text).getAsJsonObject();
        List<Runnable> installers = new ArrayList<>();
        JsonObject registries = root.getAsJsonObject("registries");
        for (var data : PvzceDataLoader.CONTENT_REGISTRIES) installers.add(decode(data, registries.getAsJsonObject(data.contentPath())));
        installers.forEach(Runnable::run);
        restorers.add(com.pvzce.common.tag.PvzceTags.MANAGER.installSnapshot(BuiltInRegistries.ACCESS));
        chunks.setLength(0);
        return true;
    }

    private <T> Runnable decode(PvzceDataLoader.RegistryData<T> data, JsonObject view) {
        Map<Identifier,T> entries = new LinkedHashMap<>();
        for (var entry : view.getAsJsonObject("entries").entrySet())
            entries.put(Identifier.parse(entry.getKey()), data.codec().parse(JsonOps.INSTANCE, entry.getValue()).getOrThrow());
        Map<Identifier,Set<Identifier>> tags = new LinkedHashMap<>();
        for (var tag : view.getAsJsonObject("tags").entrySet()) {
            Set<Identifier> ids = new LinkedHashSet<>();
            for (JsonElement id : tag.getValue().getAsJsonArray()) ids.add(Identifier.parse(id.getAsString()));
            if (!entries.keySet().containsAll(ids)) throw new IllegalArgumentException("标签引用了未知内容 " + tag.getKey());
            tags.put(Identifier.parse(tag.getKey()), ids);
        }
        MappedRegistry<T> registry = (MappedRegistry<T>) BuiltInRegistries.ACCESS.get(data.key());
        return () -> restorers.add(registry.installSnapshot(entries, tags));
    }

    @Override public void close() {
        for (int i = restorers.size() - 1; i >= 0; i--) restorers.get(i).run();
        restorers.clear(); chunks.setLength(0); nextChunk = 0;
    }
}
