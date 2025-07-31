package puregero.multipaper.externalserverprotocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Score;
import net.minecraft.world.scores.Team;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;

import javax.annotation.Nullable;

public class ScoreboardUpdatePacket extends ExternalServerPacket {

    private static final Logger LOGGER = LogManager.getLogger(ScoreboardUpdatePacket.class.getSimpleName());
    public static boolean updating = false;

    @Nullable
    private final String scoreboardId;
    @Nullable
    private final String criteria;
    private final Packet<?> packet;

    public ScoreboardUpdatePacket(@Nullable String scoreboard, Packet<?> packet) {
        this(scoreboard, null, packet);
    }

    public ScoreboardUpdatePacket(@Nullable String scoreboard, @Nullable ObjectiveCriteria criteria, Packet<?> packet) {
        this.scoreboardId = scoreboard;
        this.criteria = criteria == null ? null : criteria.getName();
        this.packet = packet;
    }

    public ScoreboardUpdatePacket(FriendlyByteBuf in) {
        String scoreboard = in.readUtf();
        this.scoreboardId = scoreboard.isEmpty() ? null : scoreboard;

        String criteria = in.readUtf();
        this.criteria = criteria.isEmpty() ? null : criteria;

        byte[] bytes = in.readByteArray();
        ByteBuf buf = Unpooled.wrappedBuffer(bytes);
        FriendlyByteBuf friendlyByteBuf = new FriendlyByteBuf(buf);
        int packetId = friendlyByteBuf.readVarInt();
        packet = ConnectionProtocol.PLAY.createPacket(PacketFlow.CLIENTBOUND, packetId, friendlyByteBuf);
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeUtf(scoreboardId == null ? "" : scoreboardId);

        out.writeUtf(criteria == null ? "" : criteria);

        ConnectionProtocol protocol = ConnectionProtocol.getProtocolForPacket(packet);
        Integer id = protocol.getPacketId(PacketFlow.CLIENTBOUND, packet);
        ByteBuf buf = Unpooled.buffer();
        FriendlyByteBuf friendlyByteBuf = new FriendlyByteBuf(buf);
        friendlyByteBuf.writeVarInt(id);
        packet.write(friendlyByteBuf);
        out.writeByteArray(buf.array());
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        MultiPaper.runSync(() -> {
            if (scoreboardId != null) {
                throw new UnsupportedOperationException("Modifying scoreboard '" + scoreboardId + "' is not supported!");
            }

            updating = true;

            ServerScoreboard scoreboard = MinecraftServer.getServer().getScoreboard();

            if (packet instanceof ClientboundSetPlayerTeamPacket setPlayerTeamPacket) {
                handle(scoreboard, setPlayerTeamPacket);
            } else if (packet instanceof ClientboundSetScorePacket setScorePacket) {
                handle(scoreboard, setScorePacket);
            } else if (packet instanceof ClientboundSetObjectivePacket setObjectivePacket) {
                handle(scoreboard, criteria, setObjectivePacket);
            } else if (packet instanceof ClientboundSetDisplayObjectivePacket setDisplayObjectivePacket) {
                handle(scoreboard, setDisplayObjectivePacket);
            } else {
                LOGGER.warn("Unhandled scoreboard update packet " + packet);
            }

            updating = false;
        });
    }

    private void handle(ServerScoreboard scoreboard, ClientboundSetPlayerTeamPacket setPlayerTeamPacket) {
        PlayerTeam team = scoreboard.getPlayerTeam(setPlayerTeamPacket.getName());

        if (setPlayerTeamPacket.getTeamAction() == ClientboundSetPlayerTeamPacket.Action.ADD && team == null) {
            team = scoreboard.addPlayerTeam(setPlayerTeamPacket.getName());
        } else if (setPlayerTeamPacket.getTeamAction() == ClientboundSetPlayerTeamPacket.Action.REMOVE) {
            scoreboard.removePlayerTeam(team);
        }

        try {
            if (setPlayerTeamPacket.getPlayerAction() == ClientboundSetPlayerTeamPacket.Action.ADD) {
                for (String player : setPlayerTeamPacket.getPlayers()) {
                    scoreboard.addPlayerToTeam(player, team);
                }
            } else if (setPlayerTeamPacket.getPlayerAction() == ClientboundSetPlayerTeamPacket.Action.REMOVE) {
                for (String player : setPlayerTeamPacket.getPlayers()) {
                    scoreboard.removePlayerFromTeam(player, team);
                }
            }
        } catch (Exception e) {
            // These can be usually safely ignored, just an innocent race condition
        }

        if (setPlayerTeamPacket.getParameters().isPresent()) {
            ClientboundSetPlayerTeamPacket.Parameters parameters = setPlayerTeamPacket.getParameters().get();
            team.setDisplayName(parameters.getDisplayName());
            team.unpackOptions(team.packOptions());
            team.setNameTagVisibility(Team.Visibility.byName(parameters.getNametagVisibility()));
            team.setCollisionRule(Team.CollisionRule.byName(parameters.getCollisionRule()));
            team.setColor(parameters.getColor());
            team.setPlayerPrefix(parameters.getPlayerPrefix());
            team.setPlayerSuffix(parameters.getPlayerSuffix());
        }
    }

    private void handle(ServerScoreboard scoreboard, ClientboundSetScorePacket setScorePacket) {
        Objective objective = scoreboard.getObjective(setScorePacket.getObjectiveName());

        if (setScorePacket.getMethod() == ServerScoreboard.Method.CHANGE) {
            Score score = scoreboard.getOrCreatePlayerScore(setScorePacket.getOwner(), objective);
            score.setScore(setScorePacket.getScore());
        } else if (setScorePacket.getMethod() == ServerScoreboard.Method.REMOVE) {
            scoreboard.resetPlayerScore(setScorePacket.getOwner(), objective);
        }
    }

    private void handle(ServerScoreboard scoreboard, String criteria, ClientboundSetObjectivePacket setObjectivePacket) {
        Objective objective = scoreboard.getObjective(setObjectivePacket.getObjectiveName());
        ObjectiveCriteria objectiveCriteria = criteria == null ? null : ObjectiveCriteria.byName(criteria).get();

        if (setObjectivePacket.getMethod() == ClientboundSetObjectivePacket.METHOD_ADD && objective == null) {
            objective = scoreboard.addObjective(setObjectivePacket.getObjectiveName(), objectiveCriteria, setObjectivePacket.getDisplayName(), setObjectivePacket.getRenderType());
        } else if (setObjectivePacket.getMethod() == ClientboundSetObjectivePacket.METHOD_REMOVE) {
            scoreboard.removeObjective(objective);
        } if (setObjectivePacket.getMethod() == ClientboundSetObjectivePacket.METHOD_CHANGE || setObjectivePacket.getMethod() == ClientboundSetObjectivePacket.METHOD_ADD) {
            objective.setDisplayName(setObjectivePacket.getDisplayName());
            objective.setRenderType(setObjectivePacket.getRenderType());
        }
    }

    private void handle(ServerScoreboard scoreboard, ClientboundSetDisplayObjectivePacket setDisplayObjectivePacket) {
        Objective objective = scoreboard.getObjective(setDisplayObjectivePacket.getObjectiveName());

        scoreboard.setDisplayObjective(setDisplayObjectivePacket.getSlot(), objective);
    }
}
