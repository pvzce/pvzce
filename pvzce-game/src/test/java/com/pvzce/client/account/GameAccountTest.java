package com.pvzce.client.account;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class GameAccountTest {
    static final String CODE = "test_login_code_0123456789ABCDEFGH";
    static final String TOKEN = "test_game_token_0123456789ABCDEFGH";
    static final Instant NOW = Instant.parse("2026-10-19T01:00:00Z");

    static JsonObject session() {
        return JsonParser.parseString("""
                {"token_type":"Bearer","access_token":"%s","expires_at":"2026-10-19T02:00:00Z",
                 "scope":["game:session"],"user":{"id":42,"display_name":"玩家"}}
                """.formatted(TOKEN)).getAsJsonObject();
    }

    static void idle(GameAccount account) throws Exception {
        Field field = GameAccount.class.getDeclaredField("worker");
        field.setAccessible(true);
        ((ExecutorService) field.get(account)).submit(() -> {}).get(12, TimeUnit.SECONDS);
    }

    private static void assertCleared(GameAccount account) throws Exception {
        for (String name : List.of("token", "expiry", "nextCheck")) {
            Field field = GameAccount.class.getDeclaredField(name);
            field.setAccessible(true);
            assertNull(field.get(account), name);
        }
        assertEquals(GameAccount.State.OFFLINE, account.view().state());
        assertFalse(account.view().toString().contains(TOKEN));
        assertFalse(account.view().toString().contains(CODE));
    }

    private static final class Time extends Clock {
        Instant now = NOW;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test void exchangeThenValidateBeforeOnlineAndLogoutOnce() throws Exception {
        var calls = new ArrayList<String>();
        try (var account = new GameAccount(Clock.fixed(NOW, ZoneOffset.UTC))) {
            char[] code = CODE.toCharArray();
            account.start((method, path, body, token) -> {
                calls.add(method + " " + path);
                if (path.equals(GameAccount.EXCHANGE)) {
                    assertEquals(1, body.get("protocol").getAsInt());
                    assertEquals("pvzce", body.get("game_id").getAsString());
                    assertEquals("1.0.0", body.get("game_version").getAsString());
                    assertEquals(CODE, body.get("login_code").getAsString());
                    assertNull(token);
                } else {
                    assertEquals(TOKEN, token);
                    assertNull(body);
                    if (path.equals(GameAccount.SESSION)) assertEquals(GameAccount.State.CONNECTING, account.view().state());
                }
                return session();
            }, code);
            assertArrayEquals(new char[code.length], code);
            idle(account);
            assertEquals(GameAccount.State.ONLINE, account.view().state());
            assertEquals("玩家", account.view().displayName());
            account.logout();
            assertCleared(account);
            idle(account);
            account.logout();
            idle(account);
            assertEquals(List.of("POST " + GameAccount.EXCHANGE, "GET " + GameAccount.SESSION, "POST " + GameAccount.LOGOUT), calls);
        }
    }

    @Test void firstSessionFailureOrIdentityMismatchNeverShowsLoggedIn() throws Exception {
        for (String fault : List.of("revoked", "wrong-user", "long-expiry", "scope", "echo-token", "echo-code", "malformed")) {
            try (var account = new GameAccount(Clock.fixed(NOW, ZoneOffset.UTC))) {
                account.start((method, path, body, token) -> {
                    var value = session();
                    if (!path.equals(GameAccount.SESSION)) return value;
                    switch (fault) {
                        case "revoked" -> throw new GameAccount.Failure("登录凭证已撤销");
                        case "wrong-user" -> value.getAsJsonObject("user").addProperty("id", 99);
                        case "long-expiry" -> value.addProperty("expires_at", "2026-10-19T02:00:01Z");
                        case "scope" -> value.getAsJsonArray("scope").add("admin");
                        case "echo-token" -> value.getAsJsonObject("user").addProperty("display_name", TOKEN);
                        case "echo-code" -> value.getAsJsonObject("user").addProperty("display_name", CODE);
                        case "malformed" -> { return new JsonObject(); }
                    }
                    return value;
                }, CODE.toCharArray());
                idle(account);
                assertCleared(account);
            }
        }
    }

    @Test void expiryAndPeriodicRevocationClearInMemorySession() throws Exception {
        for (boolean revoked : List.of(true, false)) {
            var time = new Time();
            var calls = new ArrayList<String>();
            try (var account = new GameAccount(time)) {
                account.start((method, path, body, token) -> {
                    calls.add(path);
                    if (calls.size() > 2) throw new GameAccount.Failure("登录凭证已撤销");
                    return session();
                }, CODE.toCharArray());
                idle(account);
                time.now = NOW.plusSeconds(revoked ? 60 : 3600);
                account.tick();
                idle(account);
                assertCleared(account);
                assertEquals(revoked ? 3 : 2, calls.size());
            }
        }
    }

    @Test void cancellationDuringExchangeCannotRestoreIdentity() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var account = new GameAccount(Clock.fixed(NOW, ZoneOffset.UTC))) {
            var calls = new ArrayList<String>();
            account.start((method, path, body, token) -> {
                calls.add(path);
                entered.countDown();
                try { assertTrue(release.await(3, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new AssertionError(e); }
                return session();
            }, CODE.toCharArray());
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            account.logout();
            assertCleared(account);
            release.countDown();
            idle(account);
            assertCleared(account);
            assertEquals(List.of(GameAccount.EXCHANGE, GameAccount.LOGOUT), calls);
        } finally { release.countDown(); }
    }

    @Test void internalNetworkExceptionTextCannotBecomeUiOrACrashCause() throws Exception {
        try (var account = new GameAccount(Clock.fixed(NOW, ZoneOffset.UTC))) {
            account.start((method, path, body, token) -> {
                throw new IllegalArgumentException(CODE + TOKEN + " remote_debug_details");
            }, CODE.toCharArray());
            idle(account);
            assertCleared(account);
            assertFalse(account.view().message().contains("remote_debug_details"));
        }
    }

    @Test void invalidOrAbsentArgumentsStayOfflineWithoutTransport() throws Exception {
        try (var account = new GameAccount()) {
            account.start(null, null, false);
            assertCleared(account);
            char[] code = CODE.toCharArray();
            account.start("https://example.com/path", code, true);
            assertTrue(Arrays.equals(code, new char[code.length]));
            assertCleared(account);
        }
    }
}
