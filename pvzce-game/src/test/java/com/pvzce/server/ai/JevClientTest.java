package com.pvzce.server.ai;

import com.pvzce.common.jev.JevPrompt;
import com.pvzce.common.jev.AiSettings;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The transport half: a real HTTP exchange against a local stand-in for Jev.
 *
 * <p>A stand-in rather than a mock, because most of what can go wrong here is HTTP - an
 * authorization header that never made it, a status that is not 200, a body that is not the
 * shape that was promised, a provider that never answers. All four are cheap to produce on
 * loopback and impossible to check with an injected fake.
 *
 * <p>It is also the only end-to-end proof that exists without a key: the shipped default is a
 * paid endpoint, and a test suite may not call it.
 */
class JevClientTest {
    private HttpServer server;
    private JevClient client;
    private final AtomicReference<String> lastBody = new AtomicReference<>("");
    private final AtomicReference<String> lastAuth = new AtomicReference<>("");

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.stop(0);
        }
    }

    private static JevPrompt prompt() {
        return new JevPrompt(JevPrompt.Side.ZOMBIE, "Break through before they bank 5000 sun.",
                150, JevPrompt.NO_GOAL, 0, 30,
                List.of(new JevPrompt.CardOption("pvzce:basic_zombie", 50, true, "basic")),
                List.of(new JevPrompt.RowOption(2, "row_2: two peashooters")),
                List.of(new JevPrompt.ColumnOption(7, "col_7: the far right")));
    }

    /** Starts the stand-in and answers every request with {@code status}/{@code body}. */
    private AiSettings startServer(int status, String body, long delayMillis) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/systemone", exchange -> {
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastBody.set(readBody(exchange));
            if (delayMillis > 0) {
                sleep(delayMillis);
            }
            respond(exchange, status, body);
        });
        server.start();
        return new AiSettings("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/systemone",
                "typesafe/jev-1.13", "test-key");
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Waits for the worker thread, so the assertions do not race it. */
    private Optional<JevClient.Result> await(JevClient target, long timeoutMillis) {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            Optional<JevClient.Result> result = target.poll();
            if (result.isPresent()) {
                return result;
            }
            sleep(10);
        }
        return Optional.empty();
    }

    @Test
    void aDecisionTravelsTheWholeWayFromPromptToQueue() throws IOException {
        String answer = "{\"answers\":{\"action\":{\"choice\":\"pvzce:basic_zombie\",\"confidence\":0.7},"
                + "\"row\":{\"choice\":\"row_2\"},\"column\":{\"choice\":\"7\"}}}";
        AiSettings settings = startServer(200, answer, 0);
        client = new JevClient();

        assertTrue(client.request(settings, prompt(), 7L));
        Optional<JevClient.Result> result = await(client, 5000);
        assertTrue(result.isPresent(), "the answer never arrived");
        assertTrue(result.get().usable(), result.get().error());
        assertEquals(7L, result.get().ticket());
        assertEquals("pvzce:basic_zombie", result.get().decision().cardId());
        assertEquals(2, result.get().decision().row());
        assertEquals(7, result.get().decision().column());
        assertEquals(0.7D, result.get().decision().confidence(), 1e-9D);

        // The key went out as a bearer token and never into the body, which is what the model sees.
        assertEquals("Bearer test-key", lastAuth.get());
        assertFalse(lastBody.get().contains("test-key"));
        assertTrue(lastBody.get().contains("\"model\":\"typesafe/jev-1.13\""));
        assertTrue(lastBody.get().contains("\"role\":\"zombie\""));
        assertTrue(lastBody.get().contains("row_2"));
    }

    @Test
    void anHttpFailureIsReportedRatherThanThrown() throws IOException {
        AiSettings settings = startServer(401, "{\"error\":\"no credits\"}", 0);
        client = new JevClient();

        assertTrue(client.request(settings, prompt(), 1L));
        Optional<JevClient.Result> result = await(client, 5000);
        assertTrue(result.isPresent());
        assertFalse(result.get().usable());
        assertTrue(result.get().error().contains("401"), result.get().error());
        assertTrue(result.get().error().contains("no credits"),
                "the provider's own words are what makes a 401 diagnosable");
    }

    @Test
    void anAnswerThatNamesNothingUsableIsAnError() throws IOException {
        AiSettings settings = startServer(200, "{\"code\":0,\"data\":{\"answers\":{}}}", 0);
        client = new JevClient();

        assertTrue(client.request(settings, prompt(), 1L));
        Optional<JevClient.Result> result = await(client, 5000);
        assertTrue(result.isPresent());
        assertFalse(result.get().usable());
        assertTrue(result.get().error().contains("unusable"), result.get().error());
    }

    @Test
    void oneRequestAtATime() throws IOException {
        String answer = "{\"answers\":{\"action\":{\"choice\":\"hold\"},"
                + "\"row\":{\"choice\":\"row_2\"},\"column\":{\"choice\":\"col_7\"}}}";
        AiSettings settings = startServer(200, answer, 400);
        client = new JevClient();

        assertTrue(client.request(settings, prompt(), 1L), "the first request should go out");
        assertTrue(client.busy());
        assertFalse(client.request(settings, prompt(), 2L),
                "a second question about the same board must not queue behind the first");
        Optional<JevClient.Result> result = await(client, 5000);
        assertTrue(result.isPresent());
        assertTrue(result.get().decision().isHold());
        assertFalse(client.busy());
    }

    @Test
    void aProviderThatNeverAnswersBecomesAnErrorAndFreesTheSlot() throws IOException {
        AiSettings settings = startServer(200, "{}", 2000);
        client = new JevClient(Duration.ofSeconds(2), Duration.ofMillis(250));

        assertTrue(client.request(settings, prompt(), 1L));
        Optional<JevClient.Result> result = await(client, 5000);
        assertTrue(result.isPresent());
        assertFalse(result.get().usable());
        assertNotNull(result.get().error());
        assertFalse(client.busy(), "the slot has to come back, or the level stops deciding for good");
    }

    @Test
    void nothingIsSentWithoutAKey() throws IOException {
        startServer(200, "{}", 0);
        client = new JevClient();

        assertFalse(client.request(AiSettings.NONE, prompt(), 1L));
        assertFalse(client.request(new AiSettings("http://127.0.0.1:1/x", "", ""), prompt(), 1L));
        assertFalse(client.busy());
        assertTrue(client.poll().isEmpty());
    }
}
