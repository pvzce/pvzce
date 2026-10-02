package com.pvzce.server.ai;

import com.google.gson.JsonObject;
import com.pvzce.common.jev.JevDecision;
import com.pvzce.common.jev.JevPrompt;
import com.pvzce.common.jev.AiSettings;

import org.slf4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The one place the simulation talks to Jev, and the reason it can afford to.
 *
 * <p>A decision is a network round trip of roughly a second, and the server tick cannot wait
 * for one. So a request is handed to a single worker thread and the answer is left in a queue
 * that the tick drains: {@link #request} never blocks, {@link #poll} never waits, and the two
 * are called from different threads. The level keeps simulating between them, and a decision
 * that arrives late is judged against the board as it is <em>then</em> - which is what
 * {@code JevBrain} re-checks anyway.
 *
 * <p><b>One request at a time.</b> {@link #request} refuses while another is in flight. With a
 * decision every three seconds and a round trip of about one, that leaves room to spare, and
 * it means a slow provider degrades into "fewer decisions" rather than into a queue of stale
 * ones arriving all at once. The next tick simply asks again.
 *
 * <p>The client is deliberately ignorant of what a decision means. It builds the body from a
 * {@link JevPrompt}, parses the answer with {@link JevDecision}, and hands over a
 * {@link Result} that is either a decision or an error to be logged - the game's own rules live
 * in the brain, so nothing here can spawn anything.
 *
 * <p>No retries. A failed request is not retried because the retry that matters is the next
 * scheduled decision two seconds later, which is cheaper and fresher than a resend of a
 * question about a board that has moved on.
 */
public final class JevClient implements AutoCloseable {
    private static final Logger LOGGER = org.slf4j.LoggerFactory.getLogger("PVZCE/Jev");
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(12);
    /** Longest error body kept for the log; a provider's stack trace is not worth a megabyte. */
    private static final int MAX_ERROR_BODY_CHARS = 400;

    private final HttpClient http;
    private final ExecutorService worker;
    private final ConcurrentLinkedQueue<Result> completed = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean inFlight = new AtomicBoolean();
    private final Duration requestTimeout;

    public JevClient() {
        this(DEFAULT_CONNECT_TIMEOUT, DEFAULT_REQUEST_TIMEOUT);
    }

    /** Visible for testing: the timeouts are the whole point of some of its cases. */
    JevClient(Duration connectTimeout, Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
        this.http = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        this.worker = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "pvzce-jev");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * One finished exchange: a decision the level may try to execute, or why there is none.
     *
     * @param ticket  the caller's own tag, so a stale answer can be recognised
     * @param decision what Jev said, when it said something usable
     * @param error   why there is no decision, otherwise
     * @param millis  how long the round trip took, for the log
     */
    public record Result(long ticket, JevDecision decision, String error, long millis) {
        public boolean usable() {
            return decision != null;
        }

        public static Result of(long ticket, JevDecision decision, long millis) {
            return new Result(ticket, decision, "", millis);
        }

        public static Result failed(long ticket, String error, long millis) {
            return new Result(ticket, null, error, millis);
        }
    }

    /** True while a request is out; the caller asks so it can skip a turn instead of queueing. */
    public boolean busy() {
        return inFlight.get();
    }

    /**
     * Sends one prompt, if nothing else is on the wire.
     *
     * @return false when a request is already in flight or the settings cannot make one
     */
    public boolean request(AiSettings settings, JevPrompt prompt, long ticket) {
        if (settings == null || !settings.configured() || prompt == null) {
            return false;
        }
        if (worker.isShutdown()) {
            return false;
        }
        if (!inFlight.compareAndSet(false, true)) {
            return false;
        }
        JsonObject body = prompt.requestBody(settings.model());
        worker.execute(() -> {
            long started = System.nanoTime();
            try {
                completed.add(exchange(settings, prompt, body.toString(), ticket, started));
            } catch (RuntimeException e) {
                // The worker thread must never die on a provider that answers badly.
                LOGGER.debug("Jev request failed outright", e);
                completed.add(Result.failed(ticket, "request failed: " + e.getMessage(),
                        elapsedMillis(started)));
            } finally {
                inFlight.set(false);
            }
        });
        return true;
    }

    /** The next finished exchange, or empty when nothing has come back yet. Never blocks. */
    public Optional<Result> poll() {
        return Optional.ofNullable(completed.poll());
    }

    /** Drops whatever arrived for a level that is no longer running. */
    public void clear() {
        completed.clear();
    }

    private Result exchange(AiSettings settings, JevPrompt prompt, String body, long ticket,
                            long started) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(settings.url()))
                .header("Authorization", "Bearer " + settings.key())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .timeout(requestTimeout)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // The URL is logged, the key is not: an exception message can quote the request line.
            return Result.failed(ticket, "cannot reach " + settings.url() + " (" + e + ")",
                    elapsedMillis(started));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.failed(ticket, "interrupted", elapsedMillis(started));
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return Result.failed(ticket, "HTTP " + response.statusCode() + " "
                    + abbreviate(response.body()), elapsedMillis(started));
        }
        Optional<JevDecision> decision = JevDecision.parse(response.body(), prompt);
        if (decision.isEmpty()) {
            return Result.failed(ticket, "unusable answer: " + abbreviate(response.body()),
                    elapsedMillis(started));
        }
        return Result.of(ticket, decision.get(), elapsedMillis(started));
    }

    private static long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.replace('\n', ' ').replace('\r', ' ').trim();
        return flat.length() <= MAX_ERROR_BODY_CHARS
                ? flat : flat.substring(0, MAX_ERROR_BODY_CHARS) + "...";
    }

    @Override
    public void close() {
        worker.shutdownNow();
    }
}
