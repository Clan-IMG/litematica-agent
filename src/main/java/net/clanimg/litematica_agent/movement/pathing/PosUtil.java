package net.clanimg.litematica_agent.movement.pathing;

/**
 * Packs block coordinates into a long using the same layout as Minecraft's BlockPos (26 bits X, 26 bits Z, 12 bits Y).
 */
public final class PosUtil {
    private static final int BITS_XZ = 26;
    private static final int BITS_Y = 12;
    private static final long MASK_XZ = (1L << BITS_XZ) - 1L;
    private static final long MASK_Y = (1L << BITS_Y) - 1L;
    private static final int SHIFT_Z = BITS_Y;
    private static final int SHIFT_X = BITS_Y + BITS_XZ;

    private PosUtil() {
    }

    public static long pack(int x, int y, int z) {
        return ((x & MASK_XZ) << SHIFT_X) | ((z & MASK_XZ) << SHIFT_Z) | (y & MASK_Y);
    }

    public static int x(long packed) {
        return (int) (packed << (64 - SHIFT_X - BITS_XZ) >> (64 - BITS_XZ));
    }

    public static int y(long packed) {
        return (int) (packed << (64 - BITS_Y) >> (64 - BITS_Y));
    }

    public static int z(long packed) {
        return (int) (packed << (64 - SHIFT_Z - BITS_XZ) >> (64 - BITS_XZ));
    }

    public static long packColumn(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }
}
