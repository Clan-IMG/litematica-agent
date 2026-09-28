package net.clanimg.litematica_agent.storage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * Decides which containers to visit, in which order, and what to take from each, given the free inventory space.
 */
public final class WithdrawalPlanner {
    /**
     * @param container record to open
     * @param take      item id to amount
     */
    public record Visit(ContainerRecord container, Map<String, Integer> take) {
    }

    /**
     * @param missing  items that none of the containers can provide (id to amount)
     */
    public record Plan(List<Visit> visits, Map<String, Integer> missing) {
        public boolean isEmpty() {
            return this.visits.isEmpty();
        }
    }

    private WithdrawalPlanner() {
    }

    /**
     * @param needs        wanted amount per item, in priority order
     * @param freeSlots    free inventory slots
     * @param maxStackSize stack size per item id
     */
    public static Plan plan(StorageDatabase storage, String dimension, double px, double py, double pz,
                            LinkedHashMap<String, Integer> needs, int freeSlots, ToIntFunction<String> maxStackSize) {
        Map<String, Integer> missing = new LinkedHashMap<>();
        Map<String, Integer> budget = new LinkedHashMap<>();
        int slotsLeft = freeSlots;

        for (Map.Entry<String, Integer> need : needs.entrySet()) {
            if (slotsLeft <= 0) {
                break;
            }
            String item = need.getKey();
            int available = 0;
            for (ContainerRecord record : storage.containers()) {
                if (record.dimension.equals(dimension)) {
                    available += record.count(item);
                }
            }
            int wanted = need.getValue();
            if (available < wanted) {
                missing.put(item, wanted - available);
            }
            int amount = Math.min(wanted, available);
            if (amount <= 0) {
                continue;
            }
            int stackSize = Math.max(1, maxStackSize.applyAsInt(item));
            int stacks = (amount + stackSize - 1) / stackSize;
            if (stacks > slotsLeft) {
                stacks = slotsLeft;
                amount = stacks * stackSize;
            }
            // Whole stacks are taken, so round up to what actually ends up in the inventory.
            budget.put(item, Math.min(available, stacks * stackSize));
            slotsLeft -= stacks;
        }

        Map<String, Map<String, Integer>> perContainer = new LinkedHashMap<>();
        Map<String, ContainerRecord> records = new HashMap<>();
        double cx = px;
        double cy = py;
        double cz = pz;
        Map<String, Integer> remaining = new LinkedHashMap<>(budget);

        while (remaining.values().stream().anyMatch(v -> v > 0)) {
            ContainerRecord best = null;
            double bestDistance = Double.MAX_VALUE;
            for (ContainerRecord record : storage.containers()) {
                if (!record.dimension.equals(dimension) || perContainer.containsKey(record.key())) {
                    continue;
                }
                boolean useful = false;
                for (Map.Entry<String, Integer> entry : remaining.entrySet()) {
                    if (entry.getValue() > 0 && record.count(entry.getKey()) > 0) {
                        useful = true;
                        break;
                    }
                }
                if (!useful) {
                    continue;
                }
                double distance = record.distanceSq(cx, cy, cz);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = record;
                }
            }
            if (best == null) {
                break;
            }
            Map<String, Integer> take = new LinkedHashMap<>();
            for (Map.Entry<String, Integer> entry : remaining.entrySet()) {
                int amount = Math.min(entry.getValue(), best.count(entry.getKey()));
                if (amount > 0) {
                    take.put(entry.getKey(), amount);
                    entry.setValue(entry.getValue() - amount);
                }
            }
            perContainer.put(best.key(), take);
            records.put(best.key(), best);
            cx = best.x + 0.5;
            cy = best.y + 0.5;
            cz = best.z + 0.5;
        }

        List<Visit> visits = new ArrayList<>();
        perContainer.forEach((key, take) -> visits.add(new Visit(records.get(key), take)));
        return new Plan(visits, missing);
    }
}
