package com.pvzce.arch;

import com.pvzce.common.network.PvzcePackets;
import com.pvzce.testutil.SourceTree;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every packet has a sender and a handler.
 *
 * <p>A packet is three things that must agree: a class, a row in the protocol table, and a call
 * site on each end. Nothing links them. {@code MovePlantC2S} lived for months with an id, a record
 * and a place in the wire-format test while <em>no code anywhere</em> constructed it or handled it -
 * the glove had moved to the tool path and the packet stayed behind. It was found by reading the
 * whole protocol by hand, which is a process that works once and then stops working.
 *
 * <p>This test is that reading, repeated on every build. Two directions, checked against the main
 * sources only, because a test naming a packet is not a call site:
 *
 * <ul>
 *   <li>client to server: the client must name it (a sender) and the server must name it (a
 *       handler);</li>
 *   <li>server to client: the server or a mechanic in {@code common} must name it (mechanics send
 *       their own sync packets) and the client must name it.</li>
 * </ul>
 *
 * <p>The class files are the packet list rather than the private table in {@code PvzcePackets', so
 * the table's own count is asserted against it: a packet class with no row fails, and a row whose
 * class file is gone fails too.
 */
class PacketWiringTest {
    private static Path gameSources;
    private static Path packetsDir;
    private static List<Path> clientSources;
    private static List<Path> serverSources;
    private static List<Path> commonSources;

    @BeforeAll
    static void findSources() throws IOException {
        Path root = SourceTree.root();
        Assumptions.assumeTrue(root != null, "not running from a source checkout; no packets to check");
        gameSources = root.resolve("pvzce-game/src/main/java/com/pvzce");
        packetsDir = gameSources.resolve("common/network/packet");
        clientSources = SourceTree.javaFiles(gameSources.resolve("client"));
        serverSources = SourceTree.javaFiles(gameSources.resolve("server"));
        commonSources = SourceTree.javaFiles(gameSources.resolve("common"));
    }

    @Test
    void everyPacketClassHasARowInTheTable() throws IOException {
        List<String> problems = new ArrayList<>();
        String table = Files.readString(gameSources.resolve("common/network/PvzcePackets.java"));
        List<Path> classes = packetClasses();
        for (Path file : classes) {
            String name = className(file);
            if (!table.contains(name + ".class")) {
                problems.add(name + " is not registered in PvzcePackets");
            }
        }
        assertTrue(problems.isEmpty(), "packets missing from the protocol table:\n"
                + String.join("\n", problems));
        assertEquals(PvzcePackets.count(), classes.size(),
                "the table and the packet classes disagree: " + PvzcePackets.count() + " rows, "
                        + classes.size() + " classes");
    }

    @Test
    void everyPacketIsSentAndHandled() throws IOException {
        List<String> problems = new ArrayList<>();
        for (Path file : packetClasses()) {
            String name = className(file);
            boolean serverbound = name.endsWith("C2S");
            // A packet names itself in its own file and in the table; both are excluded by
            // looking only at the two ends, which is the whole point of the check.
            boolean handledByClient = mentions(clientSources, name);
            boolean handledByServer = mentions(serverSources, name);
            boolean sentFromCommonOrServer = handledByServer || mentions(commonSources, name);

            if (serverbound && !handledByClient) {
                problems.add(name + " is client to server but no client code names it (no sender)");
            }
            if (serverbound && !handledByServer) {
                problems.add(name + " is client to server but no server code names it (no handler)");
            }
            if (!serverbound && !sentFromCommonOrServer) {
                problems.add(name + " is server to client but no server or mechanic names it (no sender)");
            }
            if (!serverbound && !handledByClient) {
                problems.add(name + " is server to client but no client code names it (no handler)");
            }
        }
        assertTrue(problems.isEmpty(), "orphan packets:\n" + String.join("\n", problems));
    }

    /** The packet classes, from the directory they live in. */
    private static List<Path> packetClasses() throws IOException {
        List<Path> classes = new ArrayList<>();
        for (Path file : SourceTree.javaFiles(packetsDir)) {
            String name = className(file);
            if (name.endsWith("C2S") || name.endsWith("S2C")) {
                classes.add(file);
            } else {
                // Payload records travel inside a packet and have no direction of their own.
                assertTrue(name.equals("LevelPayload") || name.equals("SeedOption")
                                || name.equals("SlotInfo"),
                        name + " has no direction suffix and is not a known payload record");
            }
        }
        return classes;
    }

    private static String className(Path file) {
        return file.getFileName().toString().replace(".java", "");
    }

    /** Whether any of these sources names the packet, call site or not. */
    private static boolean mentions(List<Path> sources, String className) throws IOException {
        Set<String> found = new LinkedHashSet<>();
        for (Path file : sources) {
            if (Files.readString(file).contains(className)) {
                found.add(file.getFileName().toString());
            }
        }
        return !found.isEmpty();
    }
}
