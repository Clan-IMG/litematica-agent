package net.clanimg.litematica_agent.storage;

import net.clanimg.litematica_agent.persistence.WorldData;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;

/** Persistent home access for nearby containers that form one warehouse. */
public final class StorageHomes {
    private static final double WAREHOUSE_RADIUS_SQ = 48.0 * 48.0;

    private StorageHomes() {
    }

    public static final class Route {
        public String dimension = "minecraft:overworld";
        public int x;
        public int y;
        public int z;
        public String command = "";
        /** Reserved locally; an arbitrary server's private home list is not available to the client. */
        public int suggestedNumber;
    }

    public static String command(WorldData data, ContainerRecord container) {
        Route route = nearest(data, container);
        if (route != null && !route.command.isBlank()) {
            return route.command;
        }
        // Preserve old settings until warehouse-specific homes have actually been configured.
        boolean mapped = data.storageHomes.stream().anyMatch(home -> !home.command.isBlank());
        return mapped ? "" : data.storageHomeCommand;
    }

    public static Route request(WorldData data, ContainerRecord container) {
        return request(data, container, 0);
    }

    /**
     * @param preferredNumber the number to suggest first (e.g. the session id); 0 means no preference
     */
    public static Route request(WorldData data, ContainerRecord container, int preferredNumber) {
        Route route = nearest(data, container);
        if (route == null) {
            route = new Route();
            route.dimension = container.dimension;
            route.x = container.x;
            route.y = container.y;
            route.z = container.z;
            data.storageHomes.add(route);
        }
        if (route.suggestedNumber <= 0) {
            Set<Integer> used = new HashSet<>();
            reserve(used, data.storageHomeCommand);
            reserve(used, data.buildHomeCommand);
            for (Route known : data.storageHomes) {
                reserve(used, known.command);
                used.add(known.suggestedNumber);
            }
            // Prefer the session id so the player recognises which home belongs to which session.
            int number = preferredNumber > 0 && !used.contains(preferredNumber) ? preferredNumber : 1;
            while (used.contains(number)) {
                number++;
            }
            route.suggestedNumber = number;
        }
        return route;
    }

    public static void configure(WorldData data, @Nullable ContainerRecord container, String command) {
        if (container == null) {
            data.storageHomeCommand = command;
        } else {
            Route route = request(data, container);
            route.command = command;
            // Keep the legacy display/configuration useful; mapped warehouses always take precedence.
            data.storageHomeCommand = command;
        }
    }

    private static @Nullable Route nearest(WorldData data, ContainerRecord container) {
        Route best = null;
        double closest = WAREHOUSE_RADIUS_SQ;
        for (Route route : data.storageHomes) {
            if (!container.dimension.equals(route.dimension)) {
                continue;
            }
            double dx = container.x - route.x;
            double dy = container.y - route.y;
            double dz = container.z - route.z;
            double distance = dx * dx + dy * dy + dz * dz;
            if (distance <= closest) {
                best = route;
                closest = distance;
            }
        }
        return best;
    }

    private static void reserve(Set<Integer> used, String command) {
        if (command == null) {
            return;
        }
        String[] parts = command.trim().replaceFirst("^/", "").split("\\s+");
        if (parts.length != 2 || !(parts[0].equalsIgnoreCase("home") || parts[0].equalsIgnoreCase("sethome"))) {
            return;
        }
        try {
            used.add(Integer.parseInt(parts[1]));
        } catch (NumberFormatException ignored) {
            // Named homes do not reserve a numeric home.
        }
    }
}
