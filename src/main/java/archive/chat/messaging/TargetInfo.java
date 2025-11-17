package archive.chat.messaging;

import java.util.UUID;

/**
 * Holds target player information for reply/last tracking.
 * Supports both local (UUID known) and cross-server (name only) targets.
 */
public record TargetInfo(UUID uuid, String name) {
    /**
     * Creates a TargetInfo for a cross-server target where only name is known.
     */
    public static TargetInfo crossServer(String name) {
        return new TargetInfo(null, name);
    }
}
