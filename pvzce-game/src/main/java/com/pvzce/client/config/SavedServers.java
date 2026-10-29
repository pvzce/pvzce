package com.pvzce.client.config;

import com.google.gson.*;
import com.pvzce.common.PvzceConstants;
import com.pvzce.common.network.ServerAddress;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Local bookmarks only; no credentials or automatically discovered addresses are saved. */
public final class SavedServers {
    public record Entry(String name, String address) {
        public Entry {
            name = name.strip();
            if (name.isEmpty() || name.length() > 64 || name.chars().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("服务器名称应为 1～64 个字符");
            address = ServerAddress.parse(address).toString();
        }
    }
    private final Path file;
    private final List<Entry> entries = new ArrayList<>();
    private String nickname = "玩家";

    public SavedServers(Path gameDir) throws IOException {
        file = gameDir.resolve("config/servers.json");
        if (!Files.exists(file)) return;
        if (Files.size(file) > PvzceConstants.SERVER_LIST_MAX_BYTES) throw new IOException("服务器列表过大");
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            nickname = root.get("nickname").getAsString();
            if (nickname.isBlank() || nickname.length() > 24 || nickname.chars().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("昵称无效");
            var servers = root.getAsJsonArray("servers");
            if (servers.size() > PvzceConstants.SERVER_LIST_LIMIT) throw new IllegalArgumentException("保存的服务器过多");
            for (var server : servers) {
                var row = server.getAsJsonObject();
                Entry entry = new Entry(row.get("name").getAsString(), row.get("address").getAsString());
                if (entries.stream().anyMatch(e -> e.address().equals(entry.address()))) throw new IllegalArgumentException("地址重复");
                entries.add(entry);
            }
        } catch (RuntimeException e) { throw new IOException("服务器列表格式错误，请检查 config/servers.json", e); }
    }

    public List<Entry> entries() { return List.copyOf(entries); }
    public String nickname() { return nickname; }
    public void nickname(String value) throws IOException {
        value = value.strip();
        if (value.isEmpty()) value = "玩家";
        if (value.length() > 24 || value.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("昵称无效");
        if (nickname.equals(value)) return;
        save(entries, value);
        nickname = value;
    }
    public void put(int index, Entry entry) throws IOException {
        var next = new ArrayList<>(entries);
        if (index < 0) {
            if (next.size() >= PvzceConstants.SERVER_LIST_LIMIT) throw new IllegalArgumentException("保存的服务器过多");
            next.add(entry);
        } else next.set(index, entry);
        if (next.stream().map(Entry::address).distinct().count() != next.size()) throw new IllegalArgumentException("该服务器地址已经添加");
        save(next, nickname);
        entries.clear(); entries.addAll(next);
    }
    public void remove(int index) throws IOException {
        var next = new ArrayList<>(entries); next.remove(index);
        save(next, nickname);
        entries.clear(); entries.addAll(next);
    }
    private void save(List<Entry> next, String nickname) throws IOException {
        var root = new JsonObject(); root.addProperty("nickname", nickname);
        var rows = new JsonArray();
        for (Entry entry : next) {
            var row = new JsonObject(); row.addProperty("name", entry.name()); row.addProperty("address", entry.address()); rows.add(row);
        }
        root.add("servers", rows);
        byte[] bytes = new GsonBuilder().setPrettyPrinting().create().toJson(root).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length > PvzceConstants.SERVER_LIST_MAX_BYTES) throw new IllegalArgumentException("服务器列表过大");
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling("servers.json.tmp");
        Files.write(temporary, bytes);
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
    }
}
