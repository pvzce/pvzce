package com.pvzce.client;

import com.pvzce.client.config.PvzceClientConfig;
import com.pvzce.common.network.Connection;
import com.pvzce.testutil.ClientHarness;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Who is playing, and that it survives a restart.
 *
 * <p>A world is one player's save ({@code saves/<名字>/}), so "which world" is the title screen's
 * question and the answer is worth keeping: asking it again every time the game starts is asking
 * a question the player has already answered. The name is a directory name on both sides, so it
 * is sanitised on the way in - the remembered name and the directory the menu then opens cannot
 * disagree.
 */
class RememberedWorldTest {

    @Test
    void theClientStartsAsTheLastWorldAndRemembersASwitch() throws Exception {
        try (ClientHarness harness = ClientHarness.create("pvzce-remembered-world")) {
            PvzceClient client = harness.client();
            assertEquals(PvzceClientConfig.DEFAULT_WORLD, client.currentWorld(),
                    "a game that has never been played has no player yet");

            client.setCurrentWorld("crystal neko");
            assertEquals("crystal_neko", client.currentWorld(),
                    "the name is the save directory's name, so it is sanitised the same way");
            assertEquals("crystal_neko",
                    PvzceClientConfig.load(harness.gameDir()).lastWorld(),
                    "and it is written to the client config straight away");

            // The next session: a second client on the same game directory, which is what
            // starting the game again produces.
            Connection.Pair pair = Connection.createMemoryPair();
            try {
                PvzceClient restarted = new PvzceClient(pair.client(), harness.gameDir(),
                        Thread.currentThread().getContextClassLoader());
                assertEquals("crystal_neko", restarted.currentWorld(),
                        "the next start opens on the player that was playing");
            } finally {
                pair.client().close();
                pair.server().close();
            }
        }
    }
}
