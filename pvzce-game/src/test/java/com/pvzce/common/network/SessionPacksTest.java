package com.pvzce.common.network;

import com.pvzce.common.network.packet.*;
import com.pvzce.common.resource.PvzceResourceManager;
import com.pvzce.common.resource.PackSelection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class SessionPacksTest {
    private static void file(Path root, String path, String text) throws IOException {
        Path target = root.resolve(path); Files.createDirectories(target.getParent()); Files.writeString(target, text);
    }
    private PvzceResourceManager resources(Path dir) throws IOException {
        var resources = new PvzceResourceManager(getClass().getClassLoader()); resources.init(dir); return resources;
    }
    private static void finish(SessionPacks.Host host, SessionPacks.Client client, List<PvzcePacket> requests) throws IOException {
        for (int i = 0; i < requests.size(); i++) {
            RoomC2S request = (RoomC2S)requests.get(i);
            if (request.action().equals("pack_get")) client.accept(host.chunk(request.value(), Integer.parseInt(request.token())), requests::add);
        }
        assertEquals("packs_ready", ((RoomC2S)requests.getLast()).action());
    }
    @Test void missingAndChangedPacksDownloadInHostOrderWithoutOverwritingLocalFilesAndReuseCache(@TempDir Path dir) throws Exception {
        Path hostDir = dir.resolve("host"), guestDir = dir.resolve("guest");
        String data = "data/test/plants/new.json", lang = "assets/test/lang/zh_cn.json";
        file(hostDir, "datapacks/gameplay/" + data, "new-definition");
        file(hostDir, "datapacks/gameplay/" + lang, "required-art");
        file(hostDir, "resourcepacks/cosmetic/" + lang, "optional-art");
        file(guestDir, "datapacks/gameplay/" + data, "old-definition");
        file(guestDir, "datapacks/gameplay/" + lang, "local-art");
        try (var hostResources = resources(hostDir); var guestResources = resources(guestDir)) {
            var host = new SessionPacks.Host(hostResources);
            var manifest = host.manifest(false);
            assertFalse(manifest.packs().getFirst().required());
            assertTrue(manifest.packs().getLast().required());
            List<Path> mounted;
            try (var client = new SessionPacks.Client(guestDir, guestResources)) {
                List<PvzcePacket> requests = new ArrayList<>(); client.select(manifest, Set.of(), requests::add);
                finish(host, client, requests);
                assertEquals(1, requests.stream().filter(p -> p instanceof RoomC2S r && r.action().equals("pack_get")).count());
                mounted = client.roots(); guestResources.sessionPacks(mounted); guestResources.reload();
                assertEquals("new-definition", new String(guestResources.getResource(data).orElseThrow().bytes(), StandardCharsets.UTF_8));
                assertEquals("required-art", new String(guestResources.getResource(lang).orElseThrow().bytes(), StandardCharsets.UTF_8));
                assertEquals("old-definition", Files.readString(guestDir.resolve("datapacks/gameplay/" + data)));
                guestResources.sessionPacks(List.of()); guestResources.reload();
                assertEquals("local-art", new String(guestResources.getResource(lang).orElseThrow().bytes(), StandardCharsets.UTF_8));
            }
            assertFalse(Files.exists(mounted.getFirst()), "Session directories must disappear on exit");
            try (var again = new SessionPacks.Client(guestDir, guestResources)) {
                List<PvzcePacket> requests = new ArrayList<>(); again.select(manifest, Set.of(), requests::add);
                assertTrue(requests.stream().noneMatch(p -> p instanceof RoomC2S r && r.action().equals("pack_get")), "Verified cache avoids another download");
                assertEquals(1, again.roots().size());
            }
            // An edit without changing pack metadata must still invalidate the content hash.
            file(hostDir, "datapacks/gameplay/" + data, "even-newer-definition");
            var updated = new SessionPacks.Host(hostResources);
            assertNotEquals(manifest.packs().getLast().hash(), updated.manifest(false).packs().getLast().hash());
            try (var client = new SessionPacks.Client(guestDir, guestResources)) {
                List<PvzcePacket> requests = new ArrayList<>(); client.select(updated.manifest(false), Set.of(), requests::add);
                finish(updated, client, requests);
                assertEquals("even-newer-definition", Files.readString(client.roots().getFirst().resolve(data)));
            }
        }
    }
    @Test void forceAllIncludesCosmeticPacksAndDisabledPacksAreNotOffered(@TempDir Path dir) throws Exception {
        file(dir, "resourcepacks/cosmetic/assets/test/lang/zh_cn.json", "cosmetic");
        file(dir, "datapacks/gameplay/data/test/levels/new.json", "gameplay");
        file(dir, "datapacks/disabled/data/test/levels/disabled.json", "disabled");
        var selection = PackSelection.load(dir); selection.setEnabled("disabled", false); selection.save();
        try (var resources = resources(dir)) {
            var host = new SessionPacks.Host(resources);
            var manifest = host.manifest(true);
            assertEquals(2, manifest.packs().size());
            assertThrows(IllegalArgumentException.class, () -> host.validateSelection(List.of(manifest.packs().getLast().hash()), true));
            try (var client = new SessionPacks.Client(dir.resolve("guest"), null)) {
                List<PvzcePacket> requests = new ArrayList<>(); client.select(manifest, Set.of(), requests::add); finish(host, client, requests);
                assertEquals(2, client.roots().size());
                assertEquals(Set.of(manifest.packs().getFirst().hash(), manifest.packs().getLast().hash()), host.validateSelection(
                        manifest.packs().stream().map(PackManifestS2C.Pack::hash).toList(), true));
            }
            assertThrows(IllegalArgumentException.class, () -> host.validateSelection(List.of(), false));
        }
    }
    @Test void alreadyMatchingLocalPackNeedsNoDownload(@TempDir Path dir) throws Exception {
        file(dir, "datapacks/same/data/test/levels/new.json", "unchanged");
        try (var resources = resources(dir); var client = new SessionPacks.Client(dir, resources)) {
            var host = new SessionPacks.Host(resources);
            List<PvzcePacket> requests = new ArrayList<>(); client.select(host.manifest(false), Set.of(), requests::add);
            assertEquals(List.of("packs_select", "packs_ready"), requests.stream().map(p -> ((RoomC2S)p).action()).toList());
            assertEquals(dir.resolve("datapacks/same").toAbsolutePath(), client.roots().getFirst());
        }
    }
    private static byte[] zip(String path) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(path)); zip.write("payload".getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
        }
        return bytes.toByteArray();
    }
    private static PackManifestS2C manifest(byte[] archive) {
        return new PackManifestS2C(List.of(new PackManifestS2C.Pack("test", ContentSnapshot.digest(archive), archive.length, true)), false);
    }
    @Test void badHashesOutOfOrderChunksAndArchiveTraversalAreRejectedAndPartialsAreRemoved(@TempDir Path dir) throws Exception {
        byte[] valid = zip("assets/test/lang/a.json");
        try (var client = new SessionPacks.Client(dir, null)) {
            var manifest = manifest(valid); client.select(manifest, Set.of(), p -> { });
            assertThrows(IOException.class, () -> client.accept(new PackChunkS2C(manifest.packs().getFirst().hash(), 1, "e30=", true), p -> { }));
        }
        try (var client = new SessionPacks.Client(dir, null)) {
            var manifest = manifest(valid); client.select(manifest, Set.of(), p -> { });
            byte[] corrupt = valid.clone(); corrupt[corrupt.length - 1] ^= 1;
            assertThrows(IOException.class, () -> client.accept(new PackChunkS2C(manifest.packs().getFirst().hash(), 0, Base64.getEncoder().encodeToString(corrupt), true), p -> { }));
        }
        for (String path : List.of("assets/../../../outside", "../outside", "/outside", "assets/../outside")) {
            byte[] archive = zip(path);
            try (var client = new SessionPacks.Client(dir, null)) {
                var manifest = manifest(archive); client.select(manifest, Set.of(), p -> { });
                assertThrows(IOException.class, () -> client.accept(new PackChunkS2C(manifest.packs().getFirst().hash(), 0, Base64.getEncoder().encodeToString(archive), true), p -> { }), path);
            }
        }
        try (var files = Files.list(dir.resolve("server-packs"))) { assertEquals(List.of(), files.toList()); }
        assertFalse(Files.exists(dir.resolve("outside")));
    }
}
