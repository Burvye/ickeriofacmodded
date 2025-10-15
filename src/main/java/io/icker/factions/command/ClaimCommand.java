package io.icker.factions.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.icker.factions.FactionsMod;
import io.icker.factions.api.events.ClaimEvents;
import io.icker.factions.api.persistents.Claim;
import io.icker.factions.api.persistents.Faction;
import io.icker.factions.api.persistents.User;
import io.icker.factions.util.Command;
import io.icker.factions.util.FactionsSafe;
import io.icker.factions.util.Message;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.ChunkPos;

import java.util.*;
import java.util.stream.Collectors;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class ClaimCommand implements Command {


    private int listGrants(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
        ServerWorld world = ctx.getSource().getWorld();
        ChunkPos pos = world.getChunk(player.getBlockPos()).getPos();
        String dimension = world.getRegistryKey().getValue().toString();
        Claim claim = Claim.get(pos.x, pos.z, dimension);
        if (claim == null) {
            new Message(Text.translatable("factions.command.claim.grants.fail.not_claimed"))
                    .fail().send(player, false);
            return 0;
        }
        if (claim.isBuffer()) {
            new Message(Text.translatable("factions.command.claim.grants.fail.buffer"))
                    .fail().send(player, false);
            return 0;
        }
        Faction faction = Command.getUser(player).getFaction();
        if (faction == null || claim.getFaction() == null || !claim.getFaction().getID().equals(faction.getID())) {
            new Message(Text.translatable("factions.command.claim.grants.fail.not_owned"))
                    .fail().send(player, false);
            return 0;
        }
        if (claim.permittedPlayers.isEmpty()) {
            new Message(Text.translatable("factions.command.claim.grants.none",
                    pos.x, pos.z))
                    .format(Formatting.GRAY)
                    .send(player, false);
            return 1;
        }
        // Build list of granted player names with online status
        List<String> grantedNames = new ArrayList<>();
        var server = ctx.getSource().getServer(); // Changed from player.getServer()
        for (UUID uuid : claim.permittedPlayers) {
            ServerPlayerEntity grantedPlayer = server.getPlayerManager().getPlayer(uuid);
            if (grantedPlayer != null) {
                // Online - show in green
                grantedNames.add(Formatting.GREEN + grantedPlayer.getGameProfile().name() + Formatting.RESET);
            } else {
                // Offline - show in gray with UUID
                grantedNames.add(Formatting.GRAY + uuid.toString() + Formatting.RESET);
            }
        }
        new Message(Text.translatable("factions.command.claim.grants.list",
                pos.x, pos.z, claim.permittedPlayers.size()))
                .format(Formatting.YELLOW)
                .filler("»")
                .add(String.join(", ", grantedNames))
                .send(player, false);
        return 1;
    }

    private int list(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getPlayerOrThrow();
        Faction faction = Command.getUser(player).getFaction();
        if (faction == null) {
            new Message(Text.translatable("factions.command.claim.fail.no_faction"))
                    .fail()
                    .send(player, false);
            return 0;
        }
        UUID factionId = faction.getID();

        // --- new: fetch paid vs buffer chunks ---
        List<Claim> paidChunks   = Claim.getPaidByFaction(factionId);
        List<Claim> bufferChunks = Claim.getBufferByFaction(factionId);

        int paidCount   = paidChunks.size();
        int bufferCount = bufferChunks.size();

        // show “You have X paid, +Y buffer”
        new Message(
                Text.translatable(
                        "factions.command.claim.list",
                        Text.literal(String.valueOf(paidCount)).formatted(Formatting.YELLOW),
                        Text.literal("+" + bufferCount + " buffer").formatted(Formatting.GRAY)
                )
        ).send(player, false);

        // if no paid claims, don’t list coordinates
        if (paidCount == 0) return 1;

        // --- build the per‐world map just from paidChunks ---
        HashMap<String, ArrayList<Claim>> claimsMap = new HashMap<>();
        paidChunks.forEach(claim -> {
            claimsMap.putIfAbsent(claim.level, new ArrayList<>());
            claimsMap.get(claim.level).add(claim);
        });

        // now print exactly as before
        Message claimText = new Message();
        claimsMap.forEach((level, array) -> {
            claimText.add("\n");
            claimText.add(
                    new Message(Text.translatable("factions.level." + level))
                            .format(Formatting.GRAY)
            );
            claimText.filler("»");
            claimText.add(
                    array.stream()
                            .map(c -> String.format("(%d,%d)", c.x, c.z))
                            .collect(Collectors.joining(", "))
            );
        });
        claimText.format(Formatting.ITALIC).send(player, false);
        return 1;
    }

    private int addForced(CommandContext<ServerCommandSource> context, int size)
            throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getPlayerOrThrow();
        ServerWorld world = context.getSource().getWorld();

        Faction faction = Command.getUser(player).getFaction();
        if (faction == null) {
            new Message(Text.translatable("factions.command.claim.fail.no_faction"))
                    .fail()
                    .send(player, false);
            return 0;
        }
        String dimension = world.getRegistryKey().getValue().toString();
        ArrayList<ChunkPos> chunks = new ArrayList<>();

        for (int x = -size + 1; x < size; x++) {
            for (int y = -size + 1; y < size; y++) {
                ChunkPos chunkPos = world
                        .getChunk(player.getBlockPos().add(x * 16, 0, y * 16))
                        .getPos();
                Claim existingClaim = Claim.get(chunkPos.x, chunkPos.z, dimension);

                // Check the chunk itself
                if (existingClaim != null) {
                    // 1) Prevent other factions from claiming *your* buffer zone
                    if (existingClaim.isBuffer()
                            && !Objects.equals(existingClaim.getFaction(), faction)) {
                        new Message(
                                Text.translatable(
                                        "factions.command.claim.add.fail.already_buffer",
                                        Objects.requireNonNull(existingClaim.getFaction()).getName()
                                )
                        ).fail().send(player, false);
                        return 0;
                    }
                    // 2) Block any real claim that isn't yours
                    if (!existingClaim.isBuffer()) {
                        if (size == 1) {
                            boolean isActorOwner = Objects.equals(existingClaim.getFaction(), faction);
                            new Message(
                                    Text.translatable(
                                            "factions.command.claim.add.fail.already_owned.single",
                                            Text.translatable(
                                                    "factions.command.claim.add.fail.already_owned.single."
                                                            + (isActorOwner ? "your" : "another")
                                            )
                                    )
                            ).fail().send(player, false);
                            return 0;
                        } else if (!Objects.equals(existingClaim.getFaction(), faction)) {
                            new Message(
                                    Text.translatable(
                                            "factions.command.claim.add.fail.already_owned.multiple"
                                    )
                            ).fail().send(player, false);
                            return 0;
                        }
                    }
                }

                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dz == 0) continue; // Skip the center chunk

                        Claim adjacentClaim = Claim.get(chunkPos.x + dx, chunkPos.z + dz, dimension);

                        // If an adjacent chunk is another faction's buffer, block the claim
                        if (adjacentClaim != null
                                && adjacentClaim.isBuffer()
                                && !Objects.equals(adjacentClaim.getFaction(), faction)) {
                            new Message(
                                    Text.translatable(
                                            "factions.command.claim.add.fail.adjacent_to_buffer",
                                            Objects.requireNonNull(adjacentClaim.getFaction()).getName()
                                    )
                            ).fail().send(player, false);
                            return 0;
                        }
                    }
                }

                chunks.add(chunkPos);
            }
        }

        chunks.forEach(chunk -> faction.addClaim(chunk.x, chunk.z, dimension));

        if (size == 1) {
            new Message(
                    Text.translatable(
                            "factions.command.claim.add.success.single",
                            chunks.get(0).x,
                            chunks.get(0).z,
                            player.getName().getString()
                    )
            ).send(faction);
        } else {
            new Message(
                    Text.translatable(
                            "factions.command.claim.add.success.multiple",
                            chunks.get(0).x,
                            chunks.get(0).z,
                            chunks.get(0).x + size - 1,
                            chunks.get(0).z + size - 1,
                            player.getName().getString()
                    )
            ).send(faction);
        }

        return 1;
    }


    private int add(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
        Faction faction = Command.getUser(player).getFaction();
        if (faction == null) {
            new Message(Text.translatable("factions.command.claim.fail.no_faction"))
                    .fail()
                    .send(player, false);
            return 0;
        }

        int requiredPower = FactionsSafe.requiredPowerToClaim(faction, FactionsMod.CONFIG.POWER.CLAIM_WEIGHT);

        int maxPower =
                faction.getUsers().size() * FactionsMod.CONFIG.POWER.MEMBER
                        + FactionsMod.CONFIG.POWER.BASE
                        + faction.getAdminPower();

        if (maxPower < requiredPower) {
            new Message(Text.translatable("factions.command.claim.add.fail.lacks_power"))
                    .fail()
                    .send(player, false);
            return 0;
        }

        return addForced(context, 1);
    }

    private int addSize(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        int size = IntegerArgumentType.getInteger(context, "size");
        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
        Faction faction = Command.getUser(player).getFaction();
        if (faction == null) {
            new Message(Text.translatable("factions.command.claim.fail.no_faction"))
                    .fail()
                    .send(player, false);
            return 0;
        }

        int requiredPower = FactionsSafe.requiredPowerToClaim(faction, FactionsMod.CONFIG.POWER.CLAIM_WEIGHT);

        int maxPower =
                faction.getUsers().size() * FactionsMod.CONFIG.POWER.MEMBER
                        + FactionsMod.CONFIG.POWER.BASE
                        + faction.getAdminPower();

        if (maxPower < requiredPower) {
            new Message(Text.translatable("factions.command.claim.add.fail.lacks_power.multiple"))
                    .fail()
                    .send(player, false);
            return 0;
        }

        return addForced(context, size);
    }

    private int remove(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();

        ServerPlayerEntity player = source.getPlayerOrThrow();
        ServerWorld world = context.getSource().getWorld();

        ChunkPos chunkPos = world.getChunk(player.getBlockPos()).getPos();
        String dimension = world.getRegistryKey().getValue().toString();

        Claim existingClaim = Claim.get(chunkPos.x, chunkPos.z, dimension);

        if (existingClaim == null) {
            new Message(Text.translatable("factions.command.claim.remove.fail.unclaimed"))
                    .fail()
                    .send(player, false);
            return 0;
        }

        User user = Command.getUser(player);
        Faction faction = user.getFaction();
        if (faction == null) {
            new Message(Text.translatable("factions.command.fail.no_faction")).fail().send(player, false);
            return 0;
        }

        if (!user.bypass && Objects.requireNonNull(existingClaim.getFaction()).getID() != faction.getID()) {
            new Message(Text.translatable("factions.command.claim.remove.fail.another_owner"))
                    .fail()
                    .send(player, false);
            return 0;
        }

        existingClaim.remove();
        new Message(
                        Text.translatable(
                                "factions.command.claim.remove.success.single",
                                existingClaim.x,
                                existingClaim.z,
                                player.getName().getString()))
                .send(faction);
        return 1;
    }

    private int removeSize(CommandContext<ServerCommandSource> context)
            throws CommandSyntaxException {
        int size = IntegerArgumentType.getInteger(context, "size");
        ServerCommandSource source = context.getSource();

        ServerPlayerEntity player = source.getPlayerOrThrow();
        ServerWorld world = context.getSource().getWorld();
        String dimension = world.getRegistryKey().getValue().toString();

        User user = Command.getUser(player);
        Faction faction = user.getFaction();
        if (faction == null) {
            new Message(Text.translatable("factions.command.fail.no_faction")).fail().send(player, false);
            return 0;
        }

        for (int x = -size + 1; x < size; x++) {
            for (int y = -size + 1; y < size; y++) {
                ChunkPos chunkPos =
                        world.getChunk(player.getBlockPos().add(x * 16, 0, y * 16)).getPos();
                Claim existingClaim = Claim.get(chunkPos.x, chunkPos.z, dimension);

                if (existingClaim != null
                        && (user.bypass || Objects.equals(existingClaim.getFaction(), faction)))
                    existingClaim.remove();
            }
        }

        ChunkPos chunkPos =
                world.getChunk(player.getBlockPos().add((-size + 1) * 16, 0, (-size + 1) * 16))
                        .getPos();
        new Message(
                        Text.translatable(
                                "factions.command.claim.remove.success.multiple",
                                chunkPos.x,
                                chunkPos.z,
                                chunkPos.x + size - 1,
                                chunkPos.z + size - 1,
                                player.getName().getString()))
                .send(faction);

        return 1;
    }

    private int removeAll(CommandContext<ServerCommandSource> context)
            throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getPlayerOrThrow();

        Faction faction = Command.getUser(player).getFaction();
        if (faction == null) {
            new Message(Text.translatable("factions.command.claim.fail.no_faction"))
                    .fail()
                    .send(player, false);
            return 0;
        }

        faction.removeAllClaims();
        new Message(
                        Text.translatable(
                                "factions.command.claim.remove.success.all",
                                player.getName().getString()))
                .send(faction);
        return 1;
    }
    private int grant(CommandContext<ServerCommandSource> ctx, boolean revoke)
            throws CommandSyntaxException {
        ServerPlayerEntity me = ctx.getSource().getPlayerOrThrow();
        ServerWorld world = ctx.getSource().getWorld();

        ChunkPos pos = world.getChunk(me.getBlockPos()).getPos();
        String dimension = world.getRegistryKey().getValue().toString();
        Claim claim = Claim.get(pos.x, pos.z, dimension);

        // Check if claimed
        if (claim == null) {
            new Message(Text.translatable("factions.command.claim.grant.fail.not_claimed"))
                    .fail().send(me, false);
            return 0;
        }

        // Check if buffer
        if (claim.isBuffer()) {
            new Message(Text.translatable("factions.command.claim.grant.fail.buffer"))
                    .fail().send(me, false);
            return 0;
        }

        Faction faction = Command.getUser(me).getFaction();
        if (faction == null || claim.getFaction() == null || !claim.getFaction().getID().equals(faction.getID())) {
            new Message(Text.translatable("factions.command.claim.grant.fail.not_owned"))
                    .fail().send(me, false);
            return 0;
        }

        String targetName = StringArgumentType.getString(ctx, "player");
        ServerPlayerEntity target = ctx.getSource().getServer()
                .getPlayerManager().getPlayer(targetName);
        if (target == null) {
            new Message(Text.translatable("factions.command.claim.grant.fail.not_online", targetName))
                    .fail().send(me, false);
            return 0;
        }

        User targetUser = Command.getUser(target);
        Faction targetFaction = targetUser.getFaction();

// Check if target is in same faction
        if (targetFaction == null) {
            new Message(Text.translatable("factions.command.claim.grant.fail.target_no_faction", targetName))
                    .fail().send(me, false);
            return 0;
        }

        if (!targetFaction.getID().equals(faction.getID())) {
            new Message(Text.translatable("factions.command.claim.grant.fail.target_different_faction",
                    targetName, targetFaction.getName()))
                    .fail().send(me, false);
            return 0;
        }

        if (revoke) {
            claim.revoke(target.getUuid());
            new Message(Text.translatable("factions.command.claim.revoke.success",
                    targetName, pos.x, pos.z)).send(me, false);
            // Notify target if online
            new Message(Text.translatable("factions.command.claim.revoke.notify",
                    pos.x, pos.z)).format(Formatting.YELLOW).send(target, false);
        } else {
            // Use the return value properly!
            boolean success = claim.grant(target.getUuid(), me.getUuid());
            if (success) {
                new Message(Text.translatable("factions.command.claim.grant.success",
                        targetName, pos.x, pos.z)).send(me, false);
                // Notify target if online
                new Message(Text.translatable("factions.command.claim.grant.notify",
                        pos.x, pos.z)).format(Formatting.GREEN).send(target, false);
            } else {
                new Message(Text.translatable("factions.command.claim.grant.fail.already_granted",
                        targetName)).fail().send(me, false);
                return 0;
            }
        }
        Claim.save();
        return 1;
    }

    private int auto(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getPlayerOrThrow();

        User user = Command.getUser(player);
        user.autoclaim = !user.autoclaim;
        Faction faction = Command.getUser(player).getFaction();
        if (faction == null) {
            new Message(Text.translatable("factions.command.fail.no_faction")).fail().send(player, false);
            return 0;
        }
        new Message(Text.translatable("factions.command.claim.auto.toggled"))
                .filler("·")
                .add(
                        new Message(Text.translatable("options." + (user.autoclaim ? "on" : "off")))
                                .format(user.autoclaim ? Formatting.GREEN : Formatting.RED))
                .send(player, false);

        return 1;
    }

    @SuppressWarnings("")
    private int setAccessLevel(CommandContext<ServerCommandSource> context, boolean increase)
            throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getPlayerOrThrow();
        ServerWorld world = player.getEntityWorld();

        ChunkPos chunkPos = world.getChunk(player.getBlockPos()).getPos();
        String dimension = world.getRegistryKey().getValue().toString();
        Claim claim = Claim.get(chunkPos.x, chunkPos.z, dimension);

        if (claim == null) {
            new Message(Text.translatable("factions.command.claim.set_access_level.fail.unclaimed"))
                    .fail().send(player, false);
            return 0;
        }

        User user = Command.getUser(player);
        Faction faction = user.getFaction();
        if (faction == null) {
            new Message(Text.translatable("factions.command.fail.no_faction"))
                    .fail().send(player, false);
            return 0;
        }
        if (faction == null || claim.getFaction() == null || !claim.getFaction().getID().equals(faction.getID())) {
            new Message(Text.translatable("factions.command.claim.set_access_level.fail.another_owner"))
                    .fail().send(player, false);
            return 0;
        }

        // Change level
        if (increase) {
            switch (claim.accessLevel) {
                case OWNER -> {
                    new Message(Text.translatable("factions.command.claim.set_access_level.fail.max_level"))
                            .fail().send(player, false);
                    return 0;
                }
                case LEADER -> claim.accessLevel = User.Rank.OWNER;
                case COMMANDER -> claim.accessLevel = User.Rank.LEADER;
                case MEMBER -> claim.accessLevel = User.Rank.COMMANDER;
                case GUEST -> {
                    // GUEST is the minimum internal state for a claim; nothing below to increase from.
                    new Message(Text.translatable("factions.command.claim.set_access_level.fail.invalid_state"))
                            .fail().send(player, false);
                    return 0;
                }
            }
        } else { // decrease
            switch (claim.accessLevel) {
                case OWNER -> claim.accessLevel = User.Rank.LEADER;
                case LEADER -> claim.accessLevel = User.Rank.COMMANDER;
                case COMMANDER -> claim.accessLevel = User.Rank.MEMBER;
                case MEMBER -> {
                    new Message(Text.translatable("factions.command.claim.set_access_level.fail.min_level"))
                            .fail().send(player, false);
                    return 0;
                }
                case GUEST -> {
                    new Message(Text.translatable("factions.command.claim.set_access_level.fail.invalid_state"))
                            .fail().send(player, false);
                    return 0;
                }
            }
        }

        ClaimEvents.MODIFY.invoker().onModify(claim);
        new Message(
                Text.translatable(
                        "factions.command.claim.set_access_level.success",
                        claim.x, claim.z,
                        claim.accessLevel.toString(),
                        player.getName().getString()
                )
        ).send(faction);
        return 1;
    }


    @Override
    public LiteralCommandNode<ServerCommandSource> getNode() {
        return CommandManager.literal("claim")
                .requires(Requires.isCommander())
                .then(
                        CommandManager.literal("add")
                                .requires(Requires.hasPerms("factions.claim.add", 0))
                                .then(
                                        CommandManager.argument(
                                                        "size", IntegerArgumentType.integer(1, 7))
                                                .requires(
                                                        Requires.hasPerms(
                                                                "factions.claim.add.size", 0))
                                                .then(
                                                        CommandManager.literal("force")
                                                                .requires(
                                                                        Requires.hasPerms(
                                                                                        "factions.claim.add.force",
                                                                                        0)
                                                                                .and(
                                                                                        Requires
                                                                                                .isLeader()))
                                                                .executes(
                                                                        context ->
                                                                                addForced(
                                                                                        context,
                                                                                        IntegerArgumentType
                                                                                                .getInteger(
                                                                                                        context,
                                                                                                        "size"))))
                                                .executes(this::addSize))
                                .executes(this::add))
                .then(
                        CommandManager.literal("list")
                                .requires(Requires.hasPerms("factions.claim.list", 0))
                                .executes(this::list))
                .then(
                        CommandManager.literal("remove")
                                .requires(
                                        Requires.hasPerms("factions.claim.remove", 0)
                                                .and(Requires.isLeader()))
                                .then(
                                        CommandManager.argument(
                                                        "size", IntegerArgumentType.integer(1, 7))
                                                .requires(
                                                        Requires.hasPerms(
                                                                "factions.claim.remove.size", 0))
                                                .executes(this::removeSize))
                                .then(
                                        CommandManager.literal("all")
                                                .requires(
                                                        Requires.hasPerms(
                                                                "factions.claim.remove.all", 0))
                                                .executes(this::removeAll))
                                .executes(this::remove))
                .then(
                        CommandManager.literal("auto")
                                .requires(Requires.hasPerms("factions.claim.auto", 0))
                                .executes(this::auto))
                .then(
                        CommandManager.literal("access")
                                .requires(Requires.hasPerms("factions.claim.access", 0))
                                .then(
                                        CommandManager.literal("increase")
                                                .requires(
                                                        Requires.hasPerms(
                                                                "factions.claim.access.increase",
                                                                0))
                                                .executes(
                                                        (context) -> setAccessLevel(context, true)))
                                .then(
                                        CommandManager.literal("decrease")
                                                .requires(
                                                        Requires.hasPerms(
                                                                "factions.claim.access.decrease",
                                                                0))
                                                .executes(
                                                        (context) ->
                                                                setAccessLevel(context, false))))
                .then(literal("grant")
                        .requires(Requires.hasPerms("factions.claim.grant",0).and(Requires.isLeader()))
                        .then(argument("player", StringArgumentType.word())
                                .suggests((ctx, b) -> {
                                    ctx.getSource().getServer().getPlayerManager().getPlayerList()
                                            .forEach(p -> b.suggest(p.getGameProfile().name())
);
                                    return b.buildFuture();
                                })
                                .executes(ctx -> grant(ctx, false))
                        )
                )
                // Add this to the command tree in getNode():
                .then(literal("grants")
                        .requires(Requires.hasPerms("factions.claim.grants", 0))
                        .executes(this::listGrants)
                )
                .then(literal("revoke")
                        .requires(Requires.hasPerms("factions.claim.revoke",0).and(Requires.isLeader()))
                        .then(argument("player", StringArgumentType.word())
                                .suggests((ctx, b) -> {
                                    ctx.getSource().getServer().getPlayerManager().getPlayerList()
                                            .forEach(p -> b.suggest(p.getGameProfile().name())
);
                                    return b.buildFuture();
                                })
                                // <-- here we pass true for revoke
                                .executes(ctx -> grant(ctx, true))
                        )
                )

                .build();
    }
}
