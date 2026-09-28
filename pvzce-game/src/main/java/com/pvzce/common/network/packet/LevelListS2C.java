package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PacketStruct;
import com.pvzce.common.network.PvzcePacket;

import java.util.List;

/** Level registry snapshot for the level select screen. */
public record LevelListS2C(List<LevelInfo> levels) implements PvzcePacket {
    /**
     * One side of a level, and whether a human may pick it.
     *
     * <p>{@code playable} is the level's own declaration, evaluated on the server and sent
     * as a verdict like every other "is this available" answer: a level that can only be
     * played as the plants says so, and the client's preparation screen skips itself when
     * there is only one of these. Before this the client decided for itself that the zombie
     * side was never playable, which is a fact about the build and not about the level.
     */
    public record TeamInfo(String id, String name, String winCondition, boolean playable) {
        /** A team that can be picked; the three-argument form every already-written call uses. */
        public TeamInfo(String id, String name, String winCondition) {
            this(id, name, winCondition, true);
        }

        public static final PacketStruct.Codec<TeamInfo> CODEC = PacketStruct.<TeamInfo>builder()
                .field(TeamInfo::id, PacketByteBuf::writeString, PacketByteBuf::readString)
                .field(TeamInfo::name, PacketByteBuf::writeString, PacketByteBuf::readString)
                .field(TeamInfo::winCondition, PacketByteBuf::writeString, PacketByteBuf::readString)
                .field(TeamInfo::playable, PacketByteBuf::writeBoolean, PacketByteBuf::readBoolean)
                .build(values -> new TeamInfo((String) values.get(0), (String) values.get(1),
                        (String) values.get(2), (Boolean) values.get(3)));

        public void encode(PacketByteBuf buf) {
            CODEC.encode(this, buf);
        }

        public static TeamInfo decode(PacketByteBuf buf) {
            return CODEC.decode(buf);
        }
    }

    /**
     * Why a level is or is not playable yet, as computed by
     * {@link com.pvzce.common.core.LevelUnlocks} on the server.
     *
     * <p>The verdict travels rather than the rule: the client draws the padlock, the
     * "需要通关 1-1" line and the buy button from this, so it never has to re-implement
     * (and never gets to disagree with) the server's answer. The raw
     * {@code requirements} come along so a future tooltip can show more than the
     * one-line reason without a protocol change.
     */
    public record UnlockInfo(boolean unlocked, boolean buyable, int cost, String reason,
                             List<com.pvzce.api.content.LevelUnlock.Requirement> requirements,
                             boolean hidden) {
        public static final UnlockInfo OPEN =
                new UnlockInfo(true, false, 0, "", List.of(), false);

        public static final PacketStruct.Codec<UnlockInfo> CODEC = PacketStruct.<UnlockInfo>builder()
                .field(UnlockInfo::unlocked, PacketByteBuf::writeBoolean, PacketByteBuf::readBoolean)
                .field(UnlockInfo::buyable, PacketByteBuf::writeBoolean, PacketByteBuf::readBoolean)
                .field(UnlockInfo::cost, PacketByteBuf::writeInt, PacketByteBuf::readInt)
                .field(UnlockInfo::reason, PacketByteBuf::writeString, PacketByteBuf::readString)
                .list(UnlockInfo::requirements, UnlockInfo::writeRequirement, UnlockInfo::readRequirement)
                .field(UnlockInfo::hidden, PacketByteBuf::writeBoolean, PacketByteBuf::readBoolean)
                .build(values -> new UnlockInfo((Boolean) values.get(0), (Boolean) values.get(1),
                        (Integer) values.get(2), (String) values.get(3),
                        (List<com.pvzce.api.content.LevelUnlock.Requirement>) values.get(4),
                        (Boolean) values.get(5)));

        private static void writeRequirement(com.pvzce.api.content.LevelUnlock.Requirement requirement,
                                             PacketByteBuf buf) {
            buf.writeString(requirement.type());
            buf.writeString(requirement.id().map(Object::toString).orElse(""));
            buf.writeInt(requirement.amount());
        }

        private static com.pvzce.api.content.LevelUnlock.Requirement readRequirement(PacketByteBuf buf) {
            String type = buf.readString();
            String id = buf.readString();
            int amount = buf.readInt();
            return new com.pvzce.api.content.LevelUnlock.Requirement(type,
                    com.pvzce.api.util.Identifier.tryParse(id) == null
                            ? java.util.Optional.empty()
                            : java.util.Optional.of(com.pvzce.api.util.Identifier.tryParse(id)),
                    amount);
        }

        /** Built from the server-side verdict. */
        public static UnlockInfo of(com.pvzce.common.core.LevelUnlocks.State state) {
            return new UnlockInfo(state.unlocked(), state.buyable(), state.cost(), state.reason(),
                    state.unmet(), state.hidden());
        }
    }

    /**
     * A row that is a box of levels rather than a level.
     *
     * <p>The original's worlds: 白天草坪 is one row of the adventure page that opens on 1-1 to 1-10.
     * A collection is a <em>tag</em> over the level registry on the server
     * ({@code LevelCollections}), and what travels here is everything the row needs that the client
     * cannot work out for itself: the icon its first member would have drawn, and its members in
     * the order the tag file lists them.
     *
     * <p>Its <b>name</b> is not on the wire, exactly as a category's and a theme's are not: it is
     * the language key {@code level_collection.<ns>.<path>} built from this row's own id, which is
     * the collection tag's id.
     *
     * <p>Its <b>progress</b> is not on the wire either: the members' own rows are all in this same
     * list, each carrying whether it has been cleared, so counting them is a question the client
     * can answer off what it already has - and one that cannot then disagree with the rows it is
     * drawing.
     */
    public record CollectionInfo(String icon, List<String> members) {
        /**
         * The empty one, which is what every ordinary level carries.
         *
         * <p>{@link #isCollection()} reads "has members" rather than a flag beside the record: the
         * server never sends a collection with nothing in it (see {@code LevelCollections.of}), so
         * the two cannot disagree, and one field is one thing for a codec to carry.
         */
        public static final CollectionInfo NONE = new CollectionInfo("", List.of());

        public CollectionInfo {
            icon = icon == null ? "" : icon;
            members = List.copyOf(members);
        }

        /** True when this row is a collection rather than a level. */
        public boolean isCollection() {
            return !members.isEmpty();
        }

        public static final PacketStruct.Codec<CollectionInfo> CODEC =
                PacketStruct.<CollectionInfo>builder()
                        .field(CollectionInfo::icon, PacketByteBuf::writeString,
                                PacketByteBuf::readString)
                        .stringList(CollectionInfo::members)
                        .build(values -> new CollectionInfo((String) values.get(0),
                                (List<String>) values.get(1)));
    }

    /**
     * One selectable level.
     *
     * <p>The board description is a shared {@link LevelPayload}, so this packet and
     * {@link LevelInitS2C} cannot drift apart; the flat accessors below keep the
     * screens readable.
     *
     * <p>{@code theme}/{@code category} are the level's page, derived from its id path by
     * {@link com.pvzce.api.util.LevelGrouping} on the server. They are a pair that is
     * always both set or both the unclassified sentinel, so {@link #isUncategorized()}
     * is the one check callers need.
     *
     * <p>{@code collection} is what this row <em>is</em> - empty for a level, a box of levels for a
     * collection - and {@code collectionId} is which box this level lives in, or empty when the
     * page lists it on its own. The two are one story told from both ends: a page leaves out the
     * levels that have a {@code collectionId}, and the collection's own screen draws them from its
     * member list.
     */
    public record LevelInfo(String id, String name, String description, String winTeam,
                            List<TeamInfo> teams, String status, String icon, String theme,
                            String category, boolean runningSave, boolean cleared,
                            LevelPayload payload, UnlockInfo unlock, CollectionInfo collection,
                            String collectionId) {
        /** A resumable save exists for this level in the world the list was asked for. */
        public static final String IN_PROGRESS = "in_progress";
        /** Finished at least once and no resumable save is left. */
        public static final String COMPLETED = "completed";

        public static final PacketStruct.Codec<LevelInfo> CODEC = PacketStruct.<LevelInfo>builder()
                .field(LevelInfo::id, PacketByteBuf::writeString, PacketByteBuf::readString)
                .field(LevelInfo::name, PacketByteBuf::writeString, PacketByteBuf::readString)
                .field(LevelInfo::description, PacketByteBuf::writeString, PacketByteBuf::readString)
                .field(LevelInfo::winTeam, PacketByteBuf::writeString, PacketByteBuf::readString)
                .field(LevelInfo::status, PacketByteBuf::writeString, PacketByteBuf::readString)
                .field(LevelInfo::icon, PacketByteBuf::writeString, PacketByteBuf::readString)
                .field(LevelInfo::theme, PacketByteBuf::writeString, PacketByteBuf::readString)
                .field(LevelInfo::category, PacketByteBuf::writeString, PacketByteBuf::readString)
                .field(LevelInfo::runningSave, PacketByteBuf::writeBoolean, PacketByteBuf::readBoolean)
                // Whether this level has ever been beaten in this world. Its own field
                // beside runningSave for the same reason runningSave is beside status: the
                // trophy is earned once and never taken back, while an abandoned replay
                // makes the row read "in progress" and must not cost the player the medal.
                .field(LevelInfo::cleared, PacketByteBuf::writeBoolean, PacketByteBuf::readBoolean)
                .list(LevelInfo::teams, TeamInfo::encode, TeamInfo::decode)
                .nested(LevelInfo::payload, LevelPayload.CODEC)
                .nested(LevelInfo::unlock, UnlockInfo.CODEC)
                .nested(LevelInfo::collection, CollectionInfo.CODEC)
                .field(LevelInfo::collectionId, PacketByteBuf::writeString, PacketByteBuf::readString)
                .build(values -> new LevelInfo((String) values.get(0), (String) values.get(1),
                        (String) values.get(2), (String) values.get(3),
                        (List<TeamInfo>) values.get(10), (String) values.get(4), (String) values.get(5),
                        (String) values.get(6), (String) values.get(7), (Boolean) values.get(8),
                        (Boolean) values.get(9), (LevelPayload) values.get(11),
                        (UnlockInfo) values.get(12), (CollectionInfo) values.get(13),
                        (String) values.get(14)));

        public static LevelInfo of(String id, String name, String description, String winTeam,
                                   List<TeamInfo> teams, String status, String icon,
                                   String theme, String category, boolean runningSave, boolean cleared,
                                   LevelPayload payload, UnlockInfo unlock) {
            return new LevelInfo(id, name, description, winTeam, teams, status, icon, theme,
                    category, runningSave, cleared, payload, unlock, CollectionInfo.NONE, "");
        }

        /**
         * A collection's own row: a box of levels, with nothing of a level about it.
         *
         * <p>Built here rather than at the server's call site so that "what a box carries" is one
         * decision: no board ({@code LevelPayload} is empty), no teams, no save, and an unlock
         * verdict that is always open - a box is not locked, its levels are, and each of them says
         * so on its own row inside it.
         */
        public static LevelInfo ofCollection(String id, String name, String theme, String category,
                                             String icon, List<String> members) {
            return new LevelInfo(id, name, "", "", List.of(), "", icon, theme, category, false,
                    false, LevelPayload.EMPTY, UnlockInfo.OPEN, new CollectionInfo(icon, members), "");
        }

        /**
         * The same row for a level that has never been beaten; used by tests and by the
         * editor, which lists levels nobody has played.
         */
        public static LevelInfo of(String id, String name, String description, String winTeam,
                                   List<TeamInfo> teams, String status, String icon,
                                   String theme, String category, boolean runningSave,
                                   LevelPayload payload, UnlockInfo unlock) {
            return of(id, name, description, winTeam, teams, status, icon, theme, category,
                    runningSave, false, payload, unlock);
        }

        /** True when this row is a box of levels rather than a level. */
        public boolean isCollection() {
            return collection != null && collection.isCollection();
        }

        /**
         * Which box this level lives in, or {@code null} when it is listed on its own.
         *
         * <p>What the pages filter on: a level that is inside a collection is reached through the
         * box, and drawing it beside the box would be the same level twice on one page.
         */
        public com.pvzce.api.util.Identifier collectionIdOrNull() {
            return collectionId == null || collectionId.isEmpty()
                    ? null : com.pvzce.api.util.Identifier.tryParse(collectionId);
        }

        /** True when the player may enter this level yet. */
        public boolean isLocked() {
            return unlock != null && !unlock.unlocked();
        }

        /** True when this level has no theme/category page and belongs to the unclassified tab. */
        public boolean isUncategorized() {
            return com.pvzce.api.util.LevelGrouping.isUncategorized(
                    com.pvzce.api.util.Identifier.tryParse(theme));
        }

        /**
         * True while a resumable save waits for this level in the world the list describes.
         *
         * <p>Entering such a level must load that save and ask continue/restart - the run
         * already has a card bar and a team, so it must not be sent through the pre-game
         * screens. One method because the level list, the level setup screen and the tests
         * all have to agree on what "already has progress" means.
         *
         * <p>This is its own field, and deliberately not derived from {@link #status}:
         * "cleared at least once" and "a run is waiting to be resumed" are two independent
         * facts, and a level is often both. Reading the save out of the status string made
         * the two collide - a cleared level with an abandoned replay reported
         * {@code completed}, so the entry decision saw no save, opened the seed chooser, and
         * the player only learned about the abandoned run from the restart dialog that
         * popped up after submitting their cards.
         */
        public boolean hasRunningSave() {
            return runningSave;
        }

        public int width() {
            return payload.width();
        }

        public int height() {
            return payload.height();
        }

        public List<SeedOption> seedPool() {
            return payload.seedPool();
        }

        public int maxSeedSlots() {
            return payload.maxSeedSlots();
        }

        public List<String> previewZombies() {
            return payload.previewZombies();
        }

        public List<SceneSyncS2C.Cell> sceneCells() {
            return payload.sceneCells();
        }

        public void encode(PacketByteBuf buf) {
            CODEC.encode(this, buf);
        }

        public static LevelInfo decode(PacketByteBuf buf) {
            return CODEC.decode(buf);
        }
    }

    public static final PacketStruct.Codec<LevelListS2C> CODEC = PacketStruct.<LevelListS2C>builder()
            .list(LevelListS2C::levels, LevelInfo::encode, LevelInfo::decode)
            .build(values -> new LevelListS2C((List<LevelInfo>) values.get(0)));

    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.CLIENTBOUND;
    }

    @Override
    public void encode(PacketByteBuf buf) {
        CODEC.encode(this, buf);
    }

    public static LevelListS2C decode(PacketByteBuf buf) {
        return CODEC.decode(buf);
    }
}
