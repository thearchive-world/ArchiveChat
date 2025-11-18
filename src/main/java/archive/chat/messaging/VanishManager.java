package archive.chat.messaging;

import archive.chat.redis.RedisManager;
import de.myzelyam.api.vanish.PlayerHideEvent;
import de.myzelyam.api.vanish.PlayerShowEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Manages vanish plugin integration with both utility methods and event listeners.
 * Works with any vanish plugin that properly uses the Bukkit visibility API.
 *
 * Compatible with PremiumVanish, SuperVanish, Essentials, and other vanish plugins.
 */
public class VanishManager implements Listener {
    private final RedisManager redisManager;

    public VanishManager(RedisManager redisManager) {
        this.redisManager = redisManager;
    }

    /**
     * Check if a viewer can see a target player.
     * Uses the official Bukkit/Paper canSee() API which respects all visibility hiding.
     *
     * @param viewer The player trying to see the target
     * @param target The player being viewed
     * @return true if the viewer can see the target, false if target is hidden from viewer
     */
    public static boolean canSee(Player viewer, Player target) {
        return viewer.canSee(target);
    }

    /**
     * Check if a player is effectively "vanished" from a specific viewer.
     * This is the inverse of canSee() for more intuitive reading in some contexts.
     *
     * @param viewer The player trying to see the target
     * @param target The player being checked
     * @return true if target is hidden from viewer, false otherwise
     */
    public static boolean isHiddenFrom(Player viewer, Player target) {
        return !viewer.canSee(target);
    }

    /**
     * Check if a player is globally vanished (has the "vanished" metadata).
     * This is used for cross-server scenarios where we can't use canSee().
     *
     * Most vanish plugins (PremiumVanish, SuperVanish, Essentials, etc.) set this metadata.
     *
     * @param player The player to check
     * @return true if the player has vanished metadata, false otherwise
     */
    public static boolean isVanished(Player player) {
        return player.hasMetadata("vanished");
    }

    // ========== PremiumVanish Event Listeners ==========

    /**
     * Called when a player vanishes.
     * Remove them from the Redis online player registry so they cannot be messaged cross-server.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerVanish(PlayerHideEvent event) {
        if (redisManager != null && redisManager.isConnected()) {
            redisManager.unregisterPlayer(event.getPlayer().getName());
        }
    }

    /**
     * Called when a player unvanishes.
     * Add them back to the Redis online player registry so they can be messaged cross-server.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerUnvanish(PlayerShowEvent event) {
        if (redisManager != null && redisManager.isConnected()) {
            redisManager.registerPlayer(event.getPlayer().getName());
        }
    }
}
