
package io.icker.factions.ui;

import eu.pb4.sgui.api.elements.GuiElement;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.elements.GuiElementBuilderInterface;
import eu.pb4.sgui.api.elements.GuiElementInterface;
import eu.pb4.sgui.api.gui.SimpleGui;
import eu.pb4.sgui.api.ClickType;

import io.icker.factions.util.Icons;

import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.Slot;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

@ApiStatus.Internal
public abstract class PagedGui extends SimpleGui {
    public static final int PAGE_SIZE = 9 * 4;
    protected final Runnable closeCallback;
    protected int page = 0;
    public boolean ignoreCloseCallback;

    public PagedGui(ServerPlayerEntity player, @Nullable Runnable closeCallback) {
        super(ScreenHandlerType.GENERIC_9X5, player, false);
        this.closeCallback = closeCallback;
    }

    public void refreshOpen() {
        this.updateDisplay();
        this.open();
    }

    @Override
    public void onClose() {
        if (this.closeCallback != null && !ignoreCloseCallback) {
            this.closeCallback.run();
        }
    }

    // Calculate the actual number of pages needed based on total elements
    protected int calculatePageCount() {
        int totalElements = getTotalElements();
        if (totalElements == 0) {
            return 1; // Always have at least 1 page
        }
        // Calculate pages needed: ceiling division
        return (totalElements + PAGE_SIZE - 1) / PAGE_SIZE;
    }

    // Get the total number of elements (to be implemented by subclasses)
    protected abstract int getTotalElements();

    // Fixed paging helper methods
    private int pageCount() {
        // Use the calculated page count instead of getPageAmount()
        return calculatePageCount();
    }

    private int lastPageIndex() {
        return Math.max(0, pageCount() - 1);
    }

    private void clampPage() {
        if (this.page < 0) this.page = 0;
        if (this.page > lastPageIndex()) this.page = lastPageIndex();
    }

    protected void nextPage() {
        int maxPage = lastPageIndex();
        if (this.page < maxPage) {
            this.page++;
            this.updateDisplay();
            playClickSound(this.player);
        }
    }

    protected boolean canNextPage() {
        return this.page < lastPageIndex();
    }

    protected void previousPage() {
        if (this.page > 0) {
            this.page--;
            this.updateDisplay();
            playClickSound(this.player);
        }
    }

    protected boolean canPreviousPage() {
        return this.page > 0;
    }

    protected void updateDisplay() {
        // Always clamp page to valid range first
        clampPage();

        // Clear all slots to prevent stale redirects
        for (int i = 0; i < this.getSize(); i++) {
            this.clearSlot(i);
        }

        int offset = this.page * PAGE_SIZE;
        int totalElements = getTotalElements();

        // Fill main content area (36 slots)
        for (int i = 0; i < PAGE_SIZE; i++) {
            int elementIndex = offset + i;

            // Check bounds - don't request elements beyond what exists
            var element = (elementIndex < totalElements) ? this.getElement(elementIndex) : null;

            if (element == null) {
                element = DisplayElement.empty();
            }

            if (element.element() != null) {
                this.setSlot(i, element.element());
            } else if (element.slot() != null) {
                this.setSlotRedirect(i, element.slot());
            }
        }

        // Fill navigation bar (bottom 9 slots) with page info
        for (int i = 0; i < 9; i++) {
            var navElement = this.getNavElement(i);

            if (navElement == null) {
                navElement = DisplayElement.EMPTY;
            }

            if (navElement.element != null) {
                this.setSlot(i + PAGE_SIZE, navElement.element);
            } else if (navElement.slot != null) {
                this.setSlotRedirect(i + PAGE_SIZE, navElement.slot);
            }
        }
    }

    protected int getPage() {
        return this.page;
    }

    // This method is now deprecated - use getTotalElements() instead
    protected int getPageAmount() {
        // Default implementation for backwards compatibility
        return calculatePageCount();
    }

    protected abstract DisplayElement getElement(int id);

    protected DisplayElement getNavElement(int id) {
        return switch (id) {
            case 1 -> DisplayElement.previousPage(this); // prev
            case 3 -> DisplayElement.nextPage(this);     // next
            case 5 -> // Page indicator
                    DisplayElement.of(
                            new GuiElementBuilder(Items.PAPER)
                                    .setName(
                                            Text.literal("Page " + (this.page + 1) + " / " + pageCount())
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
                                    .setCallback(this::handleCloseClick));
            default -> DisplayElement.filler();
        };
    }

    private void handleCloseClick(int index,
                                  eu.pb4.sgui.api.ClickType clickType,
                                  net.minecraft.screen.slot.SlotActionType actionType) {
        playClickSound(this.player);
        this.close(this.closeCallback != null);
    }

    public record DisplayElement(@Nullable GuiElementInterface element, @Nullable Slot slot) {
        private static final DisplayElement EMPTY =
                DisplayElement.of(
                        new GuiElement(ItemStack.EMPTY, GuiElementInterface.EMPTY_CALLBACK));
        private static final DisplayElement FILLER =
                DisplayElement.of(
                        new GuiElementBuilder(Items.WHITE_STAINED_GLASS_PANE)
                                .setName(Text.empty())
                                .hideTooltip());

        public static DisplayElement of(GuiElementInterface element) {
            return new DisplayElement(element, null);
        }

        public static DisplayElement of(GuiElementBuilderInterface<?> element) {
            return new DisplayElement(element.build(), null);
        }

        public static DisplayElement of(Slot slot) {
            return new DisplayElement(null, slot);
        }

        public static DisplayElement nextPage(PagedGui gui) {
            if (gui.canNextPage()) {
                return DisplayElement.of(
                        new GuiElementBuilder(Items.PLAYER_HEAD)
                                .setName(
                                        Text.translatable("factions.gui.generic.next_page")
                                                .formatted(Formatting.WHITE))
                                .hideDefaultTooltip()
                                .setSkullOwner(Icons.GUI_NEXT_PAGE)
                                .setCallback((i, click, action) -> gui.nextPage()));
            } else {
                return DisplayElement.of(
                        new GuiElementBuilder(Items.PLAYER_HEAD)
                                .setName(
                                        Text.translatable("factions.gui.generic.next_page")
                                                .formatted(Formatting.DARK_GRAY))
                                .hideDefaultTooltip()
                                .setSkullOwner(Icons.GUI_NEXT_PAGE_BLOCKED));
            }
        }

        public static DisplayElement previousPage(PagedGui gui) {
            if (gui.canPreviousPage()) {
                return DisplayElement.of(
                        new GuiElementBuilder(Items.PLAYER_HEAD)
                                .setName(
                                        Text.translatable("factions.gui.generic.previous_page")
                                                .formatted(Formatting.WHITE))
                                .hideDefaultTooltip()
                                .setSkullOwner(Icons.GUI_PREVIOUS_PAGE)
                                .setCallback((i, click, action) -> gui.previousPage()));
            } else {
                return DisplayElement.of(
                        new GuiElementBuilder(Items.PLAYER_HEAD)
                                .setName(
                                        Text.translatable("factions.gui.generic.previous_page")
                                                .formatted(Formatting.DARK_GRAY))
                                .hideDefaultTooltip()
                                .setSkullOwner(Icons.GUI_PREVIOUS_PAGE_BLOCKED));
            }
        }

        public static DisplayElement filler() {
            return FILLER;
        }

        public static DisplayElement empty() {
            return EMPTY;
        }
    }

    public static void playClickSound(ServerPlayerEntity player) {
        player.playSoundToPlayer(SoundEvents.UI_BUTTON_CLICK.value(), SoundCategory.MASTER, 1.0f, 1.0f);
    }
}