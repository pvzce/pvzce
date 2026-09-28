package com.pvzce.common.network.packet;

import com.pvzce.api.util.Identifier;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PacketStruct;

import java.util.List;
import java.util.Optional;

/**
 * The board description both level packets carry: size, scene grid, the seed pool metadata
 * the chooser needs, and the level's mechanics.
 *
 * <p>{@link LevelInitS2C} and {@link LevelListS2C.LevelInfo} each hand-wrote the same
 * thirteen encode/decode steps, so adding one field to a level meant editing two packets,
 * two listeners and both screens - and any one of those could be missed. One record, one
 * field list.
 *
 * <p>Which backdrop the level is played on and which scene elements it does not draw travel
 * here too, next to the scene grid they belong with: both are presentation, but a client that
 * had to guess them from its own copy of the level file would draw a different board than the
 * one the server is running.
 *
 * <p>One game rule travels too - {@code plantsWholeColumn} - because the client draws the column
 * a card is about to fill. Rules are the server's, so this is the exception and not the shape: a
 * rule the HUD does not draw stays where it is.
 *
 * <p>Mechanics travel as their own JSON blocks ({@link MechanicPayload}), encoded by the
 * mechanic's codec on the server and decoded by the same codec on the client. They used to
 * be three hand-written field groups here ({@code conveyor}, {@code beltCapacity} and the
 * four plantable-area bounds), so every new mechanic meant a field in this record, a field
 * in {@code ClientLevel} and a branch in the HUD. Now the payload has one list and does not
 * change again.
 */
public record LevelPayload(int width, int height, List<SeedOption> seedPool, int maxSeedSlots,
                           List<String> previewZombies, List<SceneSyncS2C.Cell> sceneCells,
                           List<String> lockedSlots, List<MechanicPayload> mechanics,
                           String background, List<String> hiddenSceneElements,
                           boolean shadersDisabled, List<SeedOption> buffPool, int maxBuffSlots,
                           List<String> activeBuffs, boolean plantsWholeColumn) {
    public LevelPayload {
        seedPool = List.copyOf(seedPool);
        previewZombies = List.copyOf(previewZombies);
        sceneCells = List.copyOf(sceneCells);
        lockedSlots = List.copyOf(lockedSlots);
        mechanics = List.copyOf(mechanics);
        background = background == null ? "" : background;
        hiddenSceneElements = List.copyOf(hiddenSceneElements);
        buffPool = List.copyOf(buffPool);
        activeBuffs = List.copyOf(activeBuffs);
    }

    /**
     * The payload of a row that has no board: a collection's.
     *
     * <p>A collection is a box of levels and not a level, so there is nothing for it to describe -
     * no size, no cards, no scene. An empty payload rather than a nullable field, because
     * {@link LevelListS2C.LevelInfo} has carried a {@code LevelPayload} for every row since it was
     * written and because zero is the honest description of a board that does not exist; the
     * screens never ask a collection for one (see {@code LevelListS2C.LevelInfo#isCollection}).
     */
    public static final LevelPayload EMPTY = new LevelPayload(0, 0, List.of(), 0, List.of(), List.of(),
            List.of(), List.of(), "", List.of(), false, List.of(), 0, List.of(), false);

    /**
     * The board as it was before buffs existed: no buff page, no buff slots.
     *
     * <p>Kept because two thirds of the payload's callers are tests and fixtures describing a
     * board, not a level's menu, and because "a level that never heard of buffs" is a real shape
     * rather than a missing value.
     */
    public LevelPayload(int width, int height, List<SeedOption> seedPool, int maxSeedSlots,
                        List<String> previewZombies, List<SceneSyncS2C.Cell> sceneCells,
                        List<String> lockedSlots, List<MechanicPayload> mechanics,
                        String background, List<String> hiddenSceneElements, boolean shadersDisabled) {
        this(width, height, seedPool, maxSeedSlots, previewZombies, sceneCells, lockedSlots,
                mechanics, background, hiddenSceneElements, shadersDisabled, List.of(), 0, List.of(),
                false);
    }

    /** As above, before the running buff set travelled. */
    public LevelPayload(int width, int height, List<SeedOption> seedPool, int maxSeedSlots,
                        List<String> previewZombies, List<SceneSyncS2C.Cell> sceneCells,
                        List<String> lockedSlots, List<MechanicPayload> mechanics,
                        String background, List<String> hiddenSceneElements, boolean shadersDisabled,
                        List<SeedOption> buffPool, int maxBuffSlots) {
        this(width, height, seedPool, maxSeedSlots, previewZombies, sceneCells, lockedSlots,
                mechanics, background, hiddenSceneElements, shadersDisabled, buffPool, maxBuffSlots,
                List.of(), false);
    }

    /**
     * The same board without a look of its own: the built-in yard, nothing hidden.
     *
     * <p>Kept because a board's look is the last thing most callers care about - every test
     * fixture and every level written before backdrops existed means exactly this - and a
     * fourteen-argument constructor repeated at each of them is how a field list gets copied
     * into a dozen files (see the {@code LevelDef} clone helpers this project already removed).
     */
    public LevelPayload(int width, int height, List<SeedOption> seedPool, int maxSeedSlots,
                        List<String> previewZombies, List<SceneSyncS2C.Cell> sceneCells,
                        List<String> lockedSlots, List<MechanicPayload> mechanics) {
        this(width, height, seedPool, maxSeedSlots, previewZombies, sceneCells, lockedSlots,
                mechanics, "", List.of(), false);
    }

    /** The backdrop texture, or empty for the built-in yard. */
    public Identifier backgroundId() {
        return Identifier.tryParse(background);
    }

    /**
     * One mechanic's data block, as the server has it.
     *
     * <p>Sent rather than read from the client's own data packs: "which rules is the server
     * actually running" is server state, and a modified or older client pack must not be
     * able to draw a red line where the server does not enforce one.
     */
    public record MechanicPayload(Identifier type, String block) {
        public static final PacketStruct.Codec<MechanicPayload> CODEC =
                PacketStruct.<MechanicPayload>builder()
                        .field(MechanicPayload::type, PacketByteBuf::writeIdentifier,
                                PacketByteBuf::readIdentifierOrNull)
                        .field(MechanicPayload::block, PacketByteBuf::writeString, PacketByteBuf::readString)
                        .build(values -> new MechanicPayload((Identifier) values.get(0), (String) values.get(1)));

        public void encode(PacketByteBuf buf) {
            CODEC.encode(this, buf);
        }

        public static MechanicPayload decode(PacketByteBuf buf) {
            MechanicPayload payload = CODEC.decode(buf);
            if (payload.type() == null) {
                throw new PacketByteBuf.DecoderException("Mechanic payload without a type id");
            }
            return payload;
        }
    }

    /** The block of one mechanic, or empty when this level does not declare it. */
    public Optional<String> mechanicBlock(Identifier type) {
        for (MechanicPayload payload : mechanics) {
            if (payload.type().equals(type)) {
                return Optional.of(payload.block());
            }
        }
        return Optional.empty();
    }

    public static final PacketStruct.Codec<LevelPayload> CODEC = PacketStruct.<LevelPayload>builder()
            .field(LevelPayload::width, PacketByteBuf::writeInt, PacketByteBuf::readInt)
            .field(LevelPayload::height, PacketByteBuf::writeInt, PacketByteBuf::readInt)
            .list(LevelPayload::seedPool, SeedOption::encode, SeedOption::decode)
            .field(LevelPayload::maxSeedSlots, PacketByteBuf::writeInt, PacketByteBuf::readInt)
            .stringList(LevelPayload::previewZombies)
            .list(LevelPayload::sceneCells, SceneSyncS2C.Cell::encode, SceneSyncS2C.Cell::decode)
            .stringList(LevelPayload::lockedSlots)
            .list(LevelPayload::mechanics, MechanicPayload::encode, MechanicPayload::decode)
            .field(LevelPayload::background, PacketByteBuf::writeString, PacketByteBuf::readString)
            .stringList(LevelPayload::hiddenSceneElements)
            .field(LevelPayload::shadersDisabled, PacketByteBuf::writeBoolean, PacketByteBuf::readBoolean)
            // The buff page's own two fields, right after the card page's: the two halves of
            // the same chooser travel together, and a client that got one without the other
            // would draw a tab it cannot fill.
            .list(LevelPayload::buffPool, SeedOption::encode, SeedOption::decode)
            .field(LevelPayload::maxBuffSlots, PacketByteBuf::writeInt, PacketByteBuf::readInt)
            // The buffs this run is actually played with, locked and chosen already resolved.
            // Server state rather than something the client derives: which buffs are on decides
            // what the server does, and a client drawing icons from its own arithmetic could
            // show a buff the simulation is not applying.
            .stringList(LevelPayload::activeBuffs)
            // How far one card reaches, which the placement preview draws: the one game rule the
            // client has to know (see `RULE_PLANT_WHOLE_COLUMN`). A rule rather than a mechanic
            // because the server is the one that enforces it and `/gamerule` can turn it on.
            .field(LevelPayload::plantsWholeColumn, PacketByteBuf::writeBoolean, PacketByteBuf::readBoolean)
            .build(values -> new LevelPayload((Integer) values.get(0), (Integer) values.get(1),
                    (List<SeedOption>) values.get(2), (Integer) values.get(3),
                    (List<String>) values.get(4), (List<SceneSyncS2C.Cell>) values.get(5),
                    (List<String>) values.get(6), (List<MechanicPayload>) values.get(7),
                    (String) values.get(8), (List<String>) values.get(9), (Boolean) values.get(10),
                    (List<SeedOption>) values.get(11), (Integer) values.get(12),
                    (List<String>) values.get(13), (Boolean) values.get(14)));

    public void encode(PacketByteBuf buf) {
        CODEC.encode(this, buf);
    }

    public static LevelPayload decode(PacketByteBuf buf) {
        return CODEC.decode(buf);
    }
}
