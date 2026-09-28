package net.clanimg.litematica_agent.agent.task;

import net.clanimg.litematica_agent.agent.BuildAgent;
import net.clanimg.litematica_agent.inventory.InventoryHelper;
import net.clanimg.litematica_agent.ui.Chat;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;

/**
 * Eats food from the inventory by holding right click with the food selected.
 */
public final class EatTask implements AgentTask {
    private static final int EAT_TIMEOUT = 80;

    private int timer;
    private int tries;
    private int foodBefore = -1;
    private boolean started;

    @Override
    public Result tick(BuildAgent agent) {
        ClientPlayerEntity player = agent.player();
        agent.setHeldSneak(false);
        this.timer++;
        int food = player.getHungerManager().getFoodLevel();
        if (this.foodBefore < 0) {
            this.foodBefore = food;
        }
        if (food > this.foodBefore && !player.isUsingItem()) {
            return Result.SUCCESS;
        }
        if (!this.started) {
            Integer slot = InventoryHelper.findFood(player.getInventory());
            if (slot == null) {
                return Result.FAILED;
            }
            InventoryHelper.selectSlot(agent.client(), slot);
            if (this.timer < 3) {
                return Result.RUNNING;
            }
            agent.interactionManager().interactItem(player, Hand.MAIN_HAND);
            this.started = true;
            this.timer = 0;
            return Result.RUNNING;
        }
        if (!player.isUsingItem() || this.timer > EAT_TIMEOUT) {
            if (food > this.foodBefore) {
                return Result.SUCCESS;
            }
            this.tries++;
            this.started = false;
            this.timer = 0;
            return this.tries > 2 ? Result.FAILED : Result.RUNNING;
        }
        return Result.RUNNING;
    }

    @Override
    public void cancel(BuildAgent agent) {
        if (agent.player().isUsingItem()) {
            agent.interactionManager().stopUsingItem(agent.player());
        }
    }

    @Override
    public Text describe() {
        return Chat.tr("action.eat");
    }
}
