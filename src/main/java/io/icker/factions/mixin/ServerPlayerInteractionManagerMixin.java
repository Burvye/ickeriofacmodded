package io.icker.factions.mixin;

import io.icker.factions.api.events.PlayerEvents;
import net.minecraft.block.BlockState;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.server.network.ServerPlayerInteractionManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayerInteractionManager.class)
public class ServerPlayerInteractionManagerMixin {

    @Shadow public ServerPlayerEntity player;

    // Keep the placement redirect as it was working
    @Redirect(
            method = "interactBlock",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/item/ItemStack;useOnBlock(Lnet/minecraft/item/ItemUsageContext;)Lnet/minecraft/util/ActionResult;"
            )
    )
    public ActionResult onPlace(ItemStack stack, ItemUsageContext ctx) {
        if (PlayerEvents.PLACE_BLOCK.invoker().onPlaceBlock(ctx) == ActionResult.FAIL) {
            return ActionResult.FAIL;
        }
        return stack.useOnBlock(ctx);
    }

    // Use @Inject instead of @Redirect for block breaking
    @Inject(method = "tryBreakBlock", at = @At("HEAD"), cancellable = true)
    public void onTryBreakBlock(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        // Get the blockstate before breaking
        BlockState state = this.player.getEntityWorld().getBlockState(pos);

        // Fire your event
        if (PlayerEvents.BREAK_BLOCK.invoker()
                .onBreakBlock(player, player.getEntityWorld(), pos, state)
                == ActionResult.FAIL) {
            // Cancel the block breaking by returning false
            cir.setReturnValue(false);
        }
    }
}