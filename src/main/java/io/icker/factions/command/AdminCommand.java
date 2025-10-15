package io.icker.factions.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.icker.factions.FactionsMod;
import io.icker.factions.api.persistents.Claim;
import io.icker.factions.api.persistents.Faction;
import io.icker.factions.api.persistents.User;
import io.icker.factions.ui.AdminGui;
import io.icker.factions.util.Command;
import io.icker.factions.util.Message;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.UUID;

public class AdminCommand implements Command {
    private int gui(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();

        // Show UI
        new AdminGui(player);

        return 1;
    }

    private int bypass(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();

        User user = User.get(player.getUuid());
        boolean bypass = !user.bypass;
        user.bypass = bypass;

        new Message(Text.translatable("factions.gui.admin.options.bypass.success"))
                .filler("·")
                .add(
                        new Message(
                                        user.bypass
                                                ? Text.translatable("options.on")
                                                : Text.translatable("options.off"))
                                .format(user.bypass ? Formatting.GREEN : Formatting.RED))
                .send(player, false);

        return 1;
    }

    private int reload(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        FactionsMod.dynmap.reloadAll();
        new Message(Text.translatable("factions.gui.admin.options.reload_dynmap.success"))
                .send(context.getSource().getPlayerOrThrow(), false);
        return 1;
    }

    private int power(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();

        Faction target = Faction.getByName(StringArgumentType.getString(context, "faction"));
        int power = IntegerArgumentType.getInteger(context, "power");

        target.addAdminPower(power);

        if (power != 0) {
            if (power > 0) {
                new Message(
                                Text.translatable(
                                        "factions.gui.power.success.added.faction",
                                        player.getName().getString(),
                                        power))
                        .send(target);
                new Message(Text.translatable("factions.gui.power.success.added.admin", power))
                        .send(player, false);
            } else {
                new Message(
                                Text.translatable(
                                        "factions.gui.power.success.removed.faction",
                                        player.getName().getString(),
                                        power))
                        .send(target);
                new Message(Text.translatable("factions.gui.power.success.removed.admin", power))
                        .send(player, false);
            }
        } else {
            new Message(Text.translatable("factions.gui.power.fail.nochange"))
                    .fail()
                    .send(player, false);
        }

        return 1;
    }

    private int spoof(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getPlayerOrThrow();

        User user = User.get(player.getUuid());

        String name = StringArgumentType.getString(context, "player");

        User target;

        UUID targetId = null;

// 1) Try an online player first (no cache API needed)
        ServerPlayerEntity online = source.getServer().getPlayerManager().getPlayer(name);
        if (online != null) {
            targetId = online.getUuid();
        } else {
            // 2) If the input is a UUID, use it directly
            try {
                targetId = UUID.fromString(name);
            } catch (IllegalArgumentException ignored) { }
        }

        if (targetId == null) {
            new Message(Text.translatable("factions.gui.spoof.fail.no_player", name))
                    .format(Formatting.RED)
                    .send(player, false);
            return 0;
        }

        target = User.get(targetId);


        user.setSpoof(target);

        new Message(Text.translatable("factions.gui.spoof.success", name)).send(player, false);

        return 1;
    }

    private int clearSpoof(CommandContext<ServerCommandSource> context)
            throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getPlayerOrThrow();

        User user = User.get(player.getUuid());

        user.setSpoof(null);

        new Message(Text.translatable("factions.gui.admin.options.spoof.clear.success"))
                .send(player, false);

        return 1;
    }

    private int audit(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        for (int i = 0; i < 4; i++) {
            Claim.audit();
            Faction.audit();
            User.audit();
        }

        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getPlayerOrThrow();

        if (player != null) {
            new Message(Text.translatable("factions.gui.admin.options.audit.success"))
                    .send(player, false);
        }

        return 1;
    }

    public LiteralCommandNode<ServerCommandSource> getNode() {
        return CommandManager.literal("admin")
                .requires(
                        Requires.hasPerms(
                                "factions.admin.gui", FactionsMod.CONFIG.REQUIRED_BYPASS_LEVEL))
                .executes(this::gui)
                .then(
                        CommandManager.literal("bypass")
                                .requires(
                                        Requires.hasPerms(
                                                "factions.admin.bypass",
                                                FactionsMod.CONFIG.REQUIRED_BYPASS_LEVEL))
                                .executes(this::bypass))
                .then(
                        CommandManager.literal("reload")
                                .requires(
                                        Requires.multiple(
                                                Requires.hasPerms(
                                                        "factions.admin.reload",
                                                        FactionsMod.CONFIG.REQUIRED_BYPASS_LEVEL),
                                                source -> FactionsMod.dynmap != null))
                                .executes(this::reload))
                .then(
                        CommandManager.literal("power")
                                .requires(
                                        Requires.hasPerms(
                                                "factions.admin.power",
                                                FactionsMod.CONFIG.REQUIRED_BYPASS_LEVEL))
                                .then(
                                        CommandManager.argument(
                                                        "power", IntegerArgumentType.integer())
                                                .then(
                                                        CommandManager.argument(
                                                                        "faction",
                                                                        StringArgumentType
                                                                                .greedyString())
                                                                .suggests(Suggests.allFactions())
                                                                .executes(this::power))))
                .then(
                        CommandManager.literal("spoof")
                                .requires(
                                        Requires.hasPerms(
                                                "factions.admin.spoof",
                                                FactionsMod.CONFIG.REQUIRED_BYPASS_LEVEL))
                                .then(
                                        CommandManager.argument(
                                                        "player", StringArgumentType.string())
                                                .suggests(Suggests.allPlayers())
                                                .executes(this::spoof))
                                .executes(this::clearSpoof))
                .then(
                        CommandManager.literal("audit")
                                .requires(
                                        Requires.hasPerms(
                                                "factions.admin.audit",
                                                FactionsMod.CONFIG.REQUIRED_BYPASS_LEVEL))
                                .executes(this::audit))
                .build();
    }
}
