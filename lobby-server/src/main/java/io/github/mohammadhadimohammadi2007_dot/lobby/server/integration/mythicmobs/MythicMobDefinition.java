package io.github.mohammadhadimohammadi2007_dot.lobby.server.integration.mythicmobs;

import io.github.mohammadhadimohammadi2007_dot.lobby.server.item.ItemSpec;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.EquipmentSlot;

import java.util.Map;

/**
 * One MythicMobs mob definition parsed from {@code Mobs/*.yml}, ready to spawn as a lobby NPC.
 *
 * @param id             original mob id from the YAML key (e.g. {@code SkeletonKing})
 * @param entityType     vanilla Minecraft entity type fallback
 * @param displayName    display name from {@code Display:} (may be empty)
 * @param scale          entity/model scale multiplier (1.0 default)
 * @param modelId        ModelEngine blueprint id from {@code Model:} or {@code model{mid=...}} skill (may be empty)
 * @param modelAnimation default looping animation (usually {@code "idle"})
 * @param equipment      parsed equipment slots
 */
public record MythicMobDefinition(
        String id,
        EntityType entityType,
        String displayName,
        double scale,
        String modelId,
        String modelAnimation,
        Map<EquipmentSlot, ItemSpec> equipment
) {
    public MythicMobDefinition {
        equipment = Map.copyOf(equipment);
    }

    public boolean hasModel() {
        return modelId != null && !modelId.isBlank();
    }
}
