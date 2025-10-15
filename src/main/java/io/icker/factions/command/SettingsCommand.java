package io.icker.factions.command;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;

import io.icker.factions.api.persistents.User;
import io.icker.factions.util.Command;
import io.icker.factions.util.Message;

import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public class SettingsCommand implements Command {
    private int setChat(CommandContext<ServerCommandSource> context, User.ChatMode option)
            throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
        User user = User.get(player.getUuid());
        user.chat = option;

        // derive the lower-case name from the enum
        String chatName = option.name().toLowerCase();

        new Message(Text.translatable("factions.command.settings.chat"))
                .filler("·")
                .add(new Message(Text.translatable(
                        "factions.command.settings.chat." + chatName))
                        .format(Formatting.BLUE))
                .send(player, false);

        return 1;
    }

    private int setSounds(CommandContext<ServerCommandSource> context, User.SoundMode option)
            throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
        User user = User.get(player.getUuid());
        user.sounds = option;

        // derive the lower-case name from the enum
        String soundName = option.name().toLowerCase();

        new Message(Text.translatable("factions.command.settings.sound"))
                .filler("·")
                .add(new Message(Text.translatable(
                        "factions.command.settings.sound." + soundName))
                        .format(Formatting.BLUE))
                .send(player, false);

        return 1;
    }

    private int radar(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getPlayerOrThrow();

        User config = User.get(player.getUuid());
        boolean radar = !config.radar;
        config.radar = radar;

        new Message(Text.translatable("factions.command.settings.radar"))
                .filler("·")
                .add(new Message(Text.translatable("options." + (radar ? "on" : "off")))
                        .format(radar ? Formatting.GREEN : Formatting.RED))
                .send(player, false);

        return 1;
    }

    public LiteralCommandNode<ServerCommandSource> getNode() {
        return CommandManager.literal("settings")
                .requires(Requires.hasPerms("factions.settings", 0))
                .then(CommandManager.literal("chat")
                        .requires(Requires.hasPerms("factions.settings.chat", 0))
                        .then(CommandManager.literal("global")
                                .executes(ctx -> setChat(ctx, User.ChatMode.GLOBAL)))
                        .then(CommandManager.literal("faction")
                                .executes(ctx -> setChat(ctx, User.ChatMode.FACTION)))
                        .then(CommandManager.literal("focus")
                                .executes(ctx -> setChat(ctx, User.ChatMode.FOCUS))))
                .then(CommandManager.literal("radar")
                        .requires(Requires.hasPerms("factions.settings.radar", 0))
                        .executes(this::radar))
                .then(CommandManager.literal("sounds")
                        .requires(Requires.hasPerms("factions.settings.sounds", 0))
                        .then(CommandManager.literal("none")
                                .executes(ctx -> setSounds(ctx, User.SoundMode.NONE)))
                        .then(CommandManager.literal("warnings")
                                .executes(ctx -> setSounds(ctx, User.SoundMode.WARNINGS)))
                        .then(CommandManager.literal("faction")
                                .executes(ctx -> setSounds(ctx, User.SoundMode.FACTION)))
                        .then(CommandManager.literal("all")
                                .executes(ctx -> setSounds(ctx, User.SoundMode.ALL))))
                .build();
    }
}
