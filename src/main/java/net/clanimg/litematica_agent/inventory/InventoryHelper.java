package net.clanimg.litematica_agent.inventory;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.function.Predicate;

/**
 * Inventory operations performed the same way a player would: hotbar selection and number-key swaps.
 */
public final class InventoryHelper {
    public static final int HOTBAR_SIZE = PlayerInventory.HOTBAR_SIZE;
    public static final int MAIN_SIZE = PlayerInventory.MAIN_SIZE;
    private static final int HOTBAR_SLOT_OFFSET = 36;

    private static final Set<Item> UNSAFE_FOOD = Set.of(
            Items.ROTTEN_FLESH, Items.SPIDER_EYE, Items.POISONOUS_POTATO, Items.PUFFERFISH,
            Items.CHORUS_FRUIT, Items.SUSPICIOUS_STEW, Items.CHICKEN
    );

    private InventoryHelper() {
    }

    public static boolean isTool(ItemStack stack) {
        return !stack.isEmpty() && (stack.contains(DataComponentTypes.TOOL) || stack.isOf(Items.SHEARS));
    }

    /** Tools the agent may need from the storage: mining tools, hoes, shovels, shears and flint and steel. */
    public static boolean isUtility(ItemStack stack) {
        return isTool(stack) || stack.isOf(Items.FLINT_AND_STEEL) || stack.isOf(Items.FIRE_CHARGE);
    }

    public static boolean isFood(ItemStack stack) {
        return !stack.isEmpty() && stack.contains(DataComponentTypes.FOOD) && !UNSAFE_FOOD.contains(stack.getItem());
    }

    /** Tools and food may stay in the inventory when a survival session starts. */
    public static boolean isAllowedAtStart(ItemStack stack) {
        return stack.isEmpty() || isTool(stack) || isFood(stack) || stack.isDamageable();
    }

    public static int remainingDurability(ItemStack stack) {
        return stack.isDamageable() ? stack.getMaxDamage() - stack.getDamage() : Integer.MAX_VALUE;
    }

    public static int count(PlayerInventory inventory, Item item) {
        int total = 0;
        for (int i = 0; i < MAIN_SIZE; i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack.isOf(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    public static int find(PlayerInventory inventory, Predicate<ItemStack> predicate) {
        for (int i = 0; i < MAIN_SIZE; i++) {
            if (predicate.test(inventory.getStack(i))) {
                return i;
            }
        }
        return -1;
    }

    public static int findItem(PlayerInventory inventory, Item item) {
        int selected = inventory.getSelectedSlot();
        if (inventory.getStack(selected).isOf(item)) {
            return selected;
        }
        return find(inventory, stack -> stack.isOf(item));
    }

    public static int freeSlots(PlayerInventory inventory) {
        int free = 0;
        for (int i = 0; i < MAIN_SIZE; i++) {
            if (inventory.getStack(i).isEmpty()) {
                free++;
            }
        }
        return free;
    }

    /**
     * Puts the item into the main hand. In creative mode it is taken from the creative inventory.
     *
     * @return true if the item is in the main hand now
     */
    public static boolean select(MinecraftClient client, Item item) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.interactionManager == null) {
            return false;
        }
        PlayerInventory inventory = player.getInventory();
        if (player.getMainHandStack().isOf(item)) {
            return true;
        }

        int slot = findItem(inventory, item);
        if (slot >= 0 && slot < HOTBAR_SIZE) {
            inventory.setSelectedSlot(slot);
            return true;
        }

        int hotbarSlot = chooseHotbarSlot(inventory);
        if (player.isInCreativeMode()) {
            ItemStack stack = new ItemStack(item, item.getMaxCount());
            inventory.setSelectedSlot(hotbarSlot);
            inventory.setStack(hotbarSlot, stack);
            // A copy: in singleplayer the server would otherwise share the client's stack object.
            client.interactionManager.clickCreativeStack(stack.copy(), HOTBAR_SLOT_OFFSET + hotbarSlot);
            return true;
        }

        if (slot < 0) {
            return false;
        }
        client.interactionManager.clickSlot(player.playerScreenHandler.syncId, slot, hotbarSlot, SlotActionType.SWAP, player);
        inventory.setSelectedSlot(hotbarSlot);
        return player.getMainHandStack().isOf(item);
    }

    /**
     * Selects a slot whose item does not react when right-clicking blocks (empty hand preferred).
     */
    public static void selectNeutralSlot(ClientPlayerEntity player) {
        PlayerInventory inventory = player.getInventory();
        for (int i = 0; i < HOTBAR_SIZE; i++) {
            if (inventory.getStack(i).isEmpty()) {
                inventory.setSelectedSlot(i);
                return;
            }
        }
        for (int i = 0; i < HOTBAR_SIZE; i++) {
            if (isTool(inventory.getStack(i))) {
                inventory.setSelectedSlot(i);
                return;
            }
        }
    }

    /**
     * Best hotbar-reachable tool for the block that still has more durability than {@code reserve}.
     *
     * @return inventory slot, or -1 when bare hands are best
     */
    public static int findBestTool(PlayerInventory inventory, BlockState state, int reserve) {
        int best = -1;
        float bestSpeed = 1.0F;
        for (int i = 0; i < MAIN_SIZE; i++) {
            ItemStack stack = inventory.getStack(i);
            if (!isTool(stack) || remainingDurability(stack) <= reserve) {
                continue;
            }
            float speed = stack.getMiningSpeedMultiplier(state);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                best = i;
            }
        }
        return best;
    }

    /**
     * Moves the item in {@code slot} to the hotbar (if needed) and selects it. Selects bare hands for {@code -1}.
     */
    public static void selectSlot(MinecraftClient client, int slot) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.interactionManager == null) {
            return;
        }
        PlayerInventory inventory = player.getInventory();
        if (slot < 0) {
            for (int i = 0; i < HOTBAR_SIZE; i++) {
                ItemStack stack = inventory.getStack(i);
                if (stack.isEmpty() || !stack.isDamageable()) {
                    inventory.setSelectedSlot(i);
                    return;
                }
            }
            return;
        }
        if (slot < HOTBAR_SIZE) {
            inventory.setSelectedSlot(slot);
            return;
        }
        int hotbarSlot = chooseHotbarSlot(inventory);
        client.interactionManager.clickSlot(player.playerScreenHandler.syncId, slot, hotbarSlot, SlotActionType.SWAP, player);
        inventory.setSelectedSlot(hotbarSlot);
    }

    public static @Nullable Integer findFood(PlayerInventory inventory) {
        int slot = find(inventory, InventoryHelper::isFood);
        return slot < 0 ? null : slot;
    }

    /**
     * Hotbar slot to swap building materials into: an empty slot, otherwise one that holds neither tools nor food.
     */
    private static int chooseHotbarSlot(PlayerInventory inventory) {
        for (int i = 0; i < HOTBAR_SIZE; i++) {
            if (inventory.getStack(i).isEmpty()) {
                return i;
            }
        }
        int selected = inventory.getSelectedSlot();
        for (int offset = 1; offset <= HOTBAR_SIZE; offset++) {
            int i = (selected + offset) % HOTBAR_SIZE;
            ItemStack stack = inventory.getStack(i);
            if (!isTool(stack) && !isFood(stack)) {
                return i;
            }
        }
        return selected;
    }
}
