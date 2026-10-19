package com.pvzce.client.account;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class GameLoginHttpTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir;
    private static HttpServer server() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        return server;
    }

    private static String origin(HttpServer server) { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    @Test void realHttpUsesOnlyExpectedPathsJsonAndBearerWithoutAmbientCookies() throws Exception {
        var server = server();
        var requests = new CopyOnWriteArrayList<String>();
        var errors = new CopyOnWriteArrayList<Throwable>();
        server.createContext("/", request -> {
            try {
                String path = request.getRequestURI().getPath();
                requests.add(request.getRequestMethod() + " " + path);
                assertNull(request.getRequestURI().getQuery());
                assertNull(request.getRequestHeaders().getFirst("Cookie"));
                assertNull(request.getRequestHeaders().getFirst("X-CSRF-Token"));
                assertNull(request.getRequestHeaders().getFirst("Origin"));
                String body = new String(request.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if (path.equals(GameAccount.EXCHANGE)) {
                    assertNull(request.getRequestHeaders().getFirst("Authorization"));
                    var value = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
                    assertEquals(GameAccountTest.CODE, value.get("login_code").getAsString());
                    assertEquals(1, value.get("protocol").getAsInt());
                    assertEquals("pvzce", value.get("game_id").getAsString());
                } else {
                    assertEquals("Bearer " + GameAccountTest.TOKEN, request.getRequestHeaders().getFirst("Authorization"));
                    assertTrue(body.isEmpty());
                }
                byte[] response = ("{\"data\":" + GameAccountTest.session() + "}").getBytes(StandardCharsets.UTF_8);
                request.getResponseHeaders().set("Content-Type", "application/json");
                if (path.equals(GameAccount.LOGOUT)) request.sendResponseHeaders(204, -1);
                else {
                    request.sendResponseHeaders(200, response.length);
                    request.getResponseBody().write(response);
                }
            } catch (Throwable e) { errors.add(e); }
            finally { request.close(); }
        });
        CookieHandler previous = CookieHandler.getDefault();
        var cookies = new CookieManager();
        cookies.getCookieStore().add(URI.create(origin(server)), new HttpCookie("pvzce_session", "website_cookie"));
        CookieHandler.setDefault(cookies);
        try (var http = new GameLoginHttp(origin(server));
             var account = new GameAccount(Clock.fixed(GameAccountTest.NOW, ZoneOffset.UTC))) {
            account.start(http, GameAccountTest.CODE.toCharArray());
            GameAccountTest.idle(account);
            assertEquals(GameAccount.State.ONLINE, account.view().state());
            account.logout();
            GameAccountTest.idle(account);
            assertTrue(errors.isEmpty(), errors.toString());
            assertEquals(List.of("POST " + GameAccount.EXCHANGE, "GET " + GameAccount.SESSION, "POST " + GameAccount.LOGOUT), requests);
        } finally {
            CookieHandler.setDefault(previous);
            server.stop(0);
        }
    }

    @Test void redirectsAndErrorBodiesNeverEscapeOrTriggerASecondExchange() throws Exception {
        var server = server();
        var destination = server();
        var redirected = new AtomicInteger();
        var exchanges = new AtomicInteger();
        var status = new AtomicInteger(302);
        destination.createContext("/", request -> { redirected.incrementAndGet(); request.close(); });
        server.createContext("/", request -> {
            exchanges.incrementAndGet();
            request.getRequestBody().readAllBytes();
            request.getResponseHeaders().set("Location", origin(destination) + GameAccount.EXCHANGE);
            request.getResponseHeaders().set("Content-Type", "application/json");
            byte[] response = (GameAccountTest.CODE + GameAccountTest.TOKEN + " private_error_body").getBytes(StandardCharsets.UTF_8);
            request.sendResponseHeaders(status.get(), response.length);
            request.getResponseBody().write(response);
            request.close();
        });
        try (var http = new GameLoginHttp(origin(server))) {
            for (int code : List.of(301, 302, 307, 308, 400, 401, 403, 404, 409, 429, 503, 500)) {
                status.set(code);
                var failure = assertThrows(GameAccount.Failure.class,
                        () -> http.request("POST", GameAccount.EXCHANGE, new JsonObject(), null));
                assertFalse(failure.toString().contains(GameAccountTest.CODE));
                assertFalse(failure.toString().contains(GameAccountTest.TOKEN));
                assertFalse(failure.toString().contains("private_error_body"));
                assertNull(failure.getCause());
            }
            assertEquals(12, exchanges.get());
            assertEquals(0, redirected.get());
        } finally { server.stop(0); destination.stop(0); }
    }

    @Test void lostExchangeResponseIsNotRetried() throws Exception {
        var server = server();
        var calls = new AtomicInteger();
        server.createContext("/", request -> {
            calls.incrementAndGet();
            request.getRequestBody().readAllBytes();
            request.close();
        });
        try (var http = new GameLoginHttp(origin(server))) {
            assertThrows(GameAccount.Failure.class,
                    () -> http.request("POST", GameAccount.EXCHANGE, new JsonObject(), null));
            assertEquals(1, calls.get());
        } finally { server.stop(0); }
    }

    @Test void launchBoundaryDisablesWireDiagnosticsInAFreshJvm() throws Exception {
        var server = server();
        String expiry = java.time.Instant.now().plusSeconds(3500).toString();
        server.createContext("/", request -> {
            request.getRequestBody().readAllBytes();
            if (request.getRequestURI().getPath().equals(GameAccount.LOGOUT)) {
                request.sendResponseHeaders(204, -1);
            } else {
                var session = GameAccountTest.session();
                session.addProperty("expires_at", expiry);
                byte[] response = ("{\"data\":" + session + "}").getBytes(StandardCharsets.UTF_8);
                request.getResponseHeaders().set("Content-Type", "application/json");
                request.sendResponseHeaders(200, response.length);
                request.getResponseBody().write(response);
            }
            request.close();
        });
        try {
            String classpath = java.util.stream.Stream.of(com.pvzce.launcher.GameLoginDiagnosticsProbe.class,
                            GameAccount.class, com.pvzce.launcher.GameLoginArguments.class, com.google.gson.Gson.class)
                    .map(type -> {
                        try { return java.nio.file.Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(); }
                        catch (Exception e) { throw new AssertionError(e); }
                    }).distinct().collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
            for (boolean equals : List.of(false, true)) {
                var args = new java.util.ArrayList<>(List.of(java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-Djdk.httpclient.HttpClient.log=all", "-Djdk.internal.httpclient.debug=true", "-Djavax.net.debug=all",
                        "-cp", classpath, "com.pvzce.launcher.GameLoginDiagnosticsProbe", "--pvzceApiUrl", origin(server)));
                args.add(equals ? "--pvzceLoginCode=" + GameAccountTest.CODE : "--pvzceLoginCode");
                if (!equals) args.add(GameAccountTest.CODE);
                var outputFile = tempDir.resolve("probe-" + equals + ".log");
                var process = new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(outputFile.toFile()).start();
                try { assertTrue(process.waitFor(15, TimeUnit.SECONDS)); }
                finally { if (process.isAlive()) process.destroyForcibly(); }
                String output = java.nio.file.Files.readString(outputFile);
                assertEquals(0, process.exitValue(), output);
                assertTrue(output.contains("ONLINE"), output);
                assertFalse(output.contains(GameAccountTest.CODE));
                assertFalse(output.contains(GameAccountTest.TOKEN));
            }
        } finally { server.stop(0); }
    }

    @Test void malformedOversizedAndStalledBodiesFallOfflineWithABoundedWait() throws Exception {
        var server = server();
        var mode = new AtomicInteger();
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        server.createContext("/", request -> {
            calls.incrementAndGet();
            request.getRequestBody().readAllBytes();
            request.getResponseHeaders().set("Content-Type", "application/json");
            byte[] response = (mode.get() == 1 ? "x".repeat(20000) : "invalid " + GameAccountTest.TOKEN)
                    .getBytes(StandardCharsets.UTF_8);
            request.sendResponseHeaders(200, response.length);
            if (mode.get() == 2) {
                try { release.await(12, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            } else request.getResponseBody().write(response);
            request.close();
        });
        try {
            for (int i = 0; i < 3; i++) {
                mode.set(i);
                try (var http = new GameLoginHttp(origin(server));
                     var account = new GameAccount(Clock.fixed(GameAccountTest.NOW, ZoneOffset.UTC))) {
                    account.start(http, GameAccountTest.CODE.toCharArray());
                    // A responsive account view demonstrates the HTTP wait is outside the main thread.
                    assertNotEquals(GameAccount.State.ONLINE, account.view().state());
                    GameAccountTest.idle(account);
                    assertEquals(GameAccount.State.OFFLINE, account.view().state());
                    assertFalse(account.view().toString().contains(GameAccountTest.TOKEN));
                }
            }
            assertEquals(3, calls.get());
        } finally { release.countDown(); server.stop(0); }
    }
}
