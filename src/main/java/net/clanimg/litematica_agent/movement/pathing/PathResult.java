package net.clanimg.litematica_agent.movement.pathing;

import java.util.List;

/**
 * @param nodes       path including the start node; empty if the start position itself is invalid
 * @param reachedGoal false if only a partial path towards the goal was found
 * @param expanded    number of expanded nodes (diagnostics)
 */
public record PathResult(List<PathNode> nodes, boolean reachedGoal, int expanded) {
    public boolean isEmpty() {
        return this.nodes.size() <= 1;
    }

    public int helperBlocksUsed() {
        int count = 0;
        for (PathNode node : this.nodes) {
            if (node.move() == MoveType.PILLAR) {
                count++;
            }
        }
        return count;
    }

    public PathNode last() {
        return this.nodes.get(this.nodes.size() - 1);
    }
}
