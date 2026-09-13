package com.pvzce.common.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pvzce.api.util.JsonPath;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The editor's JSON layer: addressing one field of a level file and leaving the rest alone.
 *
 * <p>These are the rules that decide whether opening a level in the editor and saving it is
 * safe. They used to be spread over a seventeen-parameter writer where "the editor does not
 * model this field any more" and "the editor deletes this field" were the same statement.
 */
class JsonDraftTest {

    @Test
    void aPathAddressesNestedObjectsAndArrayEntries() {
        JsonDraft draft = JsonDraft.of(JsonParser.parseString("""
                {"rewards": {"first_clear": [{"type": "unlock", "id": "pvzce:sunflower"}]}}
                """));
        assertEquals("unlock", draft.getString("rewards.first_clear[0].type", ""));
        assertEquals("pvzce:sunflower", draft.getString("rewards.first_clear[0].id", ""));
        assertTrue(draft.get("rewards.first_clear[1]").isEmpty(), "a missing index is empty, not an error");
        assertEquals("fallback", draft.getString("rewards.repeat[0].type", "fallback"));
    }

    @Test
    void settingAPathCreatesTheContainersItNeeds() {
        JsonDraft draft = JsonDraft.empty();
        draft.setString("mechanics[0].type", "pvzce:conveyor");
        draft.setInt("mechanics[0].capacity", 6);

        JsonObject written = draft.json();
        JsonArray mechanics = written.getAsJsonArray("mechanics");
        assertEquals(1, mechanics.size());
        assertEquals("pvzce:conveyor", mechanics.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals(6, mechanics.get(0).getAsJsonObject().get("capacity").getAsInt());
    }

    @Test
    void blankTextRemovesTheKeyInsteadOfWritingAnEmptyString() {
        JsonDraft draft = JsonDraft.of(JsonParser.parseString(
                "{\"description\":\"说明\",\"name\":\"关卡\"}").getAsJsonObject());
        draft.setStringOrRemove("description", "   ");
        assertFalse(draft.json().has("description"), "\"no description\" has one spelling");
        draft.setStringOrRemove("name", "新名字");
        assertEquals("新名字", draft.json().get("name").getAsString());
    }

    @Test
    void removingAnArrayEntryShiftsTheRest() {
        JsonDraft draft = JsonDraft.of(JsonParser.parseString(
                "{\"waves\":[{\"type\":\"small\"},{\"type\":\"final\"}]}").getAsJsonObject());
        assertTrue(draft.remove("waves[0]"));
        assertEquals(1, draft.getArray("waves").size());
        assertEquals("final", draft.getString("waves[0].type", ""));
        assertFalse(draft.remove("waves[5]"), "removing what is not there reports false");
    }

    @Test
    void aDraftNeverTouchesWhatItWasNotAskedToWrite() {
        JsonObject loaded = JsonParser.parseString("""
                {"id": "otherns:arena", "future_field": {"nested": [1, 2, 3]}, "env_vars": {}}
                """).getAsJsonObject();
        JsonDraft draft = JsonDraft.of(loaded);
        draft.setString("name", "竞技场");

        assertEquals("otherns:arena", draft.json().get("id").getAsString());
        assertTrue(draft.json().has("future_field"));
        // The draft is a copy: the caller's document is the file as loaded, not as edited.
        assertFalse(loaded.has("name"));
    }

    @Test
    void malformedPathsAreRejectedRatherThanGuessed() {
        JsonDraft draft = JsonDraft.empty();
        try {
            draft.setInt("a[", 1);
            throw new AssertionError("an unclosed index must not be accepted");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Unclosed index"));
        }
    }

    @Test
    void idArraysAreDeduplicatedInOrder() {
        JsonArray array = JsonDraft.idArray(List.of("pvzce:sun", "pvzce:pea_shooter", "pvzce:sun"));
        assertEquals(2, array.size());
        assertEquals("pvzce:sun", array.get(0).getAsString());
        assertEquals("pvzce:pea_shooter", array.get(1).getAsString());
    }

    @Test
    void jsonPathReportsWhatIsMissing() {
        JsonObject root = JsonParser.parseString("{\"a\":{\"b\":[1,2]}}").getAsJsonObject();
        assertTrue(JsonPath.get(root, "a.b[1]").isPresent());
        assertTrue(JsonPath.get(root, "a.b[2]").isEmpty());
        assertTrue(JsonPath.get(root, "a.c").isEmpty());
        assertTrue(JsonPath.get(root, "a.b.c").isEmpty(), "a scalar has no children");
        assertEquals(List.of("a", "b"), JsonPath.parse("a.b[0]").stream()
                .filter(segment -> !segment.indexed()).map(JsonPath.Segment::key).toList());
    }
}
