package io.icker.factions.command;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.minecraft.server.PlayerConfigEntry;
import io.icker.factions.FactionsMod;
import io.icker.factions.api.persistents.Faction;
import io.icker.factions.api.persistents.User;
import io.icker.factions.ui.MemberGui;
import io.icker.factions.util.Command;
import io.icker.factions.util.Message;

import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.UserCache;
import net.minecraft.util.Util;

import xyz.nucleoid.server.translations.api.Localization;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class MemberCommand implements Command {
    private int self(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getPlayerOrThrow();

        User user = Command.getUser(player);
        if (!user.isInFaction()) {
            new Message(Text.translatable("factions.command.members.fail.no_faction"))
                    .fail()
                    .send(player, false);
            return 0;
        }

        return members(player, user.getFaction());
    }

    private int any(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        String factionName = StringArgumentType.getString(context, "faction");

        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getPlayerOrThrow();

        Faction faction = Faction.getByName(factionName);
        if (faction == null) {
            new Message(Text.translatable("factions.command.members.faction.nonexistent_faction"))
                    .fail()
                    .send(player, false);
            return 0;
        }

        return members(player, faction);
    }

    // Comparator for sorting users by rank (highest to lowest)
    private static final Comparator<User> RANK_COMPARATOR = (u1, u2) -> {
        // Handle null ranks (shouldn't happen but be safe)
        if (u1.rank == null && u2.rank == null) return 0;
        if (u1.rank == null) return 1;
        if (u2.rank == null) return -1;

        // Compare by rank ordinal (OWNER=0, LEADER=1, COMMANDER=2, MEMBER=3, GUEST=4)
        // Lower ordinal = higher rank, so we want ascending order
        return Integer.compare(u1.rank.ordinal(), u2.rank.ordinal());
    };

    public static int members(ServerPlayerEntity player, Faction faction) {
        if (FactionsMod.CONFIG.GUI) {
            new MemberGui(player, faction, null);
            return 1;
        }

        List<User> users = faction.getUsers();

// get PlayerManager without calling getServer() on the player directly
        ServerWorld sw = (ServerWorld) player.getEntityWorld();
        var pm = sw.getServer().getPlayerManager();

// Sort users by rank from highest to lowest
        List<User> sortedUsers = users.stream()
                .sorted(RANK_COMPARATOR)
                .collect(Collectors.toList());
        // Generate the header
        int numDashes = 32 - faction.getName().length();
        String dashes = new StringBuilder("--------------------------------").substring(0, numDashes / 2);

        new Message(
                Formatting.BLACK
                        + dashes
                        + "[ "
                        + faction.getColor()
                        + faction.getName()
                        + Formatting.BLACK
                        + " ]"
                        + dashes)
                .send(player, false);

        new Message(
                Text.translatable(
                                "factions.command.members.faction.title",
                                Formatting.WHITE.toString() + users.size())
                        .formatted(Formatting.GOLD))
                .send(player, false);

        // Display members sorted by rank
        User.Rank currentRank = null;
        StringBuilder currentRankMembers = new StringBuilder();
        int currentRankCount = 0;

        for (User user : sortedUsers) {
            String userName = java.util.Optional.ofNullable(pm.getPlayer(user.getID()))
                    .map(p -> p.getGameProfile().name())  // in 1.21.x use .name()
                    .orElse("Unknown");


            // When we encounter a new rank, display the previous rank's members
            if (currentRank != null && user.rank != currentRank) {
                displayRankGroup(player, currentRank, currentRankCount, currentRankMembers.toString());
                currentRankMembers = new StringBuilder();
                currentRankCount = 0;
            }

            // Add this user to the current rank group
            if (currentRankMembers.length() > 0) {
                currentRankMembers.append(", ");
            }
            currentRankMembers.append(userName);
            currentRankCount++;
            currentRank = user.rank;
        }

        // Display the last rank group
        if (currentRank != null && currentRankCount > 0) {
            displayRankGroup(player, currentRank, currentRankCount, currentRankMembers.toString());
        }

        return 1;
    }

    private static void displayRankGroup(ServerPlayerEntity player, User.Rank rank, int count, String members) {
        Formatting rankColor = getRankColor(rank);
        String rankLabel = getRankLabel(rank);

        // For single member ranks (like OWNER), don't show count
        if (rank == User.Rank.OWNER) {
            new Message(
                    Text.translatable("factions.command.members.faction.owner",
                                    Formatting.WHITE + members)
                            .formatted(rankColor))
                    .send(player, false);
        } else {
            String translationKey = switch (rank) {
                case LEADER -> "factions.command.members.faction.leaders";
                case COMMANDER -> "factions.command.members.faction.commanders";
                case MEMBER -> "factions.command.members.faction.members";
                case GUEST -> "factions.command.members.faction.guests";
                default -> "factions.command.members.faction.members";
            };

            new Message(
                    Text.translatable(translationKey, count, Formatting.WHITE + members)
                            .formatted(rankColor))
                    .send(player, false);
        }
    }

    private static Formatting getRankColor(User.Rank rank) {
        return switch (rank) {
            case OWNER -> Formatting.DARK_RED;
            case LEADER -> Formatting.RED;
            case COMMANDER -> Formatting.GOLD;
            case MEMBER -> Formatting.YELLOW;
            case GUEST -> Formatting.GRAY;
        };
    }

    private static String getRankLabel(User.Rank rank) {
        return switch (rank) {
            case OWNER -> "Owner";
            case LEADER -> "Leaders";
            case COMMANDER -> "Commanders";
            case MEMBER -> "Members";
            case GUEST -> "Guests";
        };
    }

    public LiteralCommandNode<ServerCommandSource> getNode() {
        return CommandManager.literal("members")
                .requires(Command.Requires.hasPerms("factions.members", 0))
                .executes(this::self)
                .then(
                        CommandManager.argument("faction", StringArgumentType.greedyString())
                                .requires(Command.Requires.hasPerms("factions.members.other", 0))
                                .suggests(Command.Suggests.allFactions())
                                .executes(this::any))
                .build();
    }
}