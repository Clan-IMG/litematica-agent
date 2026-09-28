package net.clanimg.litematica_agent.storage;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WithdrawalPlannerTest {
    private static final String DIM = "minecraft:overworld";

    private static ContainerRecord chest(int x, String item, int count) {
        ContainerRecord record = new ContainerRecord(DIM, x, 64, 0);
        record.add(item, count);
        return record;
    }

    @Test
    void takesFromNearestContainerFirst() {
        StorageDatabase storage = new StorageDatabase();
        storage.put(chest(50, "minecraft:stone", 640));
        storage.put(chest(5, "minecraft:stone", 640));

        LinkedHashMap<String, Integer> needs = new LinkedHashMap<>();
        needs.put("minecraft:stone", 100);
        WithdrawalPlanner.Plan plan = WithdrawalPlanner.plan(storage, DIM, 0, 64, 0, needs, 36, id -> 64);

        assertEquals(1, plan.visits().size());
        assertEquals(5, plan.visits().get(0).container().x);
        assertEquals(128, plan.visits().get(0).take().get("minecraft:stone"));
        assertTrue(plan.missing().isEmpty());
    }

    @Test
    void splitsAcrossContainersAndReportsMissing() {
        StorageDatabase storage = new StorageDatabase();
        storage.put(chest(5, "minecraft:glass", 30));
        storage.put(chest(10, "minecraft:glass", 20));

        LinkedHashMap<String, Integer> needs = new LinkedHashMap<>();
        needs.put("minecraft:glass", 64);
        WithdrawalPlanner.Plan plan = WithdrawalPlanner.plan(storage, DIM, 0, 64, 0, needs, 36, id -> 64);

        assertEquals(2, plan.visits().size());
        assertEquals(30, plan.visits().get(0).take().get("minecraft:glass"));
        assertEquals(20, plan.visits().get(1).take().get("minecraft:glass"));
        assertEquals(14, plan.missing().get("minecraft:glass"));
    }

    @Test
    void respectsFreeInventorySlots() {
        StorageDatabase storage = new StorageDatabase();
        storage.put(chest(5, "minecraft:stone", 6400));
        ContainerRecord other = chest(6, "minecraft:dirt", 6400);
        storage.put(other);

        LinkedHashMap<String, Integer> needs = new LinkedHashMap<>();
        needs.put("minecraft:stone", 64 * 10);
        needs.put("minecraft:dirt", 64 * 10);
        WithdrawalPlanner.Plan plan = WithdrawalPlanner.plan(storage, DIM, 0, 64, 0, needs, 12, id -> 64);

        int stone = plan.visits().stream().mapToInt(v -> v.take().getOrDefault("minecraft:stone", 0)).sum();
        int dirt = plan.visits().stream().mapToInt(v -> v.take().getOrDefault("minecraft:dirt", 0)).sum();
        assertEquals(640, stone);
        assertEquals(128, dirt);
    }

    @Test
    void ignoresOtherDimensions() {
        StorageDatabase storage = new StorageDatabase();
        ContainerRecord nether = new ContainerRecord("minecraft:the_nether", 1, 64, 0);
        nether.add("minecraft:stone", 64);
        storage.put(nether);

        LinkedHashMap<String, Integer> needs = new LinkedHashMap<>();
        needs.put("minecraft:stone", 10);
        WithdrawalPlanner.Plan plan = WithdrawalPlanner.plan(storage, DIM, 0, 64, 0, needs, 36, id -> 64);

        assertTrue(plan.isEmpty());
        assertEquals(10, plan.missing().get("minecraft:stone"));
    }

    @Test
    void databaseTotalsAndUpdates() {
        StorageDatabase storage = new StorageDatabase();
        ContainerRecord a = chest(1, "minecraft:stone", 10);
        storage.put(a);
        storage.put(chest(2, "minecraft:stone", 5));
        assertEquals(15, storage.total("minecraft:stone"));

        a.remove("minecraft:stone", 10);
        assertEquals(5, storage.total("minecraft:stone"));
        assertEquals(0, a.count("minecraft:stone"));
    }
}
