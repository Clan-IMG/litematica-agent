package net.clanimg.litematica_agent.movement;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.PlayerInput;
import org.jetbrains.annotations.Nullable;

/**
 * The virtual keyboard of the agent. While {@link #take()} is active, the player's movement input is replaced every
 * tick by the keys pressed here.
 */
public final class InputController {
    /** The vanilla client toggles flight on a second press of space within this many ticks of the first. */
    private static final int DOUBLE_TAP_WINDOW_TICKS = 7;

    private static boolean active;
    private static boolean forward;
    private static boolean back;
    private static boolean left;
    private static boolean right;
    private static boolean jump;
    private static boolean sneak;
    private static boolean sprint;
    /** Input ticks seen, the tick space was last pressed down, and whether it was down in the previous tick. */
    private static int inputTick;
    private static int lastJumpPress = -100;
    private static boolean jumpBefore;

    private InputController() {
    }

    public static void take() {
        active = true;
    }

    public static void release() {
        active = false;
        clear();
    }

    public static boolean isActive() {
        return active;
    }

    public static void clear() {
        forward = false;
        back = false;
        left = false;
        right = false;
        jump = false;
        sneak = false;
        sprint = false;
    }

    public static void setForward(boolean pressed) {
        forward = pressed;
    }

    public static void setBack(boolean pressed) {
        back = pressed;
    }

    public static void setLeft(boolean pressed) {
        left = pressed;
    }

    public static void setRight(boolean pressed) {
        right = pressed;
    }

    public static void setJump(boolean pressed) {
        jump = pressed;
    }

    public static void setSneak(boolean pressed) {
        sneak = pressed;
    }

    public static void setSprint(boolean pressed) {
        sprint = pressed;
    }

    public static boolean isSneakPressed() {
        return sneak;
    }

    /** The keys pressed right now, for log lines. */
    public static String describe() {
        StringBuilder keys = new StringBuilder();
        if (forward) keys.append('W');
        if (back) keys.append('S');
        if (left) keys.append('A');
        if (right) keys.append('D');
        if (jump) keys.append("␣");
        if (sneak) keys.append("⇧");
        if (sprint) keys.append("↯");
        return keys.length() == 0 ? "-" : keys.toString();
    }

    /** Called once per client tick by the keyboard input. */
    public static @Nullable PlayerInput getOverride() {
        if (!active) {
            return null;
        }
        inputTick++;
        boolean jumpNow = jump;
        if (jumpNow && !jumpBefore && isFlying() && inputTick - lastJumpPress <= DOUBLE_TAP_WINDOW_TICKS) {
            // Pressing space again this soon is the double tap that ends the flight - which the height control does
            // all the time when it presses and releases around its threshold. Held back a tick or two instead: a
            // moment less climb is nothing, a fall of a hundred blocks in survival is everything.
            jumpNow = false;
        }
        if (jumpNow && !jumpBefore) {
            lastJumpPress = inputTick;
        }
        jumpBefore = jumpNow;
        return new PlayerInput(forward, back, left, right, jumpNow, sneak, sprint);
    }

    private static boolean isFlying() {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        return player != null && player.getAbilities().flying;
    }
}
