package net.clanimg.litematica_agent.movement.pathing;

import java.util.Collection;
import java.util.List;

public interface Goal {
    boolean isGoal(int x, int y, int z);

    /** Must never overestimate the remaining cost. */
    double heuristic(int x, int y, int z);

    static Goal block(int gx, int gy, int gz) {
        return new Goal() {
            @Override
            public boolean isGoal(int x, int y, int z) {
                return x == gx && y == gy && z == gz;
            }

            @Override
            public double heuristic(int x, int y, int z) {
                return distance(x - gx, y - gy, z - gz);
            }
        };
    }

    /** Any feet position whose center lies within {@code radius} of the given point. */
    static Goal near(double px, double py, double pz, double radius) {
        double radiusSq = radius * radius;
        return new Goal() {
            @Override
            public boolean isGoal(int x, int y, int z) {
                double dx = x + 0.5 - px;
                double dy = y - py;
                double dz = z + 0.5 - pz;
                return dx * dx + dy * dy + dz * dz <= radiusSq;
            }

            @Override
            public double heuristic(int x, int y, int z) {
                double dx = x + 0.5 - px;
                double dy = y - py;
                double dz = z + 0.5 - pz;
                return Math.max(0.0, Math.sqrt(dx * dx + dy * dy + dz * dz) - radius);
            }
        };
    }

    static Goal anyOf(Collection<Goal> goals) {
        List<Goal> list = List.copyOf(goals);
        return new Goal() {
            @Override
            public boolean isGoal(int x, int y, int z) {
                for (Goal goal : list) {
                    if (goal.isGoal(x, y, z)) {
                        return true;
                    }
                }
                return false;
            }

            @Override
            public double heuristic(int x, int y, int z) {
                double best = Double.MAX_VALUE;
                for (Goal goal : list) {
                    best = Math.min(best, goal.heuristic(x, y, z));
                }
                return list.isEmpty() ? 0.0 : best;
            }
        };
    }

    static double distance(int dx, int dy, int dz) {
        return Math.sqrt((double) dx * dx + (double) dy * dy + (double) dz * dz);
    }
}
