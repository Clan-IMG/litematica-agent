package net.clanimg.litematica_agent.storage;

import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Known contents of one container. Item ids are registry ids like {@code minecraft:stone}.
 */
public final class ContainerRecord {
    public String dimension = "minecraft:overworld";
    public int x;
    public int y;
    public int z;
    /** Contents of the slots the agent may use. */
    public Map<String, Integer> items = new LinkedHashMap<>();
    /** Indices of the container slots the agent may use; null means all of them (records of older versions). */
    public @Nullable List<Integer> slots;
    public long scannedAt;

    public ContainerRecord() {
    }

    public ContainerRecord(String dimension, int x, int y, int z) {
        this.dimension = dimension;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public String key() {
        return key(this.dimension, this.x, this.y, this.z);
    }

    public static String key(String dimension, int x, int y, int z) {
        return dimension + "|" + x + "|" + y + "|" + z;
    }

    public boolean allows(int slotIndex) {
        return this.slots == null || this.slots.contains(slotIndex);
    }

    public int count(String itemId) {
        return this.items.getOrDefault(itemId, 0);
    }

    public void add(String itemId, int amount) {
        this.items.merge(itemId, amount, Integer::sum);
    }

    public void remove(String itemId, int amount) {
        int left = this.count(itemId) - amount;
        if (left > 0) {
            this.items.put(itemId, left);
        } else {
            this.items.remove(itemId);
        }
    }

    public double distanceSq(double px, double py, double pz) {
        double dx = this.x + 0.5 - px;
        double dy = this.y + 0.5 - py;
        double dz = this.z + 0.5 - pz;
        return dx * dx + dy * dy + dz * dz;
    }
}
