package com.pvzce.client.gui.screens;

import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.Screen;
import com.pvzce.client.gui.components.EditBox;
import com.pvzce.common.network.packet.CommandC2S;
import com.pvzce.common.network.packet.RequestSuggestionsC2S;
import com.pvzce.common.network.packet.SuggestionsS2C;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/** MC ChatScreen + CommandSuggestions shaped command input. */
public final class ConsoleScreen extends Screen {
    private static final int SUGGESTION_LINE_LIMIT = 10;
    private static final int SUGGESTION_LINE_HEIGHT = 22;
    private static final int SUGGESTION_Y = 40;

    private final List<String> recentCommands = new ArrayList<>();
    private final List<SuggestionsS2C.Suggestion> suggestions = new ArrayList<>();
    private final String initialContents;
    private EditBox input;
    private String originalContents = "";
    private int suggestionOffset;
    private int current;
    private boolean tabCycles;
    private boolean applyingSuggestion;

    private int suggestionRequestSerial;
    private int pendingRequestId;
    private String pendingRequestText = "";

    private int historyPos = -1;
    private String historyBuffer = "";
    private int messageScroll;

    public ConsoleScreen(PvzceClient client) {
        this(client, "");
    }

    /** {@code initialContents} is pre-filled without going through the char-event queue. */
    public ConsoleScreen(PvzceClient client, String initialContents) {
        super(client);
        this.initialContents = initialContents == null ? "" : initialContents;
    }

    @Override
    protected void init() {
        input = new EditBox(4, 4, Math.max(160, client.guiWidth() - 8), 28, 256, this::submit);
        input.setBordered(false);
        input.setFocused(true);
        addWidget(input);
        input.setValue(initialContents, false);
        input.setValueChangedListener(this::onInputChanged);
    }

    private void onInputChanged() {
        if (applyingSuggestion) {
            return;
        }
        clearSuggestions();
        String text = input.value();
        if (text.startsWith("/")) {
            requestSuggestions();
        } else {
            pendingRequestId = 0;
            pendingRequestText = "";
        }
    }

    /** MC updateCommandInfo: every command text change requests fresh suggestions. */
    private void requestSuggestions() {
        int requestId = ++suggestionRequestSerial;
        pendingRequestId = requestId;
        pendingRequestText = input.value();
        client.connection().send(new RequestSuggestionsC2S(pendingRequestText, requestId));
    }

    private void submit() {
        String command = input.value().trim();
        if (command.isEmpty()) {
            client.closeScreen();
            return;
        }
        if (!command.startsWith("/")) {
            client.level().addMessage("请输入 / 开头的命令");
            input.setValue("", false);
            return;
        }
        recentCommands.add(command);
        while (recentCommands.size() > 50) {
            recentCommands.remove(0);
        }
        historyPos = -1;
        historyBuffer = "";
        String payload = command.substring(1).trim();
        if (!payload.isEmpty()) {
            client.connection().send(new CommandC2S(payload));
        }
        input.setValue("", false);
        clearSuggestions();
        pendingRequestId = 0;
        pendingRequestText = "";
        messageScroll = 0;
    }

    @Override
    public void tick() {
        SuggestionsS2C packet;
        boolean applied = false;
        while ((packet = client.level().suggestions().poll()) != null) {
            if (packet.requestId() == pendingRequestId && pendingRequestText.equals(input.value())) {
                suggestions.clear();
                suggestions.addAll(packet.suggestions());
                suggestionOffset = 0;
                current = 0;
                originalContents = input.value();
                tabCycles = false;
                applied = true;
            }
        }
        if (applied && suggestions.isEmpty()) {
            // A stale/empty response must not leave the "currently shown" state behind.
            pendingRequestId = 0;
            pendingRequestText = "";
        }
    }

    @Override
    public void keyPressed(int key) {
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            client.closeScreen();
            return;
        }
        if (!suggestions.isEmpty()) {
            if (key == GLFW.GLFW_KEY_UP) {
                cycle(-1);
                tabCycles = false;
                return;
            }
            if (key == GLFW.GLFW_KEY_DOWN) {
                cycle(1);
                tabCycles = false;
                return;
            }
            if (key == GLFW.GLFW_KEY_TAB) {
                if (tabCycles) {
                    cycle(1);
                }
                applySuggestion(current);
                tabCycles = true;
                return;
            }
        } else {
            if (key == GLFW.GLFW_KEY_UP) {
                moveInHistory(-1);
                return;
            }
            if (key == GLFW.GLFW_KEY_DOWN) {
                moveInHistory(1);
                return;
            }
            if (key == GLFW.GLFW_KEY_TAB) {
                requestSuggestions();
                return;
            }
        }
        if (key == GLFW.GLFW_KEY_PAGE_UP) {
            messageScroll += 8;
            return;
        }
        if (key == GLFW.GLFW_KEY_PAGE_DOWN) {
            messageScroll = Math.max(0, messageScroll - 8);
            return;
        }
        super.keyPressed(key);
    }

    private void cycle(int direction) {
        current += direction;
        int size = suggestions.size();
        if (current < 0) {
            current += size;
        }
        if (current >= size) {
            current -= size;
        }
        int lastVisible = suggestionOffset + SUGGESTION_LINE_LIMIT - 1;
        if (current < suggestionOffset) {
            suggestionOffset = Math.max(0, current);
        } else if (current > lastVisible) {
            suggestionOffset = Math.max(0, Math.min(current - SUGGESTION_LINE_LIMIT + 1, size - SUGGESTION_LINE_LIMIT));
        }
    }

    private void applySuggestion(int index) {
        if (index < 0 || index >= suggestions.size()) {
            return;
        }
        SuggestionsS2C.Suggestion suggestion = suggestions.get(index);
        applyingSuggestion = true;
        try {
            input.setValue(suggestion.apply(originalContents), false);
        } finally {
            applyingSuggestion = false;
        }
        current = index;
    }

    private void clearSuggestions() {
        suggestions.clear();
        suggestionOffset = 0;
        current = 0;
        originalContents = "";
        tabCycles = false;
    }

    private void moveInHistory(int delta) {
        int max = recentCommands.size();
        int nextPos = Math.max(0, Math.min(max, historyPos + delta));
        if (nextPos == historyPos) {
            return;
        }
        if (nextPos == max) {
            historyPos = max;
            input.setValue(historyBuffer, false);
        } else {
            if (historyPos == max) {
                historyBuffer = input.value();
            }
            historyPos = nextPos;
            input.setValue(recentCommands.get(nextPos), false);
        }
        clearSuggestions();
        if (input.value().startsWith("/")) {
            requestSuggestions();
        }
    }

    @Override
    protected void onMouseClicked(double guiX, double guiY, int button) {
        if (!suggestions.isEmpty() && button == 0 && inSuggestionRect(guiX, guiY)) {
            int line = suggestionOffset + (int) ((guiY - suggestionRectY()) / SUGGESTION_LINE_HEIGHT);
            if (line >= 0 && line < suggestions.size()) {
                applySuggestion(line);
                tabCycles = true;
            }
            return;
        }
    }

    @Override
    protected void onMouseScrolled(double guiX, double guiY, double amount) {
        if (!suggestions.isEmpty()) {
            if (inSuggestionRect(guiX, guiY)) {
                suggestionOffset = Math.max(0, Math.min(suggestionOffset - (int) amount,
                        Math.max(0, suggestions.size() - SUGGESTION_LINE_LIMIT)));
                return;
            }
        }
        messageScroll = Math.max(0, messageScroll - (int) amount);
    }

    /** Left edge of the suggestion popup. */
    private static final int SUGGESTION_X = 6;

    private int suggestionRectY() {
        return SUGGESTION_Y;
    }

    private int suggestionRectWidth() {
        return Math.max(160, suggestionWidth() + 4);
    }

    private int suggestionRectHeight() {
        return Math.min(suggestions.size(), SUGGESTION_LINE_LIMIT) * SUGGESTION_LINE_HEIGHT;
    }

    /** One hit test for the popup, shared by click, scroll and hover. */
    private boolean inSuggestionRect(double guiX, double guiY) {
        return guiX >= SUGGESTION_X && guiX < SUGGESTION_X + suggestionRectWidth()
                && guiY >= suggestionRectY() && guiY < suggestionRectY() + suggestionRectHeight();
    }

    private int suggestionWidth() {
        int width = 0;
        for (SuggestionsS2C.Suggestion suggestion : suggestions) {
            width = Math.max(width, Math.round(client.font().width(suggestion.text(), 0.8F)));
        }
        return width;
    }

    @Override
    public void render() {
        client.beginGuiView();
        client.drawSolid(0, 0, client.guiWidth(), 38, 0.5F, 0F, 0F, 0F, 0.55F);
        renderMessages();
        renderSuggestions();
        for (var widget : widgets) {
            widget.render(client);
        }
    }

    private void renderMessages() {
        List<String> messages = new ArrayList<>(client.level().messages());
        messages.addAll(recentCommands);
        int maxLines = Math.max(4, (client.guiHeight() - 80) / 22);
        // Clamp before drawing: an unclamped scroll past the oldest message made
        // every iteration "skip" and left the chat area completely blank.
        int maxScroll = Math.max(0, messages.size() - maxLines);
        messageScroll = Math.max(0, Math.min(messageScroll, maxScroll));
        int skip = messageScroll;
        int y = 46;
        int shown = 0;
        for (int i = messages.size() - 1; i >= 0 && shown < maxLines; i--) {
            if (skip-- > 0) {
                continue;
            }
            float alpha = Math.max(0.25F, 1F - shown * 0.08F);
            client.font().draw(messages.get(i), 8, y, 0.85F, 0.9F, 0.9F, 0.9F, alpha);
            y += 22;
            shown++;
        }
    }

    private void renderSuggestions() {
        if (suggestions.isEmpty()) {
            return;
        }
        int rectX = 6;
        int rectW = Math.max(160, suggestionWidth() + 4);
        int rectY = SUGGESTION_Y;
        int limit = Math.min(suggestions.size(), SUGGESTION_LINE_LIMIT);
        int rectH = limit * SUGGESTION_LINE_HEIGHT;
        boolean hasPrevious = suggestionOffset > 0;
        boolean hasNext = suggestions.size() > suggestionOffset + limit;
        int mouseX = (int) client.guiMouseX(client.window().cursorX());
        int guiMouseY = (int) client.guiMouseY(client.window().cursorY());

        if (hasPrevious || hasNext) {
            client.drawSolid(rectX, rectY - 2, rectW, 2, 0.1F, 0.25F, 0.25F, 0.25F, 1F);
            client.drawSolid(rectX, rectY + rectH, rectW, 2, 0.1F, 0.25F, 0.25F, 0.25F, 1F);
        }
        for (int i = 0; i < limit; i++) {
            int index = i + suggestionOffset;
            SuggestionsS2C.Suggestion suggestion = suggestions.get(index);
            int rowY = rectY + i * SUGGESTION_LINE_HEIGHT;
            boolean hovered = mouseX >= rectX && mouseX < rectX + rectW
                    && guiMouseY >= rowY && guiMouseY < rowY + SUGGESTION_LINE_HEIGHT;
            if (hovered) {
                current = index;
            }
            boolean selected = index == current;
            client.drawSolid(rectX, rowY, rectW, SUGGESTION_LINE_HEIGHT, 0.1F,
                    selected ? 0.25F : 0.05F, selected ? 0.25F : 0.05F, selected ? 0.25F : 0.05F, 0.88F);
            client.font().draw(suggestion.text(), rectX + 2, rowY + 4, 0.8F,
                    selected ? 1F : 0.75F, selected ? 1F : 0.75F, selected ? 1F : 0.75F, 1F);
        }
    }
}
