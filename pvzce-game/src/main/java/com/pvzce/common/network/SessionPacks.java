package com.pvzce.common.network;

import com.google.gson.JsonArray;
import com.pvzce.common.PvzceConstants;
import com.pvzce.common.network.packet.PackChunkS2C;
import com.pvzce.common.network.packet.PackManifestS2C;
import com.pvzce.common.network.packet.RoomC2S;
import com.pvzce.common.resource.DirectoryPack;
import com.pvzce.common.resource.PvzceResourceManager;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.*;

/** Host archives and client content-addressed cache. No downloaded file enters the user's pack roots. */
public final class SessionPacks {
    private SessionPacks() { }
    private record Archive(PackManifestS2C.Pack info, byte[] bytes, long unpackedBytes) { }

    public static final class Host {
        private final List<Archive> archives;
        public Host(PvzceResourceManager resources) throws IOException {
            List<Archive> result = new ArrayList<>();
            long total = 0, unpacked = 0;
            for (var pack : resources.packs()) if (pack instanceof DirectoryPack directory) {
                Archive archive = archive(directory);
                total += archive.bytes().length; unpacked += archive.unpackedBytes();
                if (unpacked > PvzceConstants.NETWORK_MAX_PACK_TOTAL_BYTES || total > PvzceConstants.NETWORK_MAX_PACK_TOTAL_BYTES || result.size() >= PvzceConstants.NETWORK_MAX_PACKS)
                    throw new IOException("房间数据包总量过大");
                result.add(archive);
            }
            archives = List.copyOf(result);
        }
        public PackManifestS2C manifest(boolean requireAll) {
            return new PackManifestS2C(archives.stream().map(Archive::info).toList(), requireAll);
        }
        public Set<String> validateSelection(List<String> selection, boolean requireAll) {
            Set<String> chosen = new LinkedHashSet<>(selection);
            Set<String> known = new HashSet<>();
            for (var archive : archives) {
                var pack = archive.info(); known.add(pack.hash());
                if ((requireAll || pack.required()) && !chosen.contains(pack.hash()))
                    throw new IllegalArgumentException("必须接受数据包：" + pack.name());
            }
            if (!known.containsAll(chosen)) throw new IllegalArgumentException("选择了未知数据包");
            return Set.copyOf(chosen);
        }
        public PackChunkS2C chunk(String hash, int index) {
            byte[] bytes = archives.stream().filter(a -> a.info().hash().equals(hash)).findFirst().orElseThrow().bytes();
            long offset = (long)index * PvzceConstants.NETWORK_PACK_CHUNK_BYTES;
            if (index < 0 || offset >= bytes.length) throw new IllegalArgumentException("数据包分块索引无效");
            int end = (int)Math.min(bytes.length, offset + PvzceConstants.NETWORK_PACK_CHUNK_BYTES);
            return new PackChunkS2C(hash, index, Base64.getEncoder().encodeToString(Arrays.copyOfRange(bytes, (int)offset, end)), end == bytes.length);
        }
    }

    private static Archive archive(DirectoryPack pack) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        long total = 0;
        boolean required = false;
        int count = 0;
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            // Only the trees the pack loader consumes, plus its metadata and icon, are distributable.
            List<String> paths = new ArrayList<>();
            paths.addAll(pack.list("data")); paths.addAll(pack.list("assets"));
            for (String name : List.of("pack.mcmeta", "pack.png")) if (pack.contains(name)) paths.add(name);
            for (String path : paths.stream().sorted().toList()) {
                Path file = pack.resolve(path);
                if (!file.toRealPath().startsWith(pack.root().toRealPath())) throw new IOException("数据包包含外部文件链接：" + path);
                total += Files.size(file);
                if (total > PvzceConstants.NETWORK_MAX_PACK_BYTES || ++count > PvzceConstants.NETWORK_MAX_PACK_FILES)
                    throw new IOException("数据包过大：" + pack.name());
                required |= path.startsWith("data/");
                ZipEntry entry = new ZipEntry(path); entry.setTime(0); zip.putNextEntry(entry);
                Files.copy(file, zip); zip.closeEntry();
            }
        }
        byte[] result = bytes.toByteArray();
        if (result.length > PvzceConstants.NETWORK_MAX_PACK_BYTES) throw new IOException("数据包过大：" + pack.name());
        return new Archive(new PackManifestS2C.Pack(pack.name(), ContentSnapshot.digest(result), result.length, required), result, total);
    }

    public static final class Client implements AutoCloseable {
        private final Path cache;
        private final Map<String, Path> local = new HashMap<>();
        private final List<Path> temporary = new ArrayList<>();
        private List<PackManifestS2C.Pack> selected = List.of();
        private final List<Path> roots = new ArrayList<>();
        private int current, nextChunk;
        private OutputStream download;
        private Path partial;
        private long received, extractedBytes;
        private boolean started, complete;

        public Client(Path gameDir, PvzceResourceManager resources) throws IOException {
            cache = gameDir.resolve("server-packs").toAbsolutePath();
            Files.createDirectories(cache);
            if (resources != null) for (var pack : resources.packs()) if (pack instanceof DirectoryPack directory)
                local.put(archive(directory).info().hash(), directory.root());
        }
        public static void validate(PackManifestS2C manifest) {
            if (manifest.packs().size() > PvzceConstants.NETWORK_MAX_PACKS) throw new IllegalArgumentException("房间数据包过多");
            long total = 0;
            for (var pack : manifest.packs()) {
                if (!pack.hash().matches("[0-9a-f]{64}") || pack.bytes() <= 0 || pack.bytes() > PvzceConstants.NETWORK_MAX_PACK_BYTES
                        || pack.name().isBlank() || pack.name().length() > 1024 || pack.name().chars().anyMatch(Character::isISOControl))
                    throw new IllegalArgumentException("房间数据包清单无效");
                total += pack.bytes();
            }
            if (total > PvzceConstants.NETWORK_MAX_PACK_TOTAL_BYTES) throw new IllegalArgumentException("房间数据包总量过大");
        }
        public void select(PackManifestS2C manifest, Set<String> choice, Consumer<PvzcePacket> sink) throws IOException {
            if (started) throw new IllegalStateException("已经选择房间数据包");
            validate(manifest); started = true;
            selected = manifest.packs().stream().filter(p -> manifest.requireAll() || p.required() || choice.contains(p.hash())).toList();
            JsonArray hashes = new JsonArray(); selected.forEach(p -> hashes.add(p.hash()));
            sink.accept(new RoomC2S("packs_select", hashes.toString(), ""));
            advance(sink);
        }
        private void advance(Consumer<PvzcePacket> sink) throws IOException {
            while (current < selected.size()) {
                var pack = selected.get(current);
                Path root = local.get(pack.hash());
                if (root == null) {
                    Path cached = cache.resolve(pack.hash() + ".zip");
                    if (Files.isRegularFile(cached) && Files.size(cached) == pack.bytes()
                            && ContentSnapshot.digest(Files.readAllBytes(cached)).equals(pack.hash())) root = extract(cached);
                    else {
                        partial = Files.createTempFile(cache, "download-", ".partial");
                        download = Files.newOutputStream(partial); received = 0; nextChunk = 0;
                        sink.accept(new RoomC2S("pack_get", pack.hash(), "0")); return;
                    }
                }
                local.put(pack.hash(), root); roots.add(root); current++;
            }
            complete = true;
            sink.accept(new RoomC2S("packs_ready", "", ""));
        }
        public void accept(PackChunkS2C chunk, Consumer<PvzcePacket> sink) throws IOException {
            if (download == null || current >= selected.size()) throw new IOException("未请求的数据包分块");
            var pack = selected.get(current);
            if (!chunk.hash().equals(pack.hash()) || chunk.index() != nextChunk++) throw new IOException("数据包分块顺序无效");
            byte[] bytes = Base64.getDecoder().decode(chunk.data());
            received += bytes.length;
            if (bytes.length == 0 || bytes.length > PvzceConstants.NETWORK_PACK_CHUNK_BYTES || received > pack.bytes()
                    || chunk.last() != (received == pack.bytes())) throw new IOException("数据包分块长度无效");
            download.write(bytes);
            if (!chunk.last()) { sink.accept(new RoomC2S("pack_get", pack.hash(), Integer.toString(nextChunk))); return; }
            download.close(); download = null;
            if (!ContentSnapshot.digest(Files.readAllBytes(partial)).equals(pack.hash())) throw new IOException("数据包校验失败：" + pack.name());
            Path root = extract(partial);
            Files.move(partial, cache.resolve(pack.hash() + ".zip"), StandardCopyOption.REPLACE_EXISTING); partial = null;
            local.put(pack.hash(), root); roots.add(root); current++; advance(sink);
        }
        private Path extract(Path archive) throws IOException {
            Path root = Files.createTempDirectory(cache, "session-"); temporary.add(root);
            long total = 0; int count = 0;
            Set<String> paths = new HashSet<>();
            try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
                ZipEntry entry;
                byte[] buffer = new byte[8192];
                while ((entry = zip.getNextEntry()) != null) {
                    String name = entry.getName();
                    Path target = root.resolve(name).normalize();
                    if (!target.startsWith(root) || !root.relativize(target).toString().replace(java.io.File.separatorChar, '/').equals(name)
                            || name.contains("\\") || entry.isDirectory()
                            || !(name.startsWith("data/") || name.startsWith("assets/") || name.equals("pack.mcmeta") || name.equals("pack.png"))
                            || !paths.add(target.toString()) || ++count > PvzceConstants.NETWORK_MAX_PACK_FILES)
                        throw new IOException("数据包内的路径无效：" + name);
                    Files.createDirectories(target.getParent());
                    try (OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
                        int length;
                        while ((length = zip.read(buffer)) != -1) {
                            total += length; extractedBytes += length;
                            if (total > PvzceConstants.NETWORK_MAX_PACK_BYTES || extractedBytes > PvzceConstants.NETWORK_MAX_PACK_TOTAL_BYTES) throw new IOException("数据包解压后过大");
                            out.write(buffer, 0, length);
                        }
                    }
                }
            }
            return root;
        }
        public List<Path> roots() {
            if (!complete) throw new IllegalStateException("数据包尚未下载完成");
            return List.copyOf(roots);
        }
        @Override public void close() throws IOException {
            if (download != null) { download.close(); download = null; }
            if (partial != null) Files.deleteIfExists(partial);
            for (Path root : temporary) try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
            temporary.clear();
        }
    }
}
