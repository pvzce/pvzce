package com.pvzce.launcher;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.launch.FabricLauncher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GameLoginArgumentsTest {
    private static final String CODE = "launcher_test_code_0123456789ABCDE";
    @TempDir Path dir;

    @Test void bothFormsAreConsumedBeforeFabricAndJvmDiagnostics() throws Exception {
        for (boolean equals : List.of(false, true)) {
            String[] args = equals
                    ? new String[]{"--gameDir", dir.toString(), "--pvzceApiUrl=https://example.com/", "--pvzceLoginCode=" + CODE}
                    : new String[]{"--gameDir", dir.toString(), "--pvzceApiUrl", "https://example.com/", "--pvzceLoginCode", CODE};
            String previous = System.getProperty("sun.java.command");
            try {
                System.setProperty("sun.java.command", "pvzce --pvzceLoginCode=" + CODE);
                System.setProperty("jdk.httpclient.HttpClient.log", "all");
                System.setProperty("jdk.internal.httpclient.debug", "true");
                var login = GameLoginArguments.consume(args);
                assertFalse(login.invalid());
                assertEquals("https://example.com", login.api());
                assertArrayEquals(new String[]{"--gameDir", dir.toString()}, login.remaining());
                assertFalse(Arrays.toString(args).contains(CODE));
                assertFalse(System.getProperty("sun.java.command").contains(CODE));
                assertNull(System.getProperty("jdk.httpclient.HttpClient.log"));
                assertEquals("false", System.getProperty("jdk.internal.httpclient.debug"));
                assertArrayEquals(CODE.toCharArray(), login.takeCode());
                assertNull(login.takeCode());
                login.clear();
                assertNull(login.api());
                assertFalse(login.toString().contains(CODE));
            } finally {
                if (previous == null) System.clearProperty("sun.java.command");
                else System.setProperty("sun.java.command", previous);
            }
        }
    }

    @Test void malformedDuplicateAndMissingValuesNeverLeaveACode() {
        for (String[] args : List.of(
                new String[]{"--pvzceLoginCode", CODE},
                new String[]{"--pvzceApiUrl", "https://example.com"},
                new String[]{"--pvzceApiUrl", "--pvzceLoginCode=" + CODE},
                new String[]{"--pvzceApiUrl=https://example.com", "--pvzceLoginCode="},
                new String[]{"--pvzceApiUrl=https://example.com", "--pvzceLoginCode=" + CODE, "--pvzceLoginCode", CODE},
                new String[]{"--pvzceApiUrl=https://example.com", "--pvzceApiUrl=https://example.com", "--pvzceLoginCode=" + CODE},
                new String[]{"--pvzceApiUrl=http://remote.example", "--pvzceLoginCode=" + CODE},
                new String[]{"--pvzceApiUrl=https://example.com", "--pvzceLoginCode=bad code"})) {
            var login = GameLoginArguments.consume(args);
            assertTrue(login.invalid());
            assertNull(login.takeCode());
            assertArrayEquals(new String[0], login.remaining());
            assertFalse(Arrays.toString(args).contains(CODE));
        }
        var offline = GameLoginArguments.consume(new String[]{"--gameDir", dir.toString()});
        assertFalse(offline.invalid());
        assertNull(offline.takeCode());
    }

    @Test void base64urlCodeMayStartWithTwoDashes() {
        String code = "--" + CODE;
        var login = GameLoginArguments.consume(new String[]{"--pvzceApiUrl", "https://example.com", "--pvzceLoginCode", code});
        assertFalse(login.invalid());
        assertArrayEquals(code.toCharArray(), login.takeCode());
        assertArrayEquals(new String[0], login.remaining());
    }

    @Test void originOnlyAndExplicitLocalDevelopmentExceptions() {
        assertEquals("https://example.com:8443", GameLoginArguments.normalizeOrigin("HTTPS://EXAMPLE.COM:8443/"));
        assertEquals("https://example.com", GameLoginArguments.normalizeOrigin("https://example.com:443"));
        for (String host : List.of("localhost", "127.0.0.1", "[::1]", "10.0.2.2")) {
            assertEquals("http://" + host + ":8123", GameLoginArguments.normalizeOrigin("http://" + host + ":8123/"));
        }
        for (String url : List.of("http://example.com", "http://127.0.0.2", "http://localhost.evil", "file:///tmp/x",
                "https://user@example.com", "https://example.com/a", "https://example.com/%2f", "https://example.com?x=1",
                "https://example.com#x", "https://example.com:0", "https://example.com:65536", "//example.com")) {
            assertThrows(IllegalArgumentException.class, () -> GameLoginArguments.normalizeOrigin(url), url);
        }
    }

    @Test void providerPublicApisNeverExposeConsumedCredentials() throws Exception {
        Path gameClass = dir.resolve("com/pvzce/launcher/PvzceGame.class");
        Files.createDirectories(gameClass.getParent());
        Files.createFile(gameClass);
        FabricLauncher launcher = (FabricLauncher) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class[]{FabricLauncher.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getEnvironmentType" -> EnvType.CLIENT;
                    case "getClassPath" -> List.of(dir);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        var provider = new PvzceGameProvider();
        assertTrue(provider.locateGame(launcher, new String[]{"--gameDir", dir.toString(),
                "--pvzceLoginCode=" + CODE, "--pvzceApiUrl", "https://example.com", "--accessToken", "legacy"}));
        for (String publicView : List.of(Arrays.toString(provider.getLaunchArguments(true)),
                Arrays.toString(provider.getLaunchArguments(false)), Arrays.toString(provider.getArguments().toArray()))) {
            assertFalse(publicView.contains(CODE));
            assertFalse(publicView.contains("pvzceLoginCode"));
            assertFalse(publicView.contains("pvzceApiUrl"));
        }
        assertFalse(Arrays.toString(provider.getLaunchArguments(true)).contains("legacy"));
    }
}
