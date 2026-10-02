package com.pvzce.server.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
 * The commander tier: one chat completion, asked once a minute, answered in prose.
 *
 * <p>Jev answers "play which card, where" every few seconds from a typed vocabulary. This asks the
 * other question - <em>what is this match actually about right now</em> - of a general model, and
 * hands the answer back as one line of intent that goes into Jev's prompt. The division is the
 * point: a language model is good at reading a board and naming a plan, and bad at being asked
 * sixty times a minute; Jev is the opposite.
 *
 * <p>Its own worker thread rather than the tactical client's, and deliberately: a slow commander
 * must never occupy the slot a three-second decision needs. One request in flight here too, because
 * a second strategy line about the same board is worth nothing.
 *
 * <p>The wire shape is the OpenAI-compatible chat completion that every provider in the settings
 * page speaks ({@code {model, messages}} in, {@code choices[0].message.content} out). Nothing here
 * knows what the model is; a provider that answers differently produces no directive, and the
 * opponent plays without one.
 */
public final class CommanderClient implements AutoCloseable {
    private static final Logger LOGGER = org.slf4j.LoggerFactory.getLogger("PVZCE/Commander");
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    /**
     * The commander's own timeout: longer than the tactical one on purpose.
     *
     * <p>It has a minute to answer and nobody is waiting on it; cutting it off at twelve seconds
     * would make a thinking model useless for the one job it is here to do.
     */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(45);
    private static final int MAX_ERROR_BODY_CHARS = 300;
    /** What the model is asked to do, once, as a system message. */
    private static final String SYSTEM_PROMPT =
            "You are the strategist for one side of a Plants vs. Zombies match. You will be given a "
                    + "snapshot of the board and you answer with a short plan for the next minute - "
                    + "at most two sentences, no lists, no preamble. Say what to prioritise "
                    + "(economy, a specific lane, a specific card, saving sun) and why, in the "
                    + "language the snapshot is written in. Do not repeat the numbers back.";

    /**
     * Two sentences is the whole answer; a bound this low is what keeps a minute-by-minute
     * strategist from costing more than the match.
     */
    private static final int MAX_ANSWER_TOKENS = 400;
    /**
     * Whether to ask the provider to turn reasoning off.
     *
     * <p>Measured against the default ({@code deepseek-flash}): without it, four thousand tokens of
     * {@code reasoning_content} came back and {@code content} was <b>empty</b> - the model had
     * thought itself out of budget on every call. With {@code {"thinking":{"type":"disabled"}}} the
     * same question returned two sentences in 24 tokens. Not every provider accepts the field, so a
     * rejection turns it off for the rest of the session and the request is retried without it.
     */
    private volatile boolean askThinkingOff = true;

    private final HttpClient http;
    private final ExecutorService worker;
    private final ConcurrentLinkedQueue<String> directives = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean inFlight = new AtomicBoolean();

    public CommanderClient() {
        this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        this.worker = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "pvzce-commander");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** True while a strategy request is out; the caller waits for it rather than asking twice. */
    public boolean busy() {
        return inFlight.get();
    }

    /**
     * Asks for a strategy line, if one is not already on its way.
     *
     * @param snapshot the board, as prose; see {@code JevBrain.commanderSnapshot}
     * @return false when a request is already in flight or the settings cannot make one
     */
    public boolean request(AiSettings settings, String snapshot) {
        if (settings == null || !settings.configured() || snapshot == null || snapshot.isBlank()) {
            return false;
        }
        if (worker.isShutdown() || !inFlight.compareAndSet(false, true)) {
            return false;
        }
        worker.execute(() -> {
            try {
                String directive = exchange(settings, snapshot);
                if (directive != null && !directive.isBlank()) {
                    directives.add(directive.trim());
                }
            } catch (RuntimeException e) {
                LOGGER.debug("commander request failed outright", e);
            } finally {
                inFlight.set(false);
            }
        });
        return true;
    }

    /** The next strategy line, or empty. Never blocks. */
    public Optional<String> poll() {
        return Optional.ofNullable(directives.poll());
    }

    public void clear() {
        directives.clear();
    }

    private String exchange(AiSettings settings, String snapshot) {
        JsonObject body = new JsonObject();
        body.addProperty("model", settings.model());
        body.addProperty("max_tokens", MAX_ANSWER_TOKENS);
        if (askThinkingOff) {
            JsonObject thinking = new JsonObject();
            thinking.addProperty("type", "disabled");
            body.add("thinking", thinking);
        }
        JsonArray messages = new JsonArray();
        messages.add(message("system", SYSTEM_PROMPT));
        messages.add(message("user", snapshot));
        body.add("messages", messages);
        HttpRequest request = HttpRequest.newBuilder(URI.create(settings.url()))
                .header("Authorization", "Bearer " + settings.key())
                .header("Content-Type", "application/json")
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            LOGGER.warn("commander unreachable at {} ({})", settings.url(), e.toString());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        if (isRejectedRequest(response.statusCode()) && askThinkingOff) {
            // The provider does not know this field. Ask once more without it, and remember for the
            // rest of the session: one extra round trip on the first call, none after that.
            LOGGER.info("commander provider rejected the thinking field (HTTP {}); retrying without it",
                    response.statusCode());
            askThinkingOff = false;
            return exchange(settings, snapshot);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            LOGGER.warn("commander answered HTTP {} {}", response.statusCode(),
                    abbreviate(response.body()));
            return null;
        }
        String text = readContent(response.body());
        if (text == null) {
            LOGGER.warn("commander answered without choices[0].message.content: {}",
                    abbreviate(response.body()));
        }
        return text;
    }

    /** True for the status codes a provider uses to say "I do not know that request field". */
    private static boolean isRejectedRequest(int status) {
        return status == 400 || status == 422;
    }

    private static JsonObject message(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content);
        return message;
    }

    /**
     * The assistant's text out of a chat completion.
     *
     * <p>Tolerant about the envelope for the same reason {@code JevDecision} is: the settings page
     * accepts any endpoint the player pastes, and a gateway that wraps the answer one level deeper
     * should still be usable. Only the content is read - the rest of the response is the provider's
     * business.
     */
    private static String readContent(String body) {
        try {
            var parsed = JsonParser.parseString(body);
            if (!parsed.isJsonObject()) {
                return null;
            }
            JsonObject root = parsed.getAsJsonObject();
            var choices = root.get("choices");
            if (choices != null && choices.isJsonArray() && !choices.getAsJsonArray().isEmpty()) {
                JsonObject first = choices.getAsJsonArray().get(0).getAsJsonObject();
                var message = first.get("message");
                if (message != null && message.isJsonObject()
                        && message.getAsJsonObject().get("content") != null) {
                    return message.getAsJsonObject().get("content").getAsString();
                }
                var text = first.get("text");
                if (text != null && text.isJsonPrimitive()) {
                    return text.getAsString();
                }
            }
            // A gateway that wraps the chat completion in its own envelope.
            var data = root.get("data");
            if (data != null && data.isJsonObject()) {
                return readContent(data.getAsJsonObject().toString());
            }
        } catch (RuntimeException e) {
            return null;
        }
        return null;
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
