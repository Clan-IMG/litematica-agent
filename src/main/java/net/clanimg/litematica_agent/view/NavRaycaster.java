package net.clanimg.litematica_agent.view;

import net.clanimg.litematica_agent.movement.pathing.NavWorld;

import java.util.Arrays;

/**
 * Draws a small grey picture of the world as the pathfinder sees it: every block reduced to its navigation flags,
 * cast from the player's eye. Blocked blocks are grey cubes with dark edges, low blocks such as slabs are flat boxes,
 * dangerous blocks are dark, water darkens the view. Paths and blocks can be drawn on top in white, hidden behind
 * walls like everything else.
 */
public final class NavRaycaster {
    public static final int BACKGROUND = argb(26);
    public static final int WHITE = 0xFFFFFFFF;
    private static final double MAX_DISTANCE = 24.0;
    private static final int RADIUS = 25;
    private static final int SIZE = RADIUS * 2 + 1;
    private static final double EDGE = 0.05;

    /** Eye position and view direction; yaw and pitch in degrees like Minecraft's. */
    public record Camera(double x, double y, double z, float yaw, float pitch, double fovDegrees) {
    }

    private final int width;
    private final int height;
    private final int[] pixels;
    private final double[] depth;
    /** Navigation flags around the camera, filled when a ray first reaches a block. */
    private final int[] flagCache = new int[SIZE * SIZE * SIZE];
    private final int[] flagStamp = new int[SIZE * SIZE * SIZE];
    private int stamp;
    private int originX;
    private int originY;
    private int originZ;
    private NavWorld world;

    private double eyeX;
    private double eyeY;
    private double eyeZ;
    private final double[] forward = new double[3];
    private final double[] right = new double[3];
    private final double[] up = new double[3];
    private double tanHalf;
    private double aspect;
    /** Face axis of the last hit found by {@link #intersectLow}. */
    private int lowHitAxis;

    public NavRaycaster(int width, int height) {
        this.width = width;
        this.height = height;
        this.pixels = new int[width * height];
        this.depth = new double[width * height];
    }

    public int[] pixels() {
        return this.pixels;
    }

    public void render(NavWorld world, Camera camera) {
        this.world = world;
        this.stamp++;
        this.eyeX = camera.x();
        this.eyeY = camera.y();
        this.eyeZ = camera.z();
        this.originX = (int) Math.floor(camera.x()) - RADIUS;
        this.originY = (int) Math.floor(camera.y()) - RADIUS;
        this.originZ = (int) Math.floor(camera.z()) - RADIUS;
        this.setUpCamera(camera);
        Arrays.fill(this.depth, Double.MAX_VALUE);

        for (int py = 0; py < this.height; py++) {
            double v = (1.0 - 2.0 * (py + 0.5) / this.height) * this.tanHalf;
            for (int px = 0; px < this.width; px++) {
                double u = (2.0 * (px + 0.5) / this.width - 1.0) * this.tanHalf * this.aspect;
                double dx = this.forward[0] + this.right[0] * u + this.up[0] * v;
                double dy = this.forward[1] + this.right[1] * u + this.up[1] * v;
                double dz = this.forward[2] + this.right[2] * u + this.up[2] * v;
                double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
                int index = py * this.width + px;
                this.pixels[index] = this.cast(dx / length, dy / length, dz / length, index);
            }
        }
    }

    /** Minecraft's view direction: yaw 0 looks towards +z, positive pitch looks down. */
    private void setUpCamera(Camera camera) {
        double yaw = Math.toRadians(camera.yaw());
        double pitch = Math.toRadians(camera.pitch());
        this.forward[0] = -Math.sin(yaw) * Math.cos(pitch);
        this.forward[1] = -Math.sin(pitch);
        this.forward[2] = Math.cos(yaw) * Math.cos(pitch);
        // right = forward x worldUp, up = right x forward
        double rx = -this.forward[2];
        double rz = this.forward[0];
        double rightLength = Math.sqrt(rx * rx + rz * rz);
        if (rightLength < 1.0E-6) {
            // Looking straight up or down: take the right vector from the yaw alone.
            rx = -Math.cos(yaw);
            rz = -Math.sin(yaw);
            rightLength = 1.0;
        }
        this.right[0] = rx / rightLength;
        this.right[1] = 0.0;
        this.right[2] = rz / rightLength;
        this.up[0] = this.right[1] * this.forward[2] - this.right[2] * this.forward[1];
        this.up[1] = this.right[2] * this.forward[0] - this.right[0] * this.forward[2];
        this.up[2] = this.right[0] * this.forward[1] - this.right[1] * this.forward[0];
        this.tanHalf = Math.tan(Math.toRadians(camera.fovDegrees()) / 2.0);
        this.aspect = (double) this.width / this.height;
    }

    /** Walks the ray block by block (Amanatides-Woo) until it meets something the pathfinder cannot walk through. */
    private int cast(double dx, double dy, double dz, int index) {
        int cx = (int) Math.floor(this.eyeX);
        int cy = (int) Math.floor(this.eyeY);
        int cz = (int) Math.floor(this.eyeZ);
        int stepX = dx > 0 ? 1 : -1;
        int stepY = dy > 0 ? 1 : -1;
        int stepZ = dz > 0 ? 1 : -1;
        double deltaX = dx == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dx);
        double deltaY = dy == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dy);
        double deltaZ = dz == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dz);
        double maxX = dx == 0 ? Double.MAX_VALUE : (dx > 0 ? cx + 1 - this.eyeX : this.eyeX - cx) * deltaX;
        double maxY = dy == 0 ? Double.MAX_VALUE : (dy > 0 ? cy + 1 - this.eyeY : this.eyeY - cy) * deltaY;
        double maxZ = dz == 0 ? Double.MAX_VALUE : (dz > 0 ? cz + 1 - this.eyeZ : this.eyeZ - cz) * deltaZ;
        double t = 0.0;
        int axis = -1;
        int water = 0;

        while (t <= MAX_DISTANCE) {
            int flags = this.flags(cx, cy, cz);
            double next = Math.min(maxX, Math.min(maxY, maxZ));
            if ((flags & NavWorld.UNLOADED) != 0) {
                break;
            }
            if ((flags & NavWorld.PASSABLE) == 0 || ((flags & NavWorld.DANGER) != 0 && axis >= 0)) {
                if (axis >= 0) {
                    this.depth[index] = t;
                    int base = (flags & NavWorld.DANGER) != 0 ? 45 : faceGrey(axis, dy);
                    return this.finish(base, t, water, cx, cy, cz, dx, dy, dz, axis, 1.0);
                }
            } else if ((flags & NavWorld.SOLID_TOP) != 0) {
                // A low block: only its lower part is solid.
                double height = (flags & NavWorld.RAISED) != 0 ? 0.5 : 0.12;
                double hit = this.intersectLow(cx, cy, cz, cy + height, dx, dy, dz, t, next);
                if (hit >= 0) {
                    this.depth[index] = hit;
                    int hitAxis = this.lowHitAxis;
                    int base = hitAxis == 1 && dy < 0 ? 250 : 205;
                    return this.finish(base, hit, water, cx, cy, cz, dx, dy, dz, hitAxis, height);
                }
            }
            if ((flags & NavWorld.WATER) != 0) {
                water++;
            }
            if (maxX <= maxY && maxX <= maxZ) {
                t = maxX;
                maxX += deltaX;
                cx += stepX;
                axis = 0;
            } else if (maxY <= maxZ) {
                t = maxY;
                maxY += deltaY;
                cy += stepY;
                axis = 1;
            } else {
                t = maxZ;
                maxZ += deltaZ;
                cz += stepZ;
                axis = 2;
            }
        }
        return waterTint(BACKGROUND, water);
    }

    /** Ray against the box from the block's bottom up to {@code top}; the hit distance, or -1. */
    private double intersectLow(int cx, int cy, int cz, double top, double dx, double dy, double dz, double from, double to) {
        double[] min = {cx, cy, cz};
        double[] max = {cx + 1.0, top, cz + 1.0};
        double[] origin = {this.eyeX, this.eyeY, this.eyeZ};
        double[] dir = {dx, dy, dz};
        double enter = from;
        double exit = to;
        int enterAxis = -1;
        for (int i = 0; i < 3; i++) {
            if (Math.abs(dir[i]) < 1.0E-9) {
                if (origin[i] < min[i] || origin[i] > max[i]) {
                    return -1;
                }
                continue;
            }
            double t0 = (min[i] - origin[i]) / dir[i];
            double t1 = (max[i] - origin[i]) / dir[i];
            if (t0 > t1) {
                double swap = t0;
                t0 = t1;
                t1 = swap;
            }
            if (t0 > enter) {
                enter = t0;
                enterAxis = i;
            }
            exit = Math.min(exit, t1);
        }
        if (enter > exit) {
            return -1;
        }
        this.lowHitAxis = enterAxis < 0 ? 1 : enterAxis;
        return enter;
    }

    /**
     * @param height height of the block's solid part (1 for full blocks, less for low blocks)
     */
    private int finish(int base, double t, int water, int cx, int cy, int cz, double dx, double dy, double dz, int axis,
                       double height) {
        double hx = this.eyeX + dx * t - cx;
        double hy = this.eyeY + dy * t - cy;
        double hz = this.eyeZ + dz * t - cz;
        // Dark block edges, so single blocks and their heights can be told apart.
        boolean edge = switch (axis) {
            case 0 -> nearEdge(hy, height) || nearEdge(hz, 1.0);
            case 1 -> nearEdge(hx, 1.0) || nearEdge(hz, 1.0);
            default -> nearEdge(hx, 1.0) || nearEdge(hy, height);
        };
        double grey = edge ? base * 0.6 : base;
        double fog = Math.pow(Math.min(1.0, t / MAX_DISTANCE), 1.5) * 0.85;
        grey = grey + (26 - grey) * fog;
        return waterTint(argb((int) Math.round(grey)), water);
    }

    /** Close to 0 or to {@code size} inside the block. */
    private static boolean nearEdge(double coordinate, double size) {
        return coordinate < EDGE || coordinate > size - EDGE;
    }

    private static int faceGrey(int axis, double dy) {
        return switch (axis) {
            case 1 -> dy < 0 ? 225 : 110;
            case 0 -> 175;
            default -> 150;
        };
    }

    private static int waterTint(int color, int water) {
        if (water == 0) {
            return color;
        }
        double factor = 1.0 - Math.min(0.35, water * 0.05);
        int grey = color & 0xFF;
        return argb((int) Math.round(grey * factor));
    }

    private int flags(int x, int y, int z) {
        int lx = x - this.originX;
        int ly = y - this.originY;
        int lz = z - this.originZ;
        if (lx < 0 || ly < 0 || lz < 0 || lx >= SIZE || ly >= SIZE || lz >= SIZE) {
            return NavWorld.UNLOADED;
        }
        int index = (lx * SIZE + ly) * SIZE + lz;
        if (this.flagStamp[index] != this.stamp) {
            this.flagStamp[index] = this.stamp;
            this.flagCache[index] = this.world.flags(x, y, z);
        }
        return this.flagCache[index];
    }

    // ---------------------------------------------------------------- overlays

    /**
     * Screen position of a point as {x, y, distance}, or null behind the camera.
     */
    public double[] project(double x, double y, double z) {
        double dx = x - this.eyeX;
        double dy = y - this.eyeY;
        double dz = z - this.eyeZ;
        double ahead = dx * this.forward[0] + dy * this.forward[1] + dz * this.forward[2];
        if (ahead < 0.05) {
            return null;
        }
        double side = dx * this.right[0] + dy * this.right[1] + dz * this.right[2];
        double lift = dx * this.up[0] + dy * this.up[1] + dz * this.up[2];
        double sx = (side / (ahead * this.tanHalf * this.aspect) + 1.0) * 0.5 * this.width;
        double sy = (1.0 - lift / (ahead * this.tanHalf)) * 0.5 * this.height;
        return new double[]{sx, sy, Math.sqrt(dx * dx + dy * dy + dz * dz)};
    }

    /** A line in the world, hidden where the picture shows something closer. */
    public void drawLine(double x1, double y1, double z1, double x2, double y2, double z2, int color) {
        double[] a = this.project(x1, y1, z1);
        double[] b = this.project(x2, y2, z2);
        if (a == null || b == null) {
            return;
        }
        int steps = (int) Math.ceil(Math.max(Math.abs(b[0] - a[0]), Math.abs(b[1] - a[1])));
        if (steps > this.width * 4) {
            return;
        }
        for (int i = 0; i <= steps; i++) {
            double f = steps == 0 ? 0.0 : (double) i / steps;
            this.plot(a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f, a[2] + (b[2] - a[2]) * f, color);
        }
    }

    /** A small square marking a point in the world. */
    public void drawPoint(double x, double y, double z, int color) {
        double[] p = this.project(x, y, z);
        if (p == null) {
            return;
        }
        for (int ox = -1; ox <= 1; ox++) {
            for (int oy = -1; oy <= 1; oy++) {
                this.plot(p[0] + ox, p[1] + oy, p[2], color);
            }
        }
    }

    /** The twelve edges of a block. */
    public void drawBlock(int x, int y, int z, int color) {
        double[][] corners = new double[8][];
        for (int i = 0; i < 8; i++) {
            corners[i] = new double[]{x + (i & 1), y + ((i >> 1) & 1), z + ((i >> 2) & 1)};
        }
        int[][] edges = {{0, 1}, {2, 3}, {4, 5}, {6, 7}, {0, 2}, {1, 3}, {4, 6}, {5, 7}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
        for (int[] edge : edges) {
            double[] a = corners[edge[0]];
            double[] b = corners[edge[1]];
            this.drawLine(a[0], a[1], a[2], b[0], b[1], b[2], color);
        }
    }

    private void plot(double sx, double sy, double distance, int color) {
        int px = (int) Math.floor(sx);
        int py = (int) Math.floor(sy);
        if (px < 0 || py < 0 || px >= this.width || py >= this.height) {
            return;
        }
        int index = py * this.width + px;
        // A little tolerance, so lines on the surface of a block stay visible.
        if (distance <= this.depth[index] + 0.35) {
            this.pixels[index] = color;
        }
    }

    private static int argb(int grey) {
        int value = Math.max(0, Math.min(255, grey));
        return 0xFF000000 | value << 16 | value << 8 | value;
    }
}
