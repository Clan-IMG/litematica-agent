package net.clanimg.litematica_agent.movement;

import net.minecraft.util.PlayerInput;
import org.jetbrains.annotations.Nullable;

/**
 * The virtual keyboard of the agent. While {@link #take()} is active, the player's movement input is replaced every
 * tick by the keys pressed here.
 */
public final class InputController {
    private static boolean active;
    private static boolean forward;
    private static boolean back;
    private static boolean left;
    private static boolean right;
    private static boolean jump;
    private static boolean sneak;
    private static boolean sprint;

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

    public static @Nullable PlayerInput getOverride() {
        if (!active) {
            return null;
        }
        return new PlayerInput(forward, back, left, right, jump, sneak, sprint);
    }
}
