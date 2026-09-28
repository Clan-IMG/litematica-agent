package net.clanimg.litematica_agent.movement.pathing;

import java.util.HashMap;
import java.util.Map;

final class TestWorld implements NavWorld {
    private static final int SOLID = SOLID_TOP;
    private static final int AIR = PASSABLE;

    private final Map<Long, Integer> blocks = new HashMap<>();
    private final int floorY;

    TestWorld(int floorY) {
        this.floorY = floorY;
    }

    TestWorld solid(int x, int y, int z) {
        this.blocks.put(PosUtil.pack(x, y, z), SOLID);
        return this;
    }

    TestWorld set(int x, int y, int z, int flags) {
        this.blocks.put(PosUtil.pack(x, y, z), flags);
        return this;
    }

    TestWorld wall(int x1, int z1, int x2, int z2, int yFrom, int yTo) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
            for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                for (int y = yFrom; y <= yTo; y++) {
                    this.solid(x, y, z);
                }
            }
        }
        return this;
    }

    TestWorld hole(int x1, int z1, int x2, int z2) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
            for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                for (int y = this.floorY - 30; y <= this.floorY; y++) {
                    this.set(x, y, z, AIR);
                }
            }
        }
        return this;
    }

    @Override
    public int flags(int x, int y, int z) {
        Integer value = this.blocks.get(PosUtil.pack(x, y, z));
        if (value != null) {
            return value;
        }
        return y <= this.floorY ? SOLID : AIR;
    }
}
