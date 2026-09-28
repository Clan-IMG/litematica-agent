package net.clanimg.litematica_agent.inventory;

import net.clanimg.litematica_agent.agent.AgentManager;
import net.clanimg.litematica_agent.mixin.InteractionManagerInvoker;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;

/**
 * When the player starts mining a block (also the next block while the attack key stays held), the best tool from the
 * hotbar or inventory goes into the hand. Tools about to break are never picked and are put away if held.
 */
public final class AutoTool {
    private AutoTool() {
    }

    public static void onAttackBlock(MinecraftClient client, BlockPos pos) {
        ClientPlayerEntity player = client.player;
        AgentManager manager = AgentManager.get();
        if (player == null || client.world == null || client.interactionManager == null || !manager.config().autoTool
                || player.isCreative() || player.isSpectator() || manager.activeAgent() != null) {
            return;
        }
        BlockState state = client.world.getBlockState(pos);
        if (state.isAir()) {
            return;
        }
        PlayerInventory inventory = player.getInventory();
        int reserve = manager.config().toolDurabilityReserve;
        int best = bestTool(inventory, state, reserve);
        ItemStack held = player.getMainHandStack();
        boolean heldWornOut = InventoryHelper.isTool(held) && InventoryHelper.remainingDurability(held) <= reserve;

        int slot;
        if (best >= 0 && best != inventory.getSelectedSlot()
                && (heldWornOut || score(inventory.getStack(best), state) > score(held, state))) {
            slot = best;
        } else if (best < 0 && heldWornOut) {
            slot = -1;
        } else {
            return;
        }
        InventoryHelper.selectSlot(client, slot);
        ((InteractionManagerInvoker) client.interactionManager).litematicaAgent$syncSelectedSlot();
    }

    /**
     * Inventory slot of the best usable tool, or -1 if bare hands are as good. Tools that make the block drop its
     * item win over merely fast ones (a gold pickaxe is fast but cannot mine diamond ore).
     */
    private static int bestTool(PlayerInventory inventory, BlockState state, int reserve) {
        int best = -1;
        double bestScore = score(ItemStack.EMPTY, state);
        for (int i = 0; i < InventoryHelper.MAIN_SIZE; i++) {
            ItemStack stack = inventory.getStack(i);
            if (!InventoryHelper.isTool(stack) || InventoryHelper.remainingDurability(stack) <= reserve) {
                continue;
            }
            double score = score(stack, state);
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    private static double score(ItemStack stack, BlockState state) {
        boolean drops = !state.isToolRequired() || stack.isSuitableFor(state);
        return (drops ? 1000.0 : 0.0) + stack.getMiningSpeedMultiplier(state);
    }
}
