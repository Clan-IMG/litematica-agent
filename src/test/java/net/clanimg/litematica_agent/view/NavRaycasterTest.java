package net.clanimg.litematica_agent.view;

import net.clanimg.litematica_agent.movement.pathing.NavWorld;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NavRaycasterTest {
    private static final int WIDTH = 32;
    private static final int HEIGHT = 18;
    /** Flat ground: blocks up to y 63, the eye stands at 64 + 1.62. */
    private static final NavWorld FLAT = (x, y, z) -> y <= 63 ? NavWorld.SOLID_TOP : NavWorld.PASSABLE;

    private static int centre(NavRaycaster raycaster) {
        return raycaster.pixels()[(HEIGHT / 2) * WIDTH + WIDTH / 2];
    }

    @Test
    void groundBelowIsDrawnAndTheSkyIsNot() {
        NavRaycaster raycaster = new NavRaycaster(WIDTH, HEIGHT);
        raycaster.render(FLAT, new NavRaycaster.Camera(0.5, 65.62, 0.5, 0.0F, 45.0F, 70.0));
        assertNotEquals(NavRaycaster.BACKGROUND, centre(raycaster));

        raycaster.render(FLAT, new NavRaycaster.Camera(0.5, 65.62, 0.5, 0.0F, -45.0F, 70.0));
        assertEquals(NavRaycaster.BACKGROUND, centre(raycaster));
    }

    @Test
    void slabIsDrawnAsLowBox() {
        // A slab two blocks ahead (towards +z): looking at its height from the side, the ray just above it passes.
        NavWorld world = (x, y, z) -> {
            if (y <= 63) {
                return NavWorld.SOLID_TOP;
            }
            if (x == 0 && y == 64 && z == 3) {
                return NavWorld.PASSABLE | NavWorld.SOLID_TOP | NavWorld.RAISED;
            }
            return NavWorld.PASSABLE;
        };
        // Looking slightly up, so the ground far away is not hit either.
        NavRaycaster raycaster = new NavRaycaster(WIDTH, HEIGHT);
        raycaster.render(world, new NavRaycaster.Camera(0.5, 64.25, 0.5, 0.0F, -5.0F, 70.0));
        assertNotEquals(NavRaycaster.BACKGROUND, centre(raycaster), "the slab's side at eye height 0.25");

        raycaster.render(world, new NavRaycaster.Camera(0.5, 64.75, 0.5, 0.0F, -5.0F, 70.0));
        assertEquals(NavRaycaster.BACKGROUND, centre(raycaster), "above the slab nothing is in the way");
    }

    @Test
    void pointStraightAheadProjectsToTheCentre() {
        NavRaycaster raycaster = new NavRaycaster(WIDTH, HEIGHT);
        raycaster.render(FLAT, new NavRaycaster.Camera(0.5, 65.62, 0.5, 90.0F, 0.0F, 70.0));
        // Yaw 90 looks towards -x.
        double[] point = raycaster.project(-4.5, 65.62, 0.5);
        assertNotNull(point);
        assertEquals(WIDTH / 2.0, point[0], 1.0E-6);
        assertEquals(HEIGHT / 2.0, point[1], 1.0E-6);
        assertEquals(5.0, point[2], 1.0E-6);
        assertTrue(raycaster.project(5.5, 65.62, 0.5) == null, "behind the camera");
    }
}
