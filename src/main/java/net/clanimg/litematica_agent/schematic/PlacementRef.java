package net.clanimg.litematica_agent.schematic;

import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import net.minecraft.util.math.BlockPos;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Stable, serializable reference to a Litematica placement that survives game restarts.
 */
public record PlacementRef(String hashId, String name, String schematicFile, int originX, int originY, int originZ) {
    public static PlacementRef of(SchematicPlacement placement) {
        BlockPos origin = placement.getOrigin();
        Path file = placement.getSchematicFile();
        return new PlacementRef(
                placement.getHashId() == null ? "" : placement.getHashId().toString(),
                placement.getName(),
                file == null ? "" : file.toString(),
                origin.getX(), origin.getY(), origin.getZ());
    }

    boolean matchesLoosely(SchematicPlacement placement) {
        BlockPos origin = placement.getOrigin();
        Path file = placement.getSchematicFile();
        return Objects.equals(this.name, placement.getName())
                && Objects.equals(this.schematicFile, file == null ? "" : file.toString())
                && origin.getX() == this.originX && origin.getY() == this.originY && origin.getZ() == this.originZ;
    }
}
