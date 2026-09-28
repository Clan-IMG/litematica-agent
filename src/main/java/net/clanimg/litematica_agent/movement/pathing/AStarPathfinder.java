package net.clanimg.litematica_agent.movement.pathing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A* over feet positions. The player occupies the feet block and the block above it.
 */
public final class AStarPathfinder {
    private static final double COST_WALK = 1.0;
    private static final double COST_DIAGONAL = 1.4142;
    private static final double COST_ASCEND = 2.2;
    private static final double COST_DESCEND_BASE = 1.0;
    private static final double COST_DESCEND_PER_BLOCK = 0.5;
    private static final double COST_WATER_FACTOR = 2.0;
    private static final double COST_CLIMB = 1.5;
    private static final double COST_PILLAR = 5.0;
    private static final double COST_DIG_DOWN = 3.0;
    private static final double COST_FLY = 1.0;
    private static final double COST_FLY_DIAGONAL = 1.4142;
    private static final double COST_FLY_VERTICAL = 1.2;
    private static final double COST_FLY_SLOPE = 1.6;
    private static final double HEURISTIC_WEIGHT = 1.1;

    private static final int[][] CARDINALS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[][] DIAGONALS = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    private final NavWorld world;
    private final Map<Long, Integer> flagCache = new HashMap<>();

    public AStarPathfinder(NavWorld world) {
        this.world = world;
    }

    public PathResult find(int startX, int startY, int startZ, Goal goal, PathOptions options) {
        this.flagCache.clear();
        Map<Long, Node> nodes = new HashMap<>();
        NodeHeap open = new NodeHeap();

        Node start = new Node(startX, startY, startZ);
        start.g = 0.0;
        start.h = goal.heuristic(startX, startY, startZ);
        start.f = start.h * HEURISTIC_WEIGHT;
        start.move = MoveType.START;
        nodes.put(start.key, start);
        open.add(start);

        Node best = start;
        int expanded = 0;
        List<Step> steps = new ArrayList<>(32);

        while (!open.isEmpty() && expanded < options.maxNodes()) {
            Node current = open.poll();
            current.closed = true;
            expanded++;

            if (goal.isGoal(current.x, current.y, current.z)) {
                return new PathResult(this.reconstruct(current), true, expanded);
            }

            if (current.h < best.h) {
                best = current;
            }

            steps.clear();
            this.expand(current, options, steps);

            for (Step step : steps) {
                long key = PosUtil.pack(step.x, step.y, step.z);
                if (options.forbidden().test(key)) {
                    continue;
                }

                Node neighbor = nodes.get(key);
                if (neighbor == null) {
                    neighbor = new Node(step.x, step.y, step.z);
                    neighbor.h = goal.heuristic(step.x, step.y, step.z);
                    nodes.put(key, neighbor);
                } else if (neighbor.closed) {
                    continue;
                }

                int pillarCount = current.pillarCount + (step.move == MoveType.PILLAR ? 1 : 0);
                if (pillarCount > options.helperBlocks()) {
                    continue;
                }

                double tentative = current.g + step.cost;
                if (tentative < neighbor.g) {
                    neighbor.g = tentative;
                    neighbor.f = tentative + neighbor.h * HEURISTIC_WEIGHT;
                    neighbor.parent = current;
                    neighbor.move = step.move;
                    neighbor.pillarCount = pillarCount;
                    if (neighbor.heapIndex >= 0) {
                        open.decreaseKey(neighbor);
                    } else {
                        open.add(neighbor);
                    }
                }
            }
        }

        return new PathResult(this.reconstruct(best), false, expanded);
    }

    private List<PathNode> reconstruct(Node end) {
        List<PathNode> path = new ArrayList<>();
        for (Node node = end; node != null; node = node.parent) {
            path.add(new PathNode(node.x, node.y, node.z, node.move));
        }
        Collections.reverse(path);
        return path;
    }

    private void expand(Node node, PathOptions options, List<Step> out) {
        int x = node.x;
        int y = node.y;
        int z = node.z;
        boolean supported = node.move == MoveType.PILLAR || this.isSupported(x, y, z);
        boolean flyHere = options.canFly() && !options.noFlyColumn().test(PosUtil.packColumn(x, z));

        if (supported || !options.canFly()) {
            this.expandWalking(x, y, z, supported, options, out);
        }

        if (flyHere) {
            this.expandFlying(x, y, z, supported, options, out);
        }
    }

    private void expandWalking(int x, int y, int z, boolean supported, PathOptions options, List<Step> out) {
        boolean inWater = this.has(x, y, z, NavWorld.WATER);

        for (int[] dir : CARDINALS) {
            int nx = x + dir[0];
            int nz = z + dir[1];

            if (this.canOccupy(nx, y, nz)) {
                if (this.isSupported(nx, y, nz)) {
                    double cost = COST_WALK * (inWater || this.has(nx, y, nz, NavWorld.WATER) ? COST_WATER_FACTOR : 1.0);
                    out.add(new Step(nx, y, nz, MoveType.WALK, cost));
                } else {
                    this.addDescend(nx, y, nz, options, out);
                }
            }

            if (this.canJumpUp(x, y, z, nx, nz)) {
                out.add(new Step(nx, y + 1, nz, MoveType.ASCEND, COST_ASCEND));
            }
        }

        for (int[] dir : DIAGONALS) {
            int nx = x + dir[0];
            int nz = z + dir[1];
            if (this.canOccupy(nx, y, nz) && this.isSupported(nx, y, nz)
                    && this.canOccupy(nx, y, z) && this.canOccupy(x, y, nz)) {
                out.add(new Step(nx, y, nz, MoveType.DIAGONAL, COST_DIAGONAL));
            }
        }

        if (inWater) {
            if (this.canOccupy(x, y + 1, z)) {
                out.add(new Step(x, y + 1, z, MoveType.SWIM_UP, COST_CLIMB * COST_WATER_FACTOR));
            }
            if (this.has(x, y - 1, z, NavWorld.WATER) && this.canOccupy(x, y - 1, z)) {
                out.add(new Step(x, y - 1, z, MoveType.DESCEND, COST_CLIMB * COST_WATER_FACTOR));
            }
        }

        boolean climbableHere = this.has(x, y, z, NavWorld.CLIMBABLE);
        if ((climbableHere || this.has(x, y + 1, z, NavWorld.CLIMBABLE)) && this.canOccupy(x, y + 1, z)) {
            out.add(new Step(x, y + 1, z, MoveType.CLIMB_UP, COST_CLIMB));
        }
        if (this.has(x, y - 1, z, NavWorld.CLIMBABLE) && this.canOccupy(x, y - 1, z)) {
            out.add(new Step(x, y - 1, z, MoveType.CLIMB_DOWN, COST_CLIMB));
        }

        if (this.has(x, y - 1, z, NavWorld.HELPER) && this.isPassable(x, y + 1, z)) {
            int below = this.flags(x, y - 2, z);
            boolean landing = (below & NavWorld.SOLID_TOP) != 0 && (below & (NavWorld.DANGER | NavWorld.UNLOADED)) == 0
                    || (below & NavWorld.WATER) != 0;
            if (landing) {
                out.add(new Step(x, y - 1, z, MoveType.DIG_DOWN, COST_DIG_DOWN));
            }
        }

        // The helper block goes where the feet are now, so that block must be free to build in.
        if (options.helperBlocks() > 0 && !inWater && !climbableHere
                && supported && !this.has(x, y, z, NavWorld.SOLID_TOP) && !this.has(x, y, z, NavWorld.OCCUPIED)
                && !options.noPillar().test(PosUtil.pack(x, y, z))
                && this.canOccupy(x, y + 1, z)) {
            out.add(new Step(x, y + 1, z, MoveType.PILLAR, COST_PILLAR));
        }
    }

    private void addDescend(int nx, int y, int nz, PathOptions options, List<Step> out) {
        for (int drop = 1; drop <= options.maxFall() + 1; drop++) {
            int ny = y - drop;
            if (!this.canOccupy(nx, ny, nz)) {
                return;
            }
            boolean water = this.has(nx, ny, nz, NavWorld.WATER);
            if (this.isSupported(nx, ny, nz) || water) {
                if (drop <= options.maxFall() || water) {
                    out.add(new Step(nx, ny, nz, MoveType.DESCEND, COST_DESCEND_BASE + COST_DESCEND_PER_BLOCK * drop));
                }
                return;
            }
        }
    }

    private void expandFlying(int x, int y, int z, boolean originSupported, PathOptions options, List<Step> out) {
        for (int[] dir : CARDINALS) {
            int nx = x + dir[0];
            int nz = z + dir[1];
            this.addFly(x, z, nx, y, nz, MoveType.FLY, COST_FLY, originSupported, options, out);

            if (this.isPassable(x, y + 2, z) && this.canOccupy(nx, y, nz)) {
                this.addFly(x, z, nx, y + 1, nz, MoveType.FLY_UP, COST_FLY_SLOPE, originSupported, options, out);
            }
            if (this.canOccupy(x, y - 1, z) && this.canOccupy(nx, y, nz)) {
                this.addFly(x, z, nx, y - 1, nz, MoveType.FLY_DOWN, COST_FLY_SLOPE, originSupported, options, out);
            }
        }

        for (int[] dir : DIAGONALS) {
            int nx = x + dir[0];
            int nz = z + dir[1];
            if (this.canOccupy(nx, y, z) && this.canOccupy(x, y, nz)) {
                this.addFly(x, z, nx, y, nz, MoveType.FLY, COST_FLY_DIAGONAL, originSupported, options, out);
            }
        }

        this.addFly(x, z, x, y + 1, z, MoveType.FLY_UP, COST_FLY_VERTICAL, originSupported, options, out);
        this.addFly(x, z, x, y - 1, z, MoveType.FLY_DOWN, COST_FLY_VERTICAL, originSupported, options, out);
    }

    private void addFly(int ox, int oz, int nx, int ny, int nz, MoveType move, double cost,
                        boolean originSupported, PathOptions options, List<Step> out) {
        if (!this.canOccupy(nx, ny, nz)) {
            return;
        }
        boolean entersNoFly = (nx != ox || nz != oz) && options.noFlyColumn().test(PosUtil.packColumn(nx, nz));
        if (entersNoFly && !(originSupported && this.isSupported(nx, ny, nz))) {
            return;
        }
        out.add(new Step(nx, ny, nz, move, cost));
    }

    /**
     * A jump reaches about 1.25 blocks: one block up is fine, but not onto a low block (e.g. a slab) lying on top of
     * the next block, which would be 1.5 blocks higher. The jump itself needs headroom above the start.
     */
    private boolean canJumpUp(int x, int y, int z, int nx, int nz) {
        if (!this.canOccupy(nx, y + 1, nz) || !this.isSupported(nx, y + 1, nz) || !this.isPassable(x, y + 2, z)) {
            return false;
        }
        boolean raisedHere = this.has(x, y, z, NavWorld.RAISED);
        if (this.has(nx, y + 1, nz, NavWorld.RAISED) && !raisedHere) {
            return false;
        }
        return !raisedHere || this.isPassable(x, y + 3, z);
    }

    /**
     * The player's body fits: feet and head block free, and one more block above when standing half a block higher
     * on a low block.
     */
    private boolean canOccupy(int x, int y, int z) {
        int feet = this.flags(x, y, z);
        int head = this.flags(x, y + 1, z);
        if ((feet & NavWorld.PASSABLE) == 0 || (head & NavWorld.PASSABLE) == 0
                || ((feet | head) & (NavWorld.DANGER | NavWorld.UNLOADED)) != 0) {
            return false;
        }
        return (feet & NavWorld.RAISED) == 0 || this.isPassable(x, y + 2, z);
    }

    private boolean isSupported(int x, int y, int z) {
        int feet = this.flags(x, y, z);
        if ((feet & (NavWorld.WATER | NavWorld.CLIMBABLE)) != 0) {
            return true;
        }
        if ((feet & (NavWorld.PASSABLE | NavWorld.SOLID_TOP)) == (NavWorld.PASSABLE | NavWorld.SOLID_TOP)) {
            return true;
        }
        int below = this.flags(x, y - 1, z);
        return (below & NavWorld.SOLID_TOP) != 0 && (below & (NavWorld.DANGER | NavWorld.UNLOADED)) == 0;
    }

    private boolean isPassable(int x, int y, int z) {
        int flags = this.flags(x, y, z);
        return (flags & NavWorld.PASSABLE) != 0 && (flags & (NavWorld.DANGER | NavWorld.UNLOADED)) == 0;
    }

    private boolean has(int x, int y, int z, int flag) {
        return (this.flags(x, y, z) & flag) != 0;
    }

    private int flags(int x, int y, int z) {
        long key = PosUtil.pack(x, y, z);
        Integer cached = this.flagCache.get(key);
        if (cached != null) {
            return cached;
        }
        int value = this.world.flags(x, y, z);
        this.flagCache.put(key, value);
        return value;
    }

    private record Step(int x, int y, int z, MoveType move, double cost) {
    }

    private static final class Node {
        final int x;
        final int y;
        final int z;
        final long key;
        double g = Double.MAX_VALUE;
        double h;
        double f;
        Node parent;
        MoveType move;
        boolean closed;
        int heapIndex = -1;
        int pillarCount;

        Node(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.key = PosUtil.pack(x, y, z);
        }
    }

    private static final class NodeHeap {
        private Node[] items = new Node[1024];
        private int size;

        boolean isEmpty() {
            return this.size == 0;
        }

        void add(Node node) {
            if (this.size == this.items.length) {
                Node[] grown = new Node[this.items.length * 2];
                System.arraycopy(this.items, 0, grown, 0, this.size);
                this.items = grown;
            }
            this.items[this.size] = node;
            node.heapIndex = this.size;
            this.size++;
            this.siftUp(node.heapIndex);
        }

        Node poll() {
            Node top = this.items[0];
            this.size--;
            if (this.size > 0) {
                this.items[0] = this.items[this.size];
                this.items[0].heapIndex = 0;
                this.siftDown(0);
            }
            this.items[this.size] = null;
            top.heapIndex = -1;
            return top;
        }

        void decreaseKey(Node node) {
            this.siftUp(node.heapIndex);
        }

        private void siftUp(int index) {
            Node node = this.items[index];
            while (index > 0) {
                int parentIndex = (index - 1) >>> 1;
                Node parent = this.items[parentIndex];
                if (node.f >= parent.f) {
                    break;
                }
                this.items[index] = parent;
                parent.heapIndex = index;
                index = parentIndex;
            }
            this.items[index] = node;
            node.heapIndex = index;
        }

        private void siftDown(int index) {
            Node node = this.items[index];
            int half = this.size >>> 1;
            while (index < half) {
                int child = 2 * index + 1;
                int right = child + 1;
                if (right < this.size && this.items[right].f < this.items[child].f) {
                    child = right;
                }
                if (node.f <= this.items[child].f) {
                    break;
                }
                this.items[index] = this.items[child];
                this.items[index].heapIndex = index;
                index = child;
            }
            this.items[index] = node;
            node.heapIndex = index;
        }
    }
}
