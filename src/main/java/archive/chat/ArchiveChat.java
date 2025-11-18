package archive.chat;

import archive.chat.commands.LastCommand;
import archive.chat.commands.MsgCommand;
import archive.chat.commands.ReplyCommand;
import archive.chat.messaging.ChatMessage;
import archive.chat.messaging.MessageService;
import archive.chat.messaging.VanishManager;
import archive.chat.redis.RedisManager;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * ArchiveChat - Cross-server private messaging plugin for thearchive.world
 * Provides /msg and /reply commands with Redis-based cross-server messaging support
 */
public final class ArchiveChat extends JavaPlugin {
    private RedisManager redisManager;
    private MessageService messageService;
    private MsgCommand msgCommand;
    private ReplyCommand replyCommand;
    private LastCommand lastCommand;
    private String serverName;
    private BukkitTask heartbeatTask;

    private static final long HEARTBEAT_TTL_SECONDS = 60;
    private static final long HEARTBEAT_INTERVAL_TICKS = 30 * 20; // 30 seconds

    @Override
    public void onEnable() {
        saveDefaultConfig();

        serverName = getConfig().getString("server-name", "server1");
        boolean enabled = getConfig().getBoolean("enabled", true);

        // Warn if using default server name
        if (serverName.equals("server1")) {
            getLogger().warning("Using default server-name 'server1'. Consider setting a unique name in config.yml");
        }

        // Initialize Redis if enabled
        if (enabled) {
            String redisUri = getConfig().getString("redis.uri", "redis://localhost:6379");
            redisManager = new RedisManager(this, redisUri, serverName);
            if (redisManager.connect()) {
                // Register chat listener for cross-server chat sync
                var chatListener = new ChatListener(redisManager, serverName);
                Bukkit.getPluginManager().registerEvents(chatListener, this);
                getLogger().info("Chat sync enabled");

                // Register player connection listener for online player registry
                Bukkit.getPluginManager().registerEvents(new PlayerConnectionListener(), this);

                // Register PremiumVanish listener if available
                if (Bukkit.getPluginManager().getPlugin("PremiumVanish") != null) {
                    var vanishManager = new VanishManager(redisManager);
                    Bukkit.getPluginManager().registerEvents(vanishManager, this);
                    getLogger().info("PremiumVanish integration enabled - vanish status changes will sync instantly");
                }

                // Register all currently online players (excluding vanished players)
                for (var player : Bukkit.getOnlinePlayers()) {
                    if (!VanishManager.isVanished(player)) {
                        redisManager.registerPlayer(player.getName());
                    }
                }

                // Start heartbeat task to refresh TTL (crash recovery)
                redisManager.refreshHeartbeat(HEARTBEAT_TTL_SECONDS);
                heartbeatTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this,
                    () -> redisManager.refreshHeartbeat(HEARTBEAT_TTL_SECONDS),
                    HEARTBEAT_INTERVAL_TICKS,
                    HEARTBEAT_INTERVAL_TICKS
                );
            }
        } else {
            getLogger().info("Cross-server features disabled");
        }

        // Initialize message service
        messageService = new MessageService(this, redisManager);
        Bukkit.getPluginManager().registerEvents(messageService, this);

        // Initialize command instances
        msgCommand = new MsgCommand(this, messageService);
        replyCommand = new ReplyCommand(this, messageService);
        lastCommand = new LastCommand(this, messageService);

        // Register commands using Paper's lifecycle events (Brigadier)
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            var registrar = event.registrar();
            msgCommand.register(registrar);
            replyCommand.register(registrar);
            lastCommand.register(registrar);
            getLogger().info("Registered /msg, /w, /whisper, /tell, /pm, /reply, /r, /last, /l commands");
        });

        getLogger().info("ArchiveChat enabled!");
    }

    @Override
    public void onDisable() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel();
        }
        if (redisManager != null) {
            redisManager.cleanupServerPlayers();
            redisManager.disconnect();
        }
    }

    public MessageService getMessageService() {
        return messageService;
    }

    /**
     * Manually sync a player's vanish status with the Redis online player registry.
     * This can be called by other plugins when a player's vanish status changes.
     *
     * @param player The player whose vanish status should be synced
     */
    public void syncPlayerVanishStatus(org.bukkit.entity.Player player) {
        if (redisManager != null && redisManager.isConnected()) {
            if (VanishManager.isVanished(player)) {
                redisManager.unregisterPlayer(player.getName());
            } else {
                redisManager.registerPlayer(player.getName());
            }
        }
    }

    public void handleIncomingChat(ChatMessage msg) {
        // Ignore messages from our own server
        if (msg.senderServer().equals(serverName)) {
            return;
        }

        // Use the sender's server name directly (includes their chosen formatting)
        String prefix = msg.senderServer() + " ";

        // Escape user input to prevent MiniMessage injection
        var mm = MiniMessage.miniMessage();
        String escapedName = mm.escapeTags(msg.senderName());
        String escapedMessage = mm.escapeTags(msg.message());

        // Format: ServerPrefix PlayerName: message
        String formatted = prefix + "<white>" + escapedName + "<gray>: <white>" + escapedMessage;
        var component = mm.deserialize(formatted);

        // Broadcast to all online players
        Bukkit.broadcast(component);
    }

    /**
     * Listener for player connections to maintain Redis online player registry
     */
    private class PlayerConnectionListener implements Listener {
        @EventHandler
        public void onPlayerJoin(PlayerJoinEvent event) {
            if (redisManager != null && redisManager.isConnected()) {
                // Only register non-vanished players
                // Vanished players will be registered when they unvanish
                if (!VanishManager.isVanished(event.getPlayer())) {
                    redisManager.registerPlayer(event.getPlayer().getName());
                }
            }
        }

        @EventHandler
        public void onPlayerQuit(PlayerQuitEvent event) {
            if (redisManager != null && redisManager.isConnected()) {
                redisManager.unregisterPlayer(event.getPlayer().getName());
            }
        }

        /**
         * Listen for vanish plugins loading after ArchiveChat.
         * Schedule a task to check all players' vanish status after other plugins are loaded.
         */
        @EventHandler(priority = EventPriority.MONITOR)
        public void onPluginEnable(PluginEnableEvent event) {
            String pluginName = event.getPlugin().getName().toLowerCase();
            // Detect common vanish plugins
            if (pluginName.contains("vanish") || pluginName.contains("essentials")) {
                // Schedule a delayed task to refresh player registry after vanish plugin is fully loaded
                Bukkit.getScheduler().runTaskLater(ArchiveChat.this, () -> {
                    if (redisManager != null && redisManager.isConnected()) {
                        getLogger().info("Refreshing online player registry after " + event.getPlugin().getName() + " loaded");
                        // Re-sync all online players
                        for (var player : Bukkit.getOnlinePlayers()) {
                            if (VanishManager.isVanished(player)) {
                                redisManager.unregisterPlayer(player.getName());
                            } else {
                                redisManager.registerPlayer(player.getName());
                            }
                        }
                    }
                }, 20L); // Wait 1 second for plugin to fully initialize
            }
        }
    }
}

