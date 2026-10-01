package net.clanimg.litematica_agent.gametest;

import net.clanimg.litematica_agent.agent.AgentManager;
import net.clanimg.litematica_agent.gui.AgentLockScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.input.MouseInput;
import net.minecraft.text.OrderedText;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/** Input and layout regression for instructions much longer than a screen; screenshots cover both ends. */
public class AgentLockScreenGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getClientWorld().waitForChunksRender();
            TestServerContext server = singleplayer.getServer();
            server.runCommand("gamemode creative @a");
            server.runCommand("tp @a 4 -60 4");
            server.runCommand("fill 6 -60 6 12 -58 12 stone");
            context.waitTicks(20);
            context.runOnClient(client -> GameTestSupport.createPlacement(client, "lock-screen-test",
                    new BlockPos(6, -60, 6), new BlockPos(12, -58, 12)));
            server.runCommand("fill 6 -60 6 12 -58 12 air");
            context.waitTicks(10);
            context.runOnClient(client -> AgentManager.get().startNew());
            GameTestSupport.awaitLoading(context);
            int sessionId = context.computeOnClient(client -> AgentManager.get().sessions().getFirst().id);
            context.runOnClient(client -> AgentManager.get().begin(sessionId));
            GameTestSupport.awaitLoading(context);
            context.runOnClient(client -> {
                AgentManager manager = AgentManager.get();
                manager.pauseActive();
                var session = manager.findSession(sessionId);
                StringBuilder reason = new StringBuilder("START OF LONG INSTRUCTION\n");
                for (int i = 1; i <= 200; i++) {
                    reason.append("Lagerhinweis ").append(i)
                            .append(": Gehe durch die Tuer zum Lager, pruefe den Weg und setze /sethome 3.\n");
                }
                reason.append("END OF LONG INSTRUCTION");
                session.pauseReason = reason.toString();
                session.pauseArgs = List.of();
                client.setScreen(new AgentLockScreen());
            });
            context.waitTicks(2);
            context.runOnClient(client -> {
                AgentLockScreen screen = (AgentLockScreen) client.currentScreen;
                require(number(screen, "contentHeight") > 4000, "Long instruction was truncated during layout");
                require(laidOutText(screen).contains("END OF LONG INSTRUCTION"), "Last instruction line is missing");
                require(number(screen, "scrollOffset") == 0, "New pause reason must start at the top");
                assertControlsVisible(screen);
            });
            context.takeScreenshot("lock-screen-01-long-top");
            context.runOnClient(client -> {
                AgentLockScreen screen = (AgentLockScreen) client.currentScreen;
                double x = number(screen, "panelX") + 30;
                double y = number(screen, "contentTop") + 20;
                require(screen.mouseScrolled(x, y, 0, -2), "Panel did not consume wheel input");
                require(number(screen, "scrollOffset") > 0, "Wheel did not move content");
                screen.keyPressed(key(GLFW.GLFW_KEY_PAGE_DOWN, 0));
                require(number(screen, "scrollOffset") > 60, "PageDown did not advance content");
                screen.keyPressed(key(GLFW.GLFW_KEY_END, GLFW.GLFW_MOD_CONTROL));
                assertAtBottom(screen);
            });
            context.waitTicks(2);
            context.takeScreenshot("lock-screen-02-long-bottom");
            context.runOnClient(client -> {
                AgentLockScreen screen = (AgentLockScreen) client.currentScreen;
                screen.keyPressed(key(GLFW.GLFW_KEY_HOME, GLFW.GLFW_MOD_CONTROL));
                require(number(screen, "scrollOffset") == 0, "Ctrl+Home did not return to the start");
                double x = number(screen, "panelX") + number(screen, "panelWidth") - 10;
                double top = number(screen, "contentTop") + 2;
                double bottom = number(screen, "contentBottom") + 100;
                require(screen.mouseClicked(click(x, top), false), "Scrollbar did not accept press");
                require(screen.mouseDragged(click(x, bottom), 0, bottom - top), "Scrollbar did not drag");
                require(screen.mouseReleased(click(x, bottom)), "Scrollbar did not release");
                assertAtBottom(screen);
                // Minecraft's smallest normal GUI viewport. Every control and the content viewport must fit.
                screen.resize(320, 240);
                screen.keyPressed(key(GLFW.GLFW_KEY_END, GLFW.GLFW_MOD_CONTROL));
                assertAtBottom(screen);
                assertControlsVisible(screen);
                require(number(screen, "contentBottom") > number(screen, "contentTop"), "Empty small viewport");
            });
            context.takeScreenshot("lock-screen-03-small-bottom");
            context.runOnClient(client -> {
                AgentLockScreen screen = (AgentLockScreen) client.currentScreen;
                screen.resize(client.getWindow().getScaledWidth(), client.getWindow().getScaledHeight());
                AgentManager.get().findSession(sessionId).pauseReason = "A new short instruction";
                screen.tick();
                require(number(screen, "scrollOffset") == 0, "Changed pause reason remained scrolled away");
                assertControlsVisible(screen);
                AgentManager.get().cancel(sessionId, true);
                client.setScreen(null);
            });
        }
    }

    private static KeyInput key(int key, int modifiers) {
        return new KeyInput(key, 0, modifiers);
    }

    private static Click click(double x, double y) {
        return new Click(x, y, new MouseInput(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0));
    }

    private static void assertAtBottom(AgentLockScreen screen) {
        double expected = Math.max(0, number(screen, "contentHeight")
                - number(screen, "contentBottom") + number(screen, "contentTop"));
        require(Math.abs(number(screen, "scrollOffset") - expected) < 0.001, "Bottom content is unreachable");
    }

    private static void assertControlsVisible(AgentLockScreen screen) {
        for (Object entry : (List<?>) field(screen, "panelButtons")) {
            ClickableWidget widget = (ClickableWidget) entry;
            require(widget.getX() >= 0 && widget.getRight() <= screen.width
                            && widget.getY() >= number(screen, "contentBottom") && widget.getBottom() <= screen.height,
                    "Control falls outside the screen or overlaps instructions: " + widget.getMessage().getString());
        }
    }

    private static String laidOutText(AgentLockScreen screen) {
        StringBuilder text = new StringBuilder();
        for (Object row : (List<?>) field(screen, "contentRows")) {
            if (!row.getClass().getSimpleName().equals("TextRow")) {
                continue;
            }
            try {
                Method accessor = row.getClass().getDeclaredMethod("text");
                accessor.setAccessible(true);
                ((OrderedText) accessor.invoke(row)).accept((index, style, codePoint) -> {
                    text.appendCodePoint(codePoint);
                    return true;
                });
                text.append('\n');
            } catch (ReflectiveOperationException exception) {
                throw new AssertionError(exception);
            }
        }
        return text.toString();
    }

    private static double number(AgentLockScreen screen, String name) {
        return ((Number) field(screen, name)).doubleValue();
    }

    private static Object field(AgentLockScreen screen, String name) {
        try {
            Field field = AgentLockScreen.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(screen);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
