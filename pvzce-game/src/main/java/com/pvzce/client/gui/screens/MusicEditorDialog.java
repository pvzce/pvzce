package com.pvzce.client.gui.screens;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.pvzce.api.util.Identifier;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.components.AbstractSelectionList;
import com.pvzce.client.gui.components.Button;
import com.pvzce.client.gui.components.Dialog;
import com.pvzce.client.gui.components.EditBox;
import com.pvzce.common.core.BuiltInRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Full background-music timeline editor. Every cue maps 1:1 to
 * {@code LevelDef.MusicCue}: at_tick, track, event, loop, stop, volume and
 * fade_seconds.
 *
 * <p>Times are edited as seconds for readability, then persisted as integer
 * ticks at 60tps. A level with no {@code music} field opens with the implicit
 * default grasswalk cue; an explicit {@code cues: []} opens absolutely empty.</p>
 */
public final class MusicEditorDialog extends Dialog {
    public static final String STOP_LABEL = "（无 / 停止）";

    public static final class Config {
        public final List<CueModel> cues = new ArrayList<>();

        public static Config fromJson(JsonObject root) {
            Config config = new Config();
            JsonObject music = root.has("music") && root.get("music").isJsonObject()
                    ? root.getAsJsonObject("music") : null;
            if (music == null) {
                // No field => LevelDef's implicit default grasswalk.
                CueModel cue = new CueModel();
                cue.event = "pvzce:music/grasswalk";
                config.cues.add(cue);
                return config;
            }
            if (!music.has("cues") || !music.get("cues").isJsonArray()) {
                return config;
            }
            for (JsonElement element : music.getAsJsonArray("cues")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject cueJson = element.getAsJsonObject();
                CueModel cue = new CueModel();
                cue.atTick = cueJson.has("at_tick") ? cueJson.get("at_tick").getAsInt() : 0;
                cue.track = cueJson.has("track") ? cueJson.get("track").getAsString() : "background";
                if (cueJson.has("event") && !cueJson.get("event").isJsonNull()) {
                    cue.event = cueJson.get("event").getAsString();
                }
                cue.loop = !cueJson.has("loop") || cueJson.get("loop").getAsBoolean();
                cue.stop = cueJson.has("stop") && cueJson.get("stop").getAsBoolean();
                cue.volume = cueJson.has("volume") ? cueJson.get("volume").getAsFloat() : 1F;
                cue.fadeSeconds = cueJson.has("fade_seconds") ? cueJson.get("fade_seconds").getAsFloat() : 1F;
                config.cues.add(cue);
            }
            return config;
        }

        public JsonObject toJson() {
            JsonObject music = new JsonObject();
            JsonArray cuesJson = new JsonArray();
            for (CueModel cue : cues) {
                JsonObject cueJson = new JsonObject();
                cueJson.addProperty("at_tick", Math.max(0, cue.atTick));
                cueJson.addProperty("track", cue.track);
                if (cue.event != null && !cue.event.isEmpty()) {
                    cueJson.addProperty("event", cue.event);
                }
                cueJson.addProperty("loop", cue.loop);
                cueJson.addProperty("stop", cue.stop);
                cueJson.addProperty("volume", cue.volume);
                cueJson.addProperty("fade_seconds", cue.fadeSeconds);
                cuesJson.add(cueJson);
            }
            music.add("cues", cuesJson);
            return music;
        }

        public void replaceWith(Config other) {
            cues.clear();
            for (CueModel otherCue : other.cues) {
                CueModel copy = new CueModel();
                copy.atTick = otherCue.atTick;
                copy.track = otherCue.track;
                copy.event = otherCue.event;
                copy.loop = otherCue.loop;
                copy.stop = otherCue.stop;
                copy.volume = otherCue.volume;
                copy.fadeSeconds = otherCue.fadeSeconds;
                cues.add(copy);
            }
        }
    }

    public static final class CueModel {
        public int atTick;
        public String track = "background";
        public String event = "";
        public boolean loop = true;
        public boolean stop;
        public float volume = 1F;
        public float fadeSeconds = 1F;
    }

    private final PvzceClient client;
    private final Config config;
    private AbstractSelectionList<CueModel> cueList;
    private AbstractSelectionList<String> eventList;
    private Button trackButton;
    private Button loopButton;
    private Button stopButton;
    private EditBox tickBox;
    private EditBox volumeBox;
    private EditBox fadeBox;
    private CueModel currentCue;
    private String lastEventChoice;
    private int listTopY;

    public MusicEditorDialog(PvzceClient client, int x, int y, int width, int height,
                             Config config, Runnable onClose) {
        super(x, y, width, height, "背景音乐");
        this.client = client;
        this.config = config;
        this.onClose(onClose);
        rebuild();
    }

    public void open() {
        setVisible(true);
        rebuild();
    }

    @Override
    public void close() {
        commitFields(currentCue);
        super.close();
    }

    public void tick() {
        if (!visible) {
            return;
        }
        commitFields(currentCue);
        CueModel selected = cueList == null ? null : cueList.selected();
        if (selected != currentCue) {
            currentCue = selected;
            refreshEditor();
        }
        if (currentCue != null) {
            trackButton.setLabel("轨道: " + currentCue.track);
            loopButton.setLabel(currentCue.loop ? "循环: 开" : "循环: 关");
            stopButton.setLabel(currentCue.stop ? "停止: 开" : "停止: 关");
        }
        String chosen = eventList == null ? null : eventList.selected();
        if (chosen != null && !chosen.equals(lastEventChoice) && currentCue != null) {
            lastEventChoice = chosen;
            if (STOP_LABEL.equals(chosen)) {
                currentCue.event = "";
                currentCue.stop = true;
            } else {
                currentCue.event = chosen;
                currentCue.stop = false;
            }
            stopButton.setLabel(currentCue.stop ? "停止: 开" : "停止: 关");
            refreshCueListKeepSelection(currentCue);
        }
    }

    private void rebuild() {
        clearChildren();
        currentCue = null;
        lastEventChoice = null;

        int pad = 12;
        int bottom = y + 12;
        int top = y + height - 42;
        int leftWidth = Math.max(150, Math.min(220, width / 3));
        int leftX = x + pad;
        int rightX = leftX + leftWidth + pad;
        int rightWidth = Math.max(180, x + width - pad - rightX);
        rowTop = top - 34;
        rowFieldsY = top - 68;
        listTopY = top - 96;
        int listHeight = Math.max(40, listTopY - (bottom + 84));

        cueList = new AbstractSelectionList<>(leftX, bottom + 84, leftWidth, listHeight, 26,
                (renderClient, cue, cx, cy) ->
                        renderClient.font().draw(cueSummary(cue), cx, cy + 5, 0.72F, 1F, 1F, 1F, 1F));
        addChild(cueList);

        int buttonWidth = Math.max(30, (leftWidth - 9) / 4);
        addChild(new Button(leftX, bottom + 4, buttonWidth, 28, "新增", this::addCue));
        addChild(new Button(leftX + buttonWidth + 3, bottom + 4, buttonWidth, 28, "删除", this::removeCue));
        addChild(new Button(leftX + (buttonWidth + 3) * 2, bottom + 4, buttonWidth, 28, "上移",
                () -> moveCue(-1)));
        addChild(new Button(leftX + (buttonWidth + 3) * 3, bottom + 4, buttonWidth, 28, "下移",
                () -> moveCue(1)));

        int doneWidth = Math.min(90, Math.max(60, rightWidth / 4));
        addChild(new Button(rightX + rightWidth - doneWidth, top - 2, doneWidth, 28, "完成", this::close));

        int smallWidth = Math.max(74, Math.min(120, (rightWidth - 12) / 3));
        tickBox = new EditBox(rightX + 62, rowTop, smallWidth, 28, () -> commitFields(currentCue));
        volumeBox = new EditBox(rightX + 62, rowFieldsY, smallWidth, 28, () -> commitFields(currentCue));
        fadeBox = new EditBox(rightX + 62 + smallWidth + 8, rowFieldsY, smallWidth, 28,
                () -> commitFields(currentCue));
        addChild(tickBox);
        addChild(volumeBox);
        addChild(fadeBox);

        trackButton = new Button(rightX, rowTop, 58, 28, "轨道", this::cycleTrack);
        loopButton = new Button(rightX + rightWidth - 174, rowTop, 82, 28, "循环: 开", this::toggleLoop);
        stopButton = new Button(rightX + rightWidth - 86, rowTop, 82, 28, "停止: 关", this::toggleStop);
        addChild(trackButton);
        addChild(loopButton);
        addChild(stopButton);

        List<String> events = new ArrayList<>();
        events.add(STOP_LABEL);
        BuiltInRegistries.SOUND_EVENTS.keySet().stream()
                .filter(id -> id.path().startsWith("music/"))
                .map(Identifier::toString)
                .sorted()
                .forEach(events::add);
        int eventWidth = Math.max(120, rightWidth);
        eventList = new AbstractSelectionList<>(rightX, bottom + 84, eventWidth,
                Math.max(40, listTopY - (bottom + 84)), 24,
                (renderClient, event, ex, ey) ->
                        renderClient.font().draw(event, ex, ey + 4, 0.68F, 0.9F, 0.95F, 0.9F, 1F));
        eventList.setEntries(events);
        addChild(eventList);

        refreshCueList();
    }

    private int rowTop;
    private int rowFieldsY;

    @Override
    public void render(PvzceClient renderClient) {
        super.render(renderClient);
        if (!visible) {
            return;
        }
        drawLabel(renderClient, "时间(秒)", tickBox.x() - 54, rowTop + 8);
        drawLabel(renderClient, "音量", volumeBox.x() - 30, rowFieldsY + 8);
        drawLabel(renderClient, "淡入", fadeBox.x() - 30, rowFieldsY + 8);
        drawLabel(renderClient, "时间轴 cue", cueList.x(), listTopY + 4);
        drawLabel(renderClient, "音乐事件", eventList.x(), listTopY + 4);
        if (currentCue == null) {
            drawLabel(renderClient, "暂无 cue，点击左侧“新增”创建", eventList.x() + 4,
                    eventList.y() + eventList.height() / 2F);
        }
    }

    private static void drawLabel(PvzceClient client, String text, float x, float y) {
        client.font().draw(text, x, y, 0.75F, 1F, 1F, 1F, 1F);
    }

    private void refreshCueList() {
        currentCue = ListEditorSupport.refresh(cueList, new ArrayList<>(config.cues), currentCue);
        refreshEditor();
    }

    private void refreshCueListKeepSelection(CueModel cue) {
        ListEditorSupport.refreshKeeping(cueList, new ArrayList<>(config.cues), cue);
        currentCue = cue;
        refreshEditorValues(cue);
    }

    private void refreshEditor() {
        boolean hasCue = currentCue != null;
        tickBox.setActive(hasCue);
        volumeBox.setActive(hasCue);
        fadeBox.setActive(hasCue);
        if (!hasCue) {
            tickBox.setValue("", false);
            volumeBox.setValue("", false);
            fadeBox.setValue("", false);
            trackButton.setLabel("轨道: -");
            return;
        }
        refreshEditorValues(currentCue);
    }

    private void refreshEditorValues(CueModel cue) {
        tickBox.setValue(formatSeconds(cue.atTick / 60F), false);
        volumeBox.setValue(formatFloat(cue.volume), false);
        fadeBox.setValue(formatFloat(cue.fadeSeconds), false);
        trackButton.setLabel("轨道: " + cue.track);
        loopButton.setLabel(cue.loop ? "循环: 开" : "循环: 关");
        stopButton.setLabel(cue.stop ? "停止: 开" : "停止: 关");
        List<String> entries = eventList.entries();
        int index = entries.indexOf(cue.event.isEmpty() ? STOP_LABEL : cue.event);
        eventList.select(index >= 0 ? index : 0);
        lastEventChoice = eventList.selected();
    }

    private void commitFields(CueModel cue) {
        if (cue == null || tickBox == null) {
            return;
        }
        cue.atTick = parseSeconds(tickBox.value(), cue.atTick);
        cue.volume = parseFloat(volumeBox.value(), cue.volume, 0F, 1F);
        cue.fadeSeconds = parseFloat(fadeBox.value(), cue.fadeSeconds, 0F, 60F);
    }

    private void addCue() {
        commitFields(currentCue);
        CueModel cue = new CueModel();
        cue.atTick = currentCue == null ? 0 : currentCue.atTick;
        cue.track = "background";
        cue.event = "pvzce:music/grasswalk";
        config.cues.add(cue);
        currentCue = cue;
        refreshCueList();
        cueList.select(config.cues.size() - 1);
        refreshEditor();
    }

    private void removeCue() {
        if (currentCue == null) {
            return;
        }
        int index = config.cues.indexOf(currentCue);
        if (index >= 0) {
            config.cues.remove(index);
        }
        currentCue = null;
        refreshCueList();
    }

    private void moveCue(int delta) {
        if (currentCue == null) {
            return;
        }
        if (!ListEditorSupport.move(config.cues, currentCue, delta)) {
            return;
        }
        refreshCueList();
        cueList.select(config.cues.indexOf(currentCue));
    }

    private void cycleTrack() {
        if (currentCue == null) {
            return;
        }
        currentCue.track = switch (currentCue.track) {
            case "background" -> "battle";
            case "battle" -> "stinger";
            case "stinger" -> "menu";
            default -> "background";
        };
        trackButton.setLabel("轨道: " + currentCue.track);
        refreshCueListKeepSelection(currentCue);
    }

    private void toggleLoop() {
        if (currentCue == null) {
            return;
        }
        currentCue.loop = !currentCue.loop;
        loopButton.setLabel(currentCue.loop ? "循环: 开" : "循环: 关");
        refreshCueListKeepSelection(currentCue);
    }

    private void toggleStop() {
        if (currentCue == null) {
            return;
        }
        currentCue.stop = !currentCue.stop;
        if (currentCue.stop) {
            currentCue.event = "";
        }
        stopButton.setLabel(currentCue.stop ? "停止: 开" : "停止: 关");
        refreshEditorValues(currentCue);
    }

    private static int parseSeconds(String text, int fallbackTicks) {
        try {
            float seconds = Float.parseFloat(text.trim());
            return Math.max(0, Math.round(seconds * 60F));
        } catch (RuntimeException e) {
            return fallbackTicks;
        }
    }

    private static float parseFloat(String text, float fallback, float min, float max) {
        return com.pvzce.client.gui.GuiText.parseFloat(text, fallback, min, max);
    }

    private static String formatSeconds(float seconds) {
        return com.pvzce.client.gui.GuiText.formatFloat(seconds);
    }

    private static String formatFloat(float value) {
        return com.pvzce.client.gui.GuiText.formatFloat(value);
    }

    private static String cueSummary(CueModel cue) {
        String event = cue.stop || cue.event.isEmpty() ? "停止" : shortId(cue.event);
        return formatSeconds(cue.atTick / 60F) + "s " + cue.track + " " + event;
    }

    private static String shortId(String id) {
        return com.pvzce.client.gui.GuiText.shortId(id);
    }
}
