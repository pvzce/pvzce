package com.pvzce.client;

import com.pvzce.client.gui.GuiLang;
import com.pvzce.common.resource.PvzceResourceManager;
import com.pvzce.common.tag.TestContent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * A server line that is a language key reaches the player as its sentence.
 *
 * <p>The refusals of the zombie zone and of the boss pilot are sent as keys. They were looked up as
 * content ids ({@code pvzce.<key>}), found nothing, and reached the player as the raw key.
 */
class ServerMessageTranslationTest {
    @Test
    void aLineThatIsALanguageKeyIsShownAsItsSentence() throws Exception {
        try (PvzceResourceManager resources = TestContent.loadBuiltInContentAndTags()) {
            GuiLang.reload(resources, "zh_cn");
            for (String key : new String[]{"gui.pvzce.versus.zombie_zone", "gui.pvzce.zomboss.refused.cooldown"}) {
                String sentence = GuiLang.raw(key, key);
                assertNotEquals(key, sentence, key + " is in the loaded language");
                assertEquals(sentence, PvzceClient.localizeServerMessage(key));
            }
            assertEquals("+25 阳光", PvzceClient.localizeServerMessage("+25 阳光"), "a sentence passes through unchanged");
        }
    }
}
