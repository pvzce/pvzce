package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PacketStruct;
import com.pvzce.common.network.PvzcePacket;

import java.util.List;

/** Level registry snapshot for the level select screen. */
public record LevelListS2C(List<LevelInfo> levels) implements PvzcePacket {
    public record TeamInfo(String id, String name, String winCondition) {
        public static final PacketStruct.Codec<TeamInfo> CODEC = PacketStruct.<TeamInfo>builder()
                .field(TeamInfo::id, PacketByteBuf::writeString, PacketByteBuf::readString)
                .field(TeamInfo::name, PacketByteBuf::writeString, PacketByteBuf::readString)
                .field(TeamInfo::winCondition, PacketByteBuf::writeString, PacketByteBuf::readString)
                .build(values -> new TeamInfo((String) values.get(0), (String) values.get(1),
                        (String) values.get(2)));

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
     */
    public record LevelInfo(String id, String name, String description, String winTeam,
                            List<TeamInfo> teams, String status, String icon, String theme,
                            String category, LevelPayload payload, UnlockInfo unlock) {
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
                .list(LevelInfo::teams, TeamInfo::encode, TeamInfo::decode)
                .nested(LevelInfo::payload, LevelPayload.CODEC)
                .nested(LevelInfo::unlock, UnlockInfo.CODEC)
                .build(values -> new LevelInfo((String) values.get(0), (String) values.get(1),
                        (String) values.get(2), (String) values.get(3),
                        (List<TeamInfo>) values.get(8), (String) values.get(4), (String) values.get(5),
                        (String) values.get(6), (String) values.get(7),
                        (LevelPayload) values.get(9), (UnlockInfo) values.get(10)));

        public static LevelInfo of(String id, String name, String description, String winTeam,
                                   List<TeamInfo> teams, String status, String icon,
                                   String theme, String category, LevelPayload payload,
                                   UnlockInfo unlock) {
            return new LevelInfo(id, name, description, winTeam, teams, status, icon, theme,
                    category, payload, unlock);
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
         */
        public boolean hasRunningSave() {
            return IN_PROGRESS.equals(status);
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
