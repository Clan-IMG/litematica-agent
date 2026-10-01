package net.clanimg.litematica_agent.movement.pathing;

import java.util.function.LongPredicate;

/**
 * @param canFly          whether flight moves may be used
 * @param maxFall         maximum number of blocks the player may drop without taking damage
 * @param helperBlocks    number of helper blocks available for pillaring up (0 disables pillaring)
 * @param maxNodes        search budget
 * @param noFlyColumn     columns (packed with {@link PosUtil#packColumn}) where flight is known to be unavailable
 * @param forbidden       feet positions (packed with {@link PosUtil#pack}) the path must not use
 * @param noPillar        positions (packed with {@link PosUtil#pack}) where pillaring up already failed
 */
public record PathOptions(
        boolean canFly,
        int maxFall,
        int helperBlocks,
        int maxNodes,
        LongPredicate noFlyColumn,
        LongPredicate forbidden,
        LongPredicate noPillar
) {
    public static final LongPredicate NONE = value -> false;

    public static PathOptions walking() {
        return new PathOptions(false, 3, 0, 40_000, NONE, NONE, NONE);
    }

    public static PathOptions flying() {
        return new PathOptions(true, 3, 0, 40_000, NONE, NONE, NONE);
    }

    public PathOptions withHelperBlocks(int count) {
        return new PathOptions(this.canFly, this.maxFall, count, this.maxNodes, this.noFlyColumn, this.forbidden, this.noPillar);
    }

    public PathOptions withMaxFall(int blocks) {
        return new PathOptions(this.canFly, blocks, this.helperBlocks, this.maxNodes, this.noFlyColumn, this.forbidden, this.noPillar);
    }

    public PathOptions withNoFly(LongPredicate predicate) {
        return new PathOptions(this.canFly, this.maxFall, this.helperBlocks, this.maxNodes, predicate, this.forbidden, this.noPillar);
    }

    public PathOptions withForbidden(LongPredicate predicate) {
        return new PathOptions(this.canFly, this.maxFall, this.helperBlocks, this.maxNodes, this.noFlyColumn, predicate, this.noPillar);
    }

    public PathOptions withNoPillar(LongPredicate predicate) {
        return new PathOptions(this.canFly, this.maxFall, this.helperBlocks, this.maxNodes, this.noFlyColumn, this.forbidden, predicate);
    }

    public PathOptions withMaxNodes(int nodes) {
        return new PathOptions(this.canFly, this.maxFall, this.helperBlocks, nodes, this.noFlyColumn, this.forbidden, this.noPillar);
    }
}
