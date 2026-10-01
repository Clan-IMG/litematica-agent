package net.clanimg.litematica_agent.storage;

import com.google.gson.Gson;
import net.clanimg.litematica_agent.persistence.WorldData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StorageHomesTest {
    private static ContainerRecord chest(int x) {
        return new ContainerRecord("minecraft:overworld", x, 64, 20);
    }

    @Test
    void reservationsSkipKnownNumbersAndReuseTheWarehouseSuggestion() {
        WorldData data = new WorldData();
        data.storageHomeCommand = "/home 1";
        data.buildHomeCommand = "home 2";
        StorageHomes.Route first = StorageHomes.request(data, chest(10));
        assertEquals(3, first.suggestedNumber);
        assertSame(first, StorageHomes.request(data, chest(15)));
        assertEquals(4, StorageHomes.request(data, chest(200)).suggestedNumber);
    }

    @Test
    void homesAreScopedToWarehouseAndDimensionAndSurviveReload() {
        WorldData data = new WorldData();
        StorageHomes.configure(data, chest(10), "home 7");
        StorageHomes.configure(data, chest(200), "home 8");
        Gson gson = new Gson();
        WorldData restored = gson.fromJson(gson.toJson(data), WorldData.class);
        restored.sanitize();
        assertEquals("home 7", StorageHomes.command(restored, chest(15)));
        assertEquals("home 8", StorageHomes.command(restored, chest(205)));
        assertEquals("", StorageHomes.command(restored, chest(400)));
        assertEquals("", StorageHomes.command(restored, new ContainerRecord("minecraft:the_nether", 10, 64, 20)));
    }

    @Test
    void oldGlobalHomesContinueToWorkUntilAHomeIsMapped() {
        WorldData data = new WorldData();
        data.storageHomeCommand = "home lager";
        assertEquals("home lager", StorageHomes.command(data, chest(200)));
        StorageHomes.request(data, chest(200));
        assertEquals("home lager", StorageHomes.command(data, chest(200)));
        StorageHomes.configure(data, chest(200), "home 9");
        assertEquals("home 9", StorageHomes.command(data, chest(200)));
    }

    @Test
    void legacyJsonAndNullRoutesAreSanitized() {
        WorldData data = new Gson().fromJson("{\"storageHomes\":null,\"storageHomeCommand\":null}", WorldData.class);
        data.sanitize();
        assertNotNull(data.storageHomes);
        assertEquals("", StorageHomes.command(data, chest(0)));
        assertEquals(1, StorageHomes.request(data, chest(0)).suggestedNumber);
    }
}
