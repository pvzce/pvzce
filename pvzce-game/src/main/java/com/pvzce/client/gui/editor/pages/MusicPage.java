package com.pvzce.client.gui.editor.pages;

import com.pvzce.api.util.Identifier;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.GuiLang;
import com.pvzce.client.gui.GuiText;
import com.pvzce.client.gui.components.AbstractSelectionList;
import com.pvzce.client.gui.components.Button;
import com.pvzce.client.gui.components.EditBox;
import com.pvzce.client.gui.editor.EditorContext;
import com.pvzce.client.gui.editor.EditorPage;
import com.pvzce.client.gui.editor.LevelFileWriter;
import com.pvzce.client.gui.screens.MusicEditorModel;
import com.pvzce.common.core.BuiltInRegistries;

import java.util.ArrayList;
import java.util.List;

/**
 * The music page: the cue timeline and the selected cue's parameters, on one screen.
 *
 * <p>Two columns: the timeline on the left, edited by the add / remove / move buttons above
 * it, and the selected cue's parameters on the right. The number boxes are committed when the
 * author presses Enter or saves; the event list below them is a picker rather than a field,
 * and choosing a row applies it to the cue immediately.
 *
 * <p>The page owns its slice of the level file like every other page: {@link #readFrom} loads
 * {@code music} into its model and {@link #writeTo} hands that model to
 * {@link LevelFileWriter#music}, so a level whose music page was never opened still keeps its
 * cues.
 */
public final class MusicPage implements EditorPage {
    private final MusicEditorModel.Config musicConfig = new MusicEditorModel.Config();
    private AbstractSelectionList<MusicEditorModel.CueModel> musicCueList;
    private AbstractSelectionList<String> musicEventList;
    private Button musicTrackButton;
    private Button musicLoopButton;
    private Button musicStopButton;
    private EditBox musicTickBox;
    private EditBox musicVolumeBox;
    private EditBox musicFadeBox;
    /** The cue the detail widgets were last built for; selection-change detection. */
    private MusicEditorModel.CueModel lastMusicCueShown;
    /** The event picker value that came from the model rather than from a click. */
    private String lastMusicEventChoice;

    @Override
    public String id() {
        return "music";
    }

    @Override
    public String label() {
        return GuiLang.raw("pvzce.editor.page.music", "music");
    }

    @Override
    public int order() {
        return 60;
    }

    @Override
    public boolean hasPalette() {
        return false;
    }

    /** The {@code music} block is the page's whole state; the widgets are rebuilt from it. */
    @Override
    public void readFrom(EditorContext context) {
        musicConfig.replaceWith(MusicEditorModel.Config.fromJson(context.draft().json()));
    }

    /**
     * Commits the boxes first: the detail panel's numbers are the model's to save, and a
     * half-typed tick must not be lost to a rebuild or to the save button, which is handled
     * before this frame's tick.
     */
    @Override
    public void writeTo(EditorContext context) {
        commitMusicFields();
        LevelFileWriter.music(context.draft(), musicConfig);
    }

    @Override
    public void build(EditorContext context) {
        EditorContext.Rect area = context.fullContent();
        int x = area.x();
        int y = area.y();
        int w = area.width();
        int h = area.height();
        int rowH = clamp(h / 16, 26, 34);
        int pad = 8;
        int gap = 10;

        int listW = (int) (w * 0.52F);
        int detailX = x + listW + gap;
        int detailW = w - listW - gap;

        int listTop = y + h - rowH - pad - 20;
        int listH = Math.max(60, listTop - (y + rowH + pad + 20));
        musicCueList = context.own(new AbstractSelectionList<MusicEditorModel.CueModel>(x, y + rowH + pad + 20,
                listW, listH, clamp(listH / 8, 28, 40), (renderClient, cue, rx, ry) -> {
        }));
        musicCueList.setEntryRenderer(this::renderMusicCueRow);
        musicCueList.setEntries(new ArrayList<>(musicConfig.cues));

        int bw = Math.max(70, listW / 5 - 6);
        int by = y + pad;
        context.own(new Button(x, by, bw, rowH, "新增提示音", this::addMusicCue));
        context.own(new Button(x + bw + 5, by, bw, rowH, "删除", this::removeMusicCue));
        context.own(new Button(x + (bw + 5) * 2, by, bw, rowH, "上移", () -> moveMusicCue(-1)));
        context.own(new Button(x + (bw + 5) * 3, by, bw, rowH, "下移", () -> moveMusicCue(1)));
        context.own(new Button(x + (bw + 5) * 4, by, Math.max(70, listW - (bw + 5) * 4), rowH,
                "清空时间线", () -> {
                    musicConfig.cues.clear();
                    refreshMusicCueList();
                    refreshMusicDetail();
                }));

        int fieldW = Math.max(56, (detailW - pad * 2 - gap) / 3);
        int top = y + h - rowH - pad;
        musicTickBox = context.own(new EditBox(detailX, top, fieldW, rowH, this::commitMusicFields));
        musicVolumeBox = context.own(new EditBox(detailX + fieldW + gap, top, fieldW, rowH,
                this::commitMusicFields));
        musicFadeBox = context.own(new EditBox(detailX + (fieldW + gap) * 2, top, fieldW, rowH,
                this::commitMusicFields));
        musicTrackButton = context.own(new Button(detailX, top - rowH - 6, fieldW * 2 + gap, rowH,
                "轨道：background", this::cycleMusicTrack));
        musicLoopButton = context.own(new Button(detailX + (fieldW * 2 + gap) + gap, top - rowH - 6,
                fieldW, rowH, "循环：开", this::toggleMusicLoop));
        musicStopButton = context.own(new Button(detailX, top - (rowH + 6) * 2, fieldW * 2 + gap, rowH,
                "停止：关", this::toggleMusicStop));

        int eventsTop = top - (rowH + 6) * 2 - 20;
        int eventsBottom = y + pad + rowH + 6;
        musicEventList = context.own(new AbstractSelectionList<String>(detailX, eventsBottom, detailW,
                Math.max(50, eventsTop - eventsBottom), clamp(rowH - 2, 24, 32),
                (renderClient, event, rx, ry) -> renderClient.font().draw(event, rx, ry + 4,
                        0.68F, 0.9F, 0.95F, 0.9F, 1F)));
        musicEventList.setEntries(musicEventOptions());

        refreshMusicDetail();
    }

    @Override
    public void render(EditorContext context) {
        EditorContext.Rect area = context.fullContent();
        PvzceClient renderClient = context.client();
        renderClient.font().draw("提示音 " + musicConfig.cues.size() + " 条　"
                        + "每条按 tick 触发，可选轨道 background / battle / menu / stinger",
                area.x() + 4, area.y() + area.height() + 4F, 0.74F, 1F, 1F, 1F, 1F);
        // Field labels in the gaps the fields leave; without them three bare
        // numbers in a row say nothing about which is which.
        if (musicTickBox != null) {
            renderClient.font().draw("tick　　/　音量　/　淡入秒", musicTickBox.x(),
                    musicTickBox.y() + musicTickBox.height() + 3F, 0.68F, 0.9F, 0.9F, 0.9F, 1F);
            renderClient.font().draw("事件（点选即应用）", musicEventList.x(),
                    musicEventList.y() + musicEventList.height() + 4F, 0.7F, 0.9F, 0.9F, 0.9F, 1F);
            renderClient.font().draw("时间线（tick / 轨道 / 事件）", area.x() + 4,
                    musicCueList.y() + musicCueList.height() + 4F, 0.7F, 0.9F, 0.9F, 0.9F, 1F);
        }
        if (musicConfig.cues.isEmpty()) {
            renderClient.font().draw("时间线为空：这个关卡不会播放任何音乐",
                    area.x() + 4, area.y() + area.height() / 2F, 0.85F, 1F, 0.85F, 0.5F, 1F);
        }
    }

    @Override
    public void tick(EditorContext context) {
        if (musicCueList != null) {
            // The detail panel follows the timeline's selection; comparing against the cue
            // it was last built for is what makes this cheap enough to run every frame.
            MusicEditorModel.CueModel selected = musicCueList.selected();
            if (selected != lastMusicCueShown) {
                lastMusicCueShown = selected;
                refreshMusicDetail();
            }
        }
        applyMusicEventChoice();
    }

    /**
     * The events a cue may name: stop, plus every registered {@code music/} sound.
     *
     * <p>Read from the sound-event registry rather than a literal list, so a data pack or a
     * mod that registers a music event gets it in this list.
     */
    private static List<String> musicEventOptions() {
        List<String> events = new ArrayList<>();
        events.add(MusicEditorModel.STOP_LABEL);
        BuiltInRegistries.SOUND_EVENTS.keySet().stream()
                .filter(id -> id.path().startsWith("music/"))
                .map(Identifier::toString)
                .sorted()
                .forEach(events::add);
        return events;
    }

    private void renderMusicCueRow(PvzceClient renderClient, MusicEditorModel.CueModel cue, int x, int y) {
        int rowH = musicCueList == null ? 30 : musicCueList.entryHeight();
        String event = cue.event == null || cue.event.isEmpty() ? MusicEditorModel.STOP_LABEL : cue.event;
        String head = "tick " + cue.atTick + "　" + cue.track;
        renderClient.font().draw(head, x, y + rowH / 2F + 1F, 0.76F, 1F, 1F, 1F, 1F);
        renderClient.font().draw(GuiText.shortId(event), x, y + rowH / 2F - 12F, 0.66F,
                cue.stop ? 0.95F : 0.8F, cue.stop ? 0.7F : 0.85F, 0.7F, 1F);
    }

    private MusicEditorModel.CueModel currentMusicCue() {
        return musicCueList == null ? null : musicCueList.selected();
    }

    private void refreshMusicDetail() {
        MusicEditorModel.CueModel cue = currentMusicCue();
        boolean has = cue != null;
        if (musicTickBox == null) {
            return;
        }
        musicTickBox.setActive(has);
        musicVolumeBox.setActive(has);
        musicFadeBox.setActive(has);
        musicTrackButton.setActive(has);
        musicLoopButton.setActive(has);
        musicStopButton.setActive(has);
        if (!has) {
            musicTickBox.setValue("", false);
            musicVolumeBox.setValue("", false);
            musicFadeBox.setValue("", false);
            musicTrackButton.setLabel("轨道：-");
            return;
        }
        if (!musicTickBox.isFocused()) {
            musicTickBox.setValue(String.valueOf(cue.atTick), false);
        }
        if (!musicVolumeBox.isFocused()) {
            musicVolumeBox.setValue(GuiText.formatFloat(cue.volume), false);
        }
        if (!musicFadeBox.isFocused()) {
            musicFadeBox.setValue(GuiText.formatFloat(cue.fadeSeconds), false);
        }
        musicTrackButton.setLabel("轨道：" + cue.track);
        musicLoopButton.setLabel(cue.loop ? "循环：开" : "循环：关");
        musicStopButton.setLabel(cue.stop ? "停止：开" : "停止：关");
    }

    private void commitMusicFields() {
        MusicEditorModel.CueModel cue = currentMusicCue();
        if (cue == null) {
            return;
        }
        cue.atTick = GuiText.parseInt(musicTickBox.value(), cue.atTick, 0, 10_000_000);
        cue.volume = GuiText.parseFloat(musicVolumeBox.value(), cue.volume, 0F, 4F);
        cue.fadeSeconds = GuiText.parseFloat(musicFadeBox.value(), cue.fadeSeconds, 0F, 60F);
    }

    private void cycleMusicTrack() {
        MusicEditorModel.CueModel cue = currentMusicCue();
        if (cue == null) {
            return;
        }
        cue.track = switch (cue.track) {
            case "menu" -> "battle";
            case "battle" -> "stinger";
            case "stinger" -> "background";
            default -> "menu";
        };
        refreshMusicDetail();
    }

    private void toggleMusicLoop() {
        MusicEditorModel.CueModel cue = currentMusicCue();
        if (cue == null) {
            return;
        }
        cue.loop = !cue.loop;
        refreshMusicDetail();
    }

    private void toggleMusicStop() {
        MusicEditorModel.CueModel cue = currentMusicCue();
        if (cue == null) {
            return;
        }
        cue.stop = !cue.stop;
        if (cue.stop) {
            cue.event = "";
        }
        refreshMusicDetail();
        refreshMusicCueList();
    }

    private void addMusicCue() {
        MusicEditorModel.CueModel cue = new MusicEditorModel.CueModel();
        cue.event = "pvzce:music/grasswalk";
        musicConfig.cues.add(cue);
        refreshMusicCueList();
        musicCueList.select(musicConfig.cues.size() - 1);
        refreshMusicDetail();
    }

    private void removeMusicCue() {
        int index = musicCueList == null ? -1 : musicCueList.selectedIndex();
        if (index < 0 || index >= musicConfig.cues.size()) {
            return;
        }
        musicConfig.cues.remove(index);
        refreshMusicCueList();
        if (!musicConfig.cues.isEmpty()) {
            musicCueList.select(Math.min(index, musicConfig.cues.size() - 1));
        }
        refreshMusicDetail();
    }

    private void moveMusicCue(int delta) {
        int index = musicCueList == null ? -1 : musicCueList.selectedIndex();
        int target = index + delta;
        if (index < 0 || target < 0 || target >= musicConfig.cues.size()) {
            return;
        }
        MusicEditorModel.CueModel cue = musicConfig.cues.remove(index);
        musicConfig.cues.add(target, cue);
        refreshMusicCueList();
        musicCueList.select(target);
        refreshMusicDetail();
    }

    private void refreshMusicCueList() {
        if (musicCueList != null) {
            int keep = musicCueList.selectedIndex();
            musicCueList.setEntries(new ArrayList<>(musicConfig.cues));
            if (keep >= 0 && keep < musicConfig.cues.size()) {
                musicCueList.select(keep);
            }
        }
    }

    /**
     * Applies the event list's selection to the cue being edited.
     *
     * <p>The list is not a form field with a commit, it is a picker: choosing
     * "（无 / 停止）" clears the event and sets stop, choosing a music event sets it and
     * clears stop. Guarded against the same choice so a click that lands on the already
     * selected row does not fight the author's own toggle edits.
     */
    private void applyMusicEventChoice() {
        MusicEditorModel.CueModel cue = currentMusicCue();
        String chosen = musicEventList == null ? null : musicEventList.selected();
        if (cue == null || chosen == null || chosen.equals(lastMusicEventChoice)) {
            return;
        }
        lastMusicEventChoice = chosen;
        if (MusicEditorModel.STOP_LABEL.equals(chosen)) {
            cue.event = "";
            cue.stop = true;
        } else {
            cue.event = chosen;
            cue.stop = false;
        }
        refreshMusicDetail();
        refreshMusicCueList();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
