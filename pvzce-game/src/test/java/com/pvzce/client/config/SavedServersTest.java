package com.pvzce.client.config;

import java.nio.file.*;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SavedServersTest {
    @TempDir Path dir;
    @Test void bookmarksAndNicknameSurviveEditingDeletionAndReload() throws Exception {
        var saved = new SavedServers(dir);
        saved.nickname("局域网玩家");
        saved.put(-1, new SavedServers.Entry("合作", "localhost"));
        saved.put(-1, new SavedServers.Entry("对战", "[::1]:24862"));
        saved.put(0, new SavedServers.Entry("新名字", "127.0.0.1:24863"));
        var reloaded = new SavedServers(dir);
        assertEquals("局域网玩家", reloaded.nickname());
        assertEquals(saved.entries(), reloaded.entries());
        reloaded.remove(0);
        assertEquals(java.util.List.of(new SavedServers.Entry("对战", "[::1]:24862")), new SavedServers(dir).entries());
        var file = dir.resolve("config/servers.json");
        String original = Files.readString(file);
        assertThrows(IllegalArgumentException.class, () -> reloaded.put(-1, new SavedServers.Entry("重复", "[::1]:24862")));
        assertEquals(original, Files.readString(file));
        assertEquals(1, reloaded.entries().size());
    }
    @Test void corruptExistingListIsReportedAndPreserved() throws Exception {
        var file = dir.resolve("config/servers.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{broken data");
        assertThrows(IOException.class, () -> new SavedServers(dir));
        assertEquals("{broken data", Files.readString(file));
    }
}
