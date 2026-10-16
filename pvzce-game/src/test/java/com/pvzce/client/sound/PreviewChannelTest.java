package com.pvzce.client.sound;

import com.pvzce.client.gui.Screen;
import com.pvzce.client.gui.ScreenStack;
import com.pvzce.client.gui.workshop.WorkshopPreviewScreen;
import com.pvzce.testutil.ClientHarness;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PreviewChannelTest {
    @Test void auditionsReplaceEachOtherAndOldPagesCannotStopANewerAudition() {
        List<String> calls = new ArrayList<>();
        var channel = new PreviewChannel(id -> calls.add("play:" + id), () -> calls.add("stop"));
        var first = channel.open();
        var second = channel.open();
        first.play("garden:first");
        first.play("garden:second");
        second.play("garden:third");
        first.close();
        assertEquals(List.of("play:garden:first", "stop", "play:garden:second", "stop", "play:garden:third"), calls);
        second.stop();
        second.stop();
        second.play("garden:fourth");
        channel.stopAll();
        second.close();
        assertEquals(List.of("play:garden:first", "stop", "play:garden:second", "stop", "play:garden:third",
                "stop", "play:garden:fourth", "stop"), calls);
        first.play("garden:closed");
        assertFalse(calls.contains("play:garden:closed"));
    }

    @Test void previewPagesStopWhenCoveredRemovedOrSuspendedAndCanAuditionAgainAfterReturning() throws Exception {
        try (var harness = ClientHarness.create("workshop-audition")) {
            var calls = new ArrayList<String>();
            var channel = new PreviewChannel(id -> calls.add("play:" + id), () -> calls.add("stop"));
            var session = channel.open();
            var client = harness.client();
            var root = new Screen(client) { public void render() {} };
            var preview = new WorkshopPreviewScreen(client, null, "assets/garden/sounds/sample.ogg");
            var field = WorkshopPreviewScreen.class.getDeclaredField("audition");
            field.setAccessible(true);field.set(preview, session);
            var stack = new ScreenStack(client);
            stack.replace(root);stack.push(preview);
            session.play("garden:first");
            stack.push(new Screen(client) { public void render() {} });
            assertEquals(List.of("play:garden:first", "stop"), calls);
            stack.back();session.play("garden:returned");
            var saved = stack.suspend();
            assertEquals("stop", calls.getLast());
            assertEquals(List.of(preview, root), saved);
            stack.replace(root);stack.push(preview);session.play("garden:resumed");
            stack.back();
            int ended = calls.size();session.play("garden:removed");
            assertEquals(ended, calls.size());
            assertEquals(List.of("play:garden:first", "stop", "play:garden:returned", "stop", "play:garden:resumed", "stop"), calls);
        }
    }
}
