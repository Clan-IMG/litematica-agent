package net.clanimg.litematica_agent.storage;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The agent's knowledge about the player's storage. Updated incrementally when the agent takes or puts items, so a
 * full re-scan is only needed when the player changed the storage by hand.
 */
public final class StorageDatabase {
    private final Map<String, ContainerRecord> containers = new LinkedHashMap<>();

    public Collection<ContainerRecord> containers() {
        return this.containers.values();
    }

    public boolean isEmpty() {
        return this.containers.isEmpty();
    }

    public int size() {
        return this.containers.size();
    }

    public void put(ContainerRecord record) {
        this.containers.put(record.key(), record);
    }

    public ContainerRecord get(String key) {
        return this.containers.get(key);
    }

    public void remove(String key) {
        this.containers.remove(key);
    }

    public void clear() {
        this.containers.clear();
    }

    public int total(String itemId) {
        int sum = 0;
        for (ContainerRecord record : this.containers.values()) {
            sum += record.count(itemId);
        }
        return sum;
    }

    public Map<String, Integer> totals() {
        Map<String, Integer> totals = new HashMap<>();
        for (ContainerRecord record : this.containers.values()) {
            record.items.forEach((item, count) -> totals.merge(item, count, Integer::sum));
        }
        return totals;
    }

    public List<ContainerRecord> containersWith(String itemId, String dimension, double px, double py, double pz) {
        List<ContainerRecord> result = new ArrayList<>();
        for (ContainerRecord record : this.containers.values()) {
            if (record.dimension.equals(dimension) && record.count(itemId) > 0) {
                result.add(record);
            }
        }
        result.sort(Comparator.comparingDouble(record -> record.distanceSq(px, py, pz)));
        return result;
    }

    public List<ContainerRecord> toList() {
        return new ArrayList<>(this.containers.values());
    }

    public void load(List<ContainerRecord> records) {
        this.containers.clear();
        for (ContainerRecord record : records) {
            if (record.items == null) {
                record.items = new LinkedHashMap<>();
            }
            this.put(record);
        }
    }
}
