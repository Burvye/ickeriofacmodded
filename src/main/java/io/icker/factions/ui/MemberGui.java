package io.icker.factions.ui;

import eu.pb4.sgui.api.ClickType;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import io.icker.factions.api.persistents.Faction;
import io.icker.factions.api.persistents.User;
import io.icker.factions.command.RankCommand;
import io.icker.factions.util.Command;
import io.icker.factions.util.GuiInteract;
import io.icker.factions.util.Icons;
import io.icker.factions.util.Message;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.jetbrains.annotations.Nullable;
import xyz.nucleoid.server.translations.api.Localization;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class MemberGui extends PagedGui {
    Faction faction;
    int size;
    User user;

    List<User> members;

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

    public MemberGui(ServerPlayerEntity player, Faction faction, @Nullable Runnable closeCallback) {
        super(player, closeCallback);
        this.faction = faction;
        this.user = User.get(player.getUuid());

        // Get all faction members and sort by rank
        this.members = new ArrayList<>(faction.getUsers());

        // Sort members by rank (highest to lowest)
        this.members.sort(RANK_COMPARATOR);

        if (faction.equals(this.user.getFaction())) {
            this.members.remove(user);
            this.members.add(0, user);
        }


        this.size = members.size();

        this.setTitle(
                Text.translatable(
                        "factions.gui.members.title",
                        Text.literal(faction.getColor() + faction.getName()),
                        size));
        this.updateDisplay();
        this.open();
    }

    @Override
    protected int getPageAmount() {
        if (this.size == 0) {
            return 1; // Always have at least 1 page
        }
        return (this.size + PAGE_SIZE - 1) / PAGE_SIZE;
    }

    @Override
    protected int getTotalElements() {
        return this.size;
    }

    @Override
    protected DisplayElement getElement(int id) {
        if (this.size > id) {
            var targetUser = this.members.get(id);

            // Try online profile first
            ServerPlayerEntity online =
                    player.getEntityWorld().getServer().getPlayerManager().getPlayer(targetUser.getID());

            // Fallback display name if offline/unknown
            String displayName = online != null
                    ? online.getGameProfile().name()
                    : Localization.raw("factions.gui.generic.unknown_player", player);

            var icon = new GuiElementBuilder(Items.PLAYER_HEAD);

            if (online != null) {
                // For online players, use their display name to show their skin
                icon.setSkullOwner(displayName);
            } else {
                // For offline players, use the unknown player texture
                icon.setSkullOwner(Icons.GUI_UNKNOWN_PLAYER);
            }


            // Rank-colored name
            Text nameText = switch (targetUser.rank) {
                case OWNER -> Text.literal(displayName).formatted(Formatting.DARK_RED, Formatting.BOLD);
                case LEADER -> Text.literal(displayName).formatted(Formatting.RED, Formatting.BOLD);
                case COMMANDER -> Text.literal(displayName).formatted(Formatting.GOLD);
                case MEMBER -> Text.literal(displayName).formatted(Formatting.YELLOW);
                case GUEST -> Text.literal(displayName).formatted(Formatting.GRAY);
            };
            icon.setName(nameText);

            // Rank color
            Formatting rankColor = switch (targetUser.rank) {
                case OWNER -> Formatting.DARK_RED;
                case LEADER -> Formatting.RED;
                case COMMANDER -> Formatting.GOLD;
                case MEMBER -> Formatting.GREEN;
                case GUEST -> Formatting.GRAY;
            };

            List<Text> lore = new ArrayList<>(List.of(
                    Text.translatable(
                            "factions.gui.members.entry.info.rank",
                            Text.translatable("factions.gui.members.entry.info.rank." + targetUser.getRankName())
                                    .setStyle(Style.EMPTY.withItalic(false).withColor(rankColor))
                    ).setStyle(Style.EMPTY.withItalic(false).withColor(Formatting.GRAY))
            ));

            // Online/offline status
            if (online != null) {
                lore.add(Text.literal("Status: ").formatted(Formatting.GRAY)
                        .append(Text.literal("Online").formatted(Formatting.GREEN)));
            } else {
                lore.add(Text.literal("Status: ").formatted(Formatting.GRAY)
                        .append(Text.literal("Offline").formatted(Formatting.RED)));
            }

            // Management actions (promote/demote/kick) if allowed
            if (!targetUser.getID().equals(player.getUuid())
                    && Command.Requires.isLeader().test(player.getCommandSource())
                    && Command.Requires.hasPerms("factions.rank.promote", 0).test(player.getCommandSource())
                    && faction.equals(user.getFaction())) {

                lore.add(Text.empty());
                lore.add(Text.translatable("factions.gui.members.entry.manage.promote")
                        .setStyle(Style.EMPTY.withItalic(false).withColor(Formatting.DARK_GREEN)));
                lore.add(Text.translatable("factions.gui.members.entry.manage.demote")
                        .setStyle(Style.EMPTY.withItalic(false).withColor(Formatting.DARK_RED)));
                lore.add(Text.translatable("factions.gui.members.entry.manage.kick")
                        .setStyle(Style.EMPTY.withItalic(false).withColor(Formatting.DARK_RED)));

                ServerPlayerEntity targetPlayer =
                        player.getEntityWorld().getServer().getPlayerManager().getPlayer(targetUser.getID());

                icon.setCallback((index, clickType, actionType) -> {
                    GuiInteract.playClickSound(player);

                    if (clickType == ClickType.MOUSE_LEFT) {
                        try {
                            RankCommand.execPromote(targetUser, player);
                            new Message(
                                    // In the promote callback:
                                    Text.translatable(
                                            "factions.gui.members.entry.manage.promote.result",
                                            displayName,  // Changed from profile.name()
                                            Text.translatable("factions.gui.members.entry.info.rank." + targetUser.getRankName())
                                    )
                            ).prependFaction(faction).send(player, false);
                        } catch (Exception e) {
                            new Message(e.getMessage()).format(Formatting.RED).send(player, false);
                            return;
                        }
                    }

                    if (clickType == ClickType.MOUSE_RIGHT) {
                        try {
                            RankCommand.execDemote(targetUser, player);
                            new Message(
                                    Text.translatable(
                                            "factions.gui.members.entry.manage.demote.result",
                                            displayName,  // Changed from profile.name()
                                            Text.translatable("factions.gui.members.entry.info.rank." + targetUser.getRankName())
                                    )
                            ).prependFaction(faction).send(player, false);
                        } catch (Exception e) {
                            new Message(e.getMessage()).format(Formatting.RED).send(player, false);
                            return;
                        }
                    }

                    if (clickType == ClickType.DROP) {
                        SimpleGui gui = new SimpleGui(ScreenHandlerType.HOPPER, player, false);
                        for (int i = 0; i < 5; i++) {
                            gui.setSlot(i, new GuiElementBuilder(Items.WHITE_STAINED_GLASS_PANE).hideTooltip());
                        }
                        gui.setTitle(Text.translatable("factions.gui.members.entry.manage.kick.confirm.title"));
                        gui.setSlot(1,
                                new GuiElementBuilder(Items.SLIME_BALL)
                                        .setName(Text.translatable(
                                                "factions.gui.members.entry.manage.kick.confirm.yes", displayName
                                        ).formatted(Formatting.GREEN))
                                        .setCallback((index2, clickType2, actionType2) -> {
                                            if (user.rank == User.Rank.LEADER &&
                                                    (targetUser.rank == User.Rank.LEADER || targetUser.rank == User.Rank.OWNER)) {
                                                new Message(Text.translatable("factions.command.kick.fail.high_rank"))
                                                        .format(Formatting.RED).send(player, false);
                                                return;
                                            }

                                            GuiInteract.playClickSound(player);
                                            targetUser.leaveFaction();

                                            new Message(
                                                    Text.translatable(
                                                            "factions.gui.members.entry.manage.kick.result.actor",
                                                            displayName
                                                    )
                                            ).send(player, false);

                                            ServerPlayerEntity tp =
                                                    player.getEntityWorld().getServer().getPlayerManager().getPlayer(targetUser.getID());
                                            if (tp != null) {
                                                new Message(
                                                        Text.translatable(
                                                                "factions.gui.members.entry.manage.kick.result.subject",
                                                                player.getName().getString()
                                                        )
                                                ).send(tp, false);
                                            }
                                            this.open();
                                        })
                        );
                        gui.setSlot(3,
                                new GuiElementBuilder(Items.STRUCTURE_VOID)
                                        .setName(Text.translatable("factions.gui.members.entry.manage.kick.confirm.no")
                                                .formatted(Formatting.RED))
                                        .setCallback(() -> {
                                            GuiInteract.playClickSound(player);
                                            this.open();
                                        })
                        );
                        gui.open();
                    }

                    // Refresh lore after changes
                    lore.clear();
                    Formatting newRankColor = switch (targetUser.rank) {
                        case OWNER -> Formatting.DARK_RED;
                        case LEADER -> Formatting.RED;
                        case COMMANDER -> Formatting.GOLD;
                        case MEMBER -> Formatting.GREEN;
                        case GUEST -> Formatting.GRAY;
                    };
                    lore.add(
                            Text.translatable(
                                    "factions.gui.members.entry.info.rank",
                                    Text.translatable("factions.gui.members.entry.info.rank." + targetUser.getRankName())
                                            .setStyle(Style.EMPTY.withItalic(false).withColor(newRankColor))
                            ).setStyle(Style.EMPTY.withItalic(false).withColor(Formatting.GRAY))
                    );
                    ServerPlayerEntity updated =
                            player.getEntityWorld().getServer().getPlayerManager().getPlayer(targetUser.getID());
                    if (updated != null) {
                        lore.add(Text.literal("Status: ").formatted(Formatting.GRAY)
                                .append(Text.literal("Online").formatted(Formatting.GREEN)));
                    } else {
                        lore.add(Text.literal("Status: ").formatted(Formatting.GRAY)
                                .append(Text.literal("Offline").formatted(Formatting.RED)));
                    }
                    lore.add(Text.empty());
                    lore.add(Text.translatable("factions.gui.members.entry.manage.promote")
                            .setStyle(Style.EMPTY.withItalic(false).withColor(Formatting.DARK_GREEN)));
                    lore.add(Text.translatable("factions.gui.members.entry.manage.demote")
                            .setStyle(Style.EMPTY.withItalic(false).withColor(Formatting.DARK_RED)));
                    lore.add(Text.translatable("factions.gui.members.entry.manage.kick")
                            .setStyle(Style.EMPTY.withItalic(false).withColor(Formatting.DARK_RED)));
                    icon.setLore(lore);
                });
            }

            icon.setLore(lore);
            return DisplayElement.of(icon);
        }
        return DisplayElement.empty();
    }
}