package io.icker.factions.ui;

import eu.pb4.sgui.api.ClickType;
import eu.pb4.sgui.api.elements.GuiElementBuilder;

import io.icker.factions.api.persistents.Faction;
import io.icker.factions.api.persistents.Home;
import io.icker.factions.api.persistents.User;
import io.icker.factions.command.HomeCommand;
import io.icker.factions.util.GuiInteract;
import io.icker.factions.util.Icons;

import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class ListGui extends PagedGui {
    List<Faction> factions;
    int size;
    User user;

    // Comparator for sorting factions by member count (descending) then by name
    private static final Comparator<Faction> FACTION_COMPARATOR =
            Comparator.comparingInt((Faction f) -> f.getUsers().size()).reversed()
                    .thenComparing(f -> f.getName().toLowerCase());

    public ListGui(ServerPlayerEntity player, User user, @Nullable Runnable closeCallback) {
        super(player, closeCallback);
        this.user = user;

        // Get all factions and sort them
        this.factions = new ArrayList<>(Faction.all().stream().toList());

        // Remove user's faction temporarily if they have one
        Faction userFaction = user.getFaction();
        if (userFaction != null) {
            this.factions.remove(userFaction);
        }

        // Sort remaining factions by member count (descending) then alphabetically
        this.factions.sort(FACTION_COMPARATOR);

        // Add user's faction back at the beginning if they have one
        if (userFaction != null) {
            this.factions.addFirst(userFaction);
        }

        this.size = factions.size();

        this.setTitle(Text.translatable("factions.gui.list.title"));
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
            var faction = this.factions.get(id);

            boolean isInFaction = faction.equals(this.user.getFaction());
            Home home = faction.getHome();
            int memberCount = faction.getUsers().size();
            int claimCount = faction.getClaims().size();
            int power = faction.getPower();
            int maxPower = faction.calculateMaxPower();

            var icon = new GuiElementBuilder(Items.PLAYER_HEAD);
            icon.setSkullOwner(isInFaction ? Icons.GUI_CASTLE_NORMAL : Icons.GUI_CASTLE_OPEN);

            // Add special formatting for user's faction
            if (isInFaction) {
                icon.setName(Text.literal(faction.getColor() + faction.getName())
                        .formatted(Formatting.BOLD));
            } else {
                icon.setName(Text.literal(faction.getColor() + faction.getName()));
            }

            List<Text> lore = new ArrayList<>();

            // Add description
            if (faction.getDescription() != null && !faction.getDescription().isEmpty()) {
                lore.add(Text.literal(faction.getDescription())
                        .setStyle(Style.EMPTY
                                .withItalic(true)
                                .withColor(Formatting.GRAY)));
                lore.add(Text.empty()); // Spacing
            }

            // Add faction stats
            lore.add(Text.literal("Members: ").formatted(Formatting.GRAY)
                    .append(Text.literal(String.valueOf(memberCount)).formatted(Formatting.WHITE)));

            lore.add(Text.literal("Claims: ").formatted(Formatting.GRAY)
                    .append(Text.literal(String.valueOf(claimCount)).formatted(Formatting.WHITE)));

            // Color code power based on percentage
            Formatting powerColor;
            float powerPercentage = maxPower > 0 ? (float) power / maxPower : 0;
            if (powerPercentage >= 0.75) {
                powerColor = Formatting.GREEN;
            } else if (powerPercentage >= 0.5) {
                powerColor = Formatting.YELLOW;
            } else if (powerPercentage >= 0.25) {
                powerColor = Formatting.GOLD;
            } else {
                powerColor = Formatting.RED;
            }

            lore.add(Text.literal("Power: ").formatted(Formatting.GRAY)
                    .append(Text.literal(power + "/" + maxPower).formatted(powerColor)));

            // Add interaction instructions
            lore.add(Text.empty()); // Spacing

            if (isInFaction) {
                // Show special indicator that this is player's faction
                lore.add(Text.literal("★ Your Faction ★")
                        .setStyle(Style.EMPTY
                                .withItalic(false)
                                .withColor(Formatting.AQUA)
                                .withBold(true)));
                lore.add(Text.empty()); // Spacing
            }

            if (isInFaction && home != null) {
                lore.add(
                        Text.translatable("factions.gui.list.entry.view_info")
                                .setStyle(
                                        Style.EMPTY.withItalic(false).withColor(Formatting.GRAY)));
                lore.add(
                        Text.translatable("factions.gui.list.entry.teleport")
                                .setStyle(
                                        Style.EMPTY
                                                .withItalic(false)
                                                .withColor(Formatting.DARK_AQUA)));
                icon.setCallback(
                        (index, clickType, actionType) -> {
                            GuiInteract.playClickSound(player);
                            if (clickType == ClickType.MOUSE_RIGHT) {
                                new HomeCommand().execGo(player, user, faction);
                                this.close();
                                return;
                            }
                            new InfoGui(player, faction, this::open);
                        });
            } else if (isInFaction) {
                // In faction but no home set
                lore.add(
                        Text.translatable("factions.gui.list.entry.view_info")
                                .setStyle(
                                        Style.EMPTY.withItalic(false).withColor(Formatting.GRAY)));
                lore.add(
                        Text.literal("No home set")
                                .setStyle(
                                        Style.EMPTY
                                                .withItalic(false)
                                                .withColor(Formatting.DARK_GRAY)));
                icon.setCallback(
                        (index, clickType, actionType) -> {
                            GuiInteract.playClickSound(player);
                            new InfoGui(player, faction, this::open);
                        });
            } else {
                lore.add(
                        Text.translatable("factions.gui.list.entry.view_info")
                                .setStyle(
                                        Style.EMPTY.withItalic(false).withColor(Formatting.GRAY)));
                icon.setCallback(
                        (index, clickType, actionType) -> {
                            GuiInteract.playClickSound(player);
                            new InfoGui(player, faction, this::open);
                        });
            }
            icon.setLore(lore);

            return DisplayElement.of(icon);
        }

        return DisplayElement.empty();
    }

    @Override
    protected DisplayElement getNavElement(int id) {
        // Override to add faction count and page info
        return switch (id) {
            case 1 -> DisplayElement.previousPage(this);
            case 3 -> DisplayElement.nextPage(this);
            case 4 -> // Faction count indicator
                    DisplayElement.of(
                            new GuiElementBuilder(Items.BOOK)
                                    .setName(
                                            Text.literal("Total Factions: " + this.size)
                                                    .formatted(Formatting.AQUA))
                                    .hideDefaultTooltip());
            case 5 -> // Page indicator
                    DisplayElement.of(
                            new GuiElementBuilder(Items.PAPER)
                                    .setName(
                                            Text.literal("Page " + (this.page + 1) + " / " + getPageAmount())
                                                    .formatted(Formatting.YELLOW))
                                    .hideDefaultTooltip());
            case 7 -> // close/back
                    DisplayElement.of(
                            new GuiElementBuilder(Items.STRUCTURE_VOID)
                                    .setName(
                                            Text.translatable(
                                                            this.closeCallback != null
                                                                    ? "factions.gui.generic.back"
                                                                    : "factions.gui.generic.close")
                                                    .formatted(Formatting.RED))
                                    .hideDefaultTooltip()
                                    .setCallback((i, click, action) -> {
                                        GuiInteract.playClickSound(this.player);
                                        this.close(this.closeCallback != null);
                                    }));
            default -> DisplayElement.filler();
        };
    }
}