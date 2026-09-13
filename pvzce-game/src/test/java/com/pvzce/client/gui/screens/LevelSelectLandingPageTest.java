package com.pvzce.client.gui.screens;

import com.pvzce.client.PvzceClient;
import com.pvzce.common.network.Connection;
import com.pvzce.common.network.PvzcePackets;
import com.pvzce.common.network.packet.LevelListS2C;
import com.pvzce.common.network.packet.LevelPayload;
import com.pvzce.common.network.packet.LevelTabsS2C;
import com.pvzce.common.network.packet.SceneSyncS2C;
import com.pvzce.common.network.packet.SeedOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Where the level list lands, and the bug that made it land on the empty bucket.
 *
 * <p>Reviewed symptom: opening 选择关卡 showed 主题=未分类 / 分类=未分类 and "这个分类下还没有关卡",
 * with every real page (庭院 / 冒险模式) one click away. The screen builds its widgets against
 * its own fallback tab table on the first frame - one unclassified page - because the server's
 * table arrives a round trip later. Index 0 of that fallback is the bucket, so the bucket was
 * chosen; when the real table arrived the choice was still "valid" (the bucket is always in the
 * table, appended last), so it was kept for the rest of the session.
 *
 * <p>The fix is that "first page" means the first page that lists levels. The bucket is the
 * fallback only when there is nothing else, which mirrors how the server builds the table.
 */
class LevelSelectLandingPageTest {
    @BeforeAll
    static void register() {
        PvzcePackets.register();
    }

    /**
     * The page the screen lands on.
     *
     * <p>Driven through {@code tick()} rather than {@code init()}: init also starts the menu
     * music, which needs a window this test does not have, while tick makes the same two calls
     * that decide the page.
     */
    private static String openPage(LevelSelectScreen screen) {
        screen.tick();
        return screen.openPageForTest();
    }

    private static LevelListS2C.LevelInfo level(String id, String theme, String category) {
        LevelPayload payload = new LevelPayload(9, 5,
                List.of(new SeedOption("pvzce:pea_shooter", "plant", "pvzce:pea_shooter",
                        "pvzce:textures/entities/pea_shooter", 100)),
                6, List.of("pvzce:basic_zombie"),
                List.of(new SceneSyncS2C.Cell(0, 0, "pvzce:grass")), List.of(), List.of());
        return LevelListS2C.LevelInfo.of(id, "关卡", "", "pvzce:plant_team",
                List.of(new LevelListS2C.TeamInfo("pvzce:plant_team", "植物方", "survive_waves")),
                "", "day", theme, category, false, payload, LevelListS2C.UnlockInfo.OPEN);
    }

    private static PvzceClient client(Path gameDir) {
        Connection.Pair pair = Connection.createMemoryPair();
        return new PvzceClient(pair.client(), gameDir, Thread.currentThread().getContextClassLoader());
    }

    /**
     * The server's table has no unclassified page (every shipped level is classified), and the
     * screen must land on its first real page rather than on the bucket it invents itself.
     *
     * <p>The sequence below is the real one, and it is the whole bug: a frame runs with no
     * table and no levels, so the screen's own fallback - a single unclassified page - is all
     * it can choose from, and the server's table and list only arrive on the next tick. A page
     * picked out of that empty view is a guess, so the refresh must be free to move off it.
     */
    @Test
    void landsOnTheFirstRealPageEvenThoughTheBucketIsAlwaysAdded() throws Exception {
        PvzceClient client = client(Files.createTempDirectory("pvzce-landing"));
        LevelSelectScreen screen = new LevelSelectScreen(client);
        client.setScreenReplacing(screen);

        // Frame 1: an empty view; whatever lands here is not a decision.
        openPage(screen);

        // Frame 2: the server's answers.
        client.setLevelTabs(List.of(new LevelTabsS2C.Tab("pvzce:yard", "pvzce:adventure")));
        client.setLevelList(List.of(level("pvzce:yard/adventure/1_1", "pvzce:yard", "pvzce:adventure")));

        assertEquals("pvzce:yard/pvzce:adventure", openPage(screen),
                "the level list must open on the page that has levels, not on the empty bucket");
    }

    /** Picking a page is the player's call, and later refreshes must respect it. */
    @Test
    void aPageThePlayerPickedIsKeptAcrossRefreshes() throws Exception {
        PvzceClient client = client(Files.createTempDirectory("pvzce-landing-picked"));
        client.setLevelTabs(List.of(new LevelTabsS2C.Tab("pvzce:yard", "pvzce:adventure")));
        client.setLevelList(List.of(level("pvzce:yard/adventure/1_1", "pvzce:yard", "pvzce:adventure"),
                level("pvzce:one_off", "pvzce:uncategorized", "pvzce:uncategorized")));

        LevelSelectScreen screen = new LevelSelectScreen(client);
        client.setScreenReplacing(screen);
        openPage(screen);
        screen.switchToPageForTest("pvzce:uncategorized", "pvzce:uncategorized");
        assertEquals("pvzce:uncategorized/pvzce:uncategorized", openPage(screen),
                "the bucket is where the unclassified level lives");

        // A refresh (a save, a /reload) must not yank them back to a page they did not ask for.
        client.setLevelList(List.of(level("pvzce:yard/adventure/1_1", "pvzce:yard", "pvzce:adventure"),
                level("pvzce:one_off", "pvzce:uncategorized", "pvzce:uncategorized"),
                level("pvzce:yard/adventure/1_2", "pvzce:yard", "pvzce:adventure")));

        assertEquals("pvzce:uncategorized/pvzce:uncategorized", openPage(screen),
                "a deliberate choice outlives a list refresh");
    }

    /** The table did not arrive yet: the fallback is the bucket, because it is all there is. */
    @Test
    void fallsBackToTheBucketWhenThatIsAllThereIs() throws Exception {
        PvzceClient client = client(Files.createTempDirectory("pvzce-landing-empty"));

        LevelSelectScreen screen = new LevelSelectScreen(client);
        client.setScreenReplacing(screen);

        assertEquals("pvzce:uncategorized/pvzce:uncategorized", openPage(screen),
                "with no table at all the bucket is the honest answer");
    }

    /** A table that really is only the bucket stays on the bucket. */
    @Test
    void keepsTheBucketWhenTheContentHasNoThemes() throws Exception {
        PvzceClient client = client(Files.createTempDirectory("pvzce-landing-bucket"));
        client.setLevelList(List.of(level("pvzce:one_off", "pvzce:uncategorized", "pvzce:uncategorized")));
        client.setLevelTabs(List.of());

        LevelSelectScreen screen = new LevelSelectScreen(client);
        client.setScreenReplacing(screen);

        assertEquals("pvzce:uncategorized/pvzce:uncategorized", openPage(screen),
                "a level with no theme belongs on the bucket page");
    }
}
