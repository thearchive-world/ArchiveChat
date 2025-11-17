package archive.chat;

import archive.chat.commands.LastCommand;
import archive.chat.commands.MsgCommand;
import archive.chat.commands.ReplyCommand;
import archive.chat.messaging.ChatMessage;
import archive.chat.messaging.MessageService;
import archive.chat.redis.RedisManager;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

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
            redisManager = new RedisManager(this, redisUri);
            if (redisManager.connect()) {
                // Register chat listener for cross-server chat sync
                var chatListener = new ChatListener(redisManager, serverName);
                Bukkit.getPluginManager().registerEvents(chatListener, this);
                getLogger().info("Chat sync enabled");
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
        if (redisManager != null) {
            redisManager.disconnect();
        }
    }

    public MessageService getMessageService() {
        return messageService;
    }

    public void handleIncomingChat(ChatMessage msg) {
        // Ignore messages from our own server
        if (msg.senderServer().equals(serverName)) {
            return;
        }

        // Look up the prefix for the sender's server
        String prefix = getConfig().getString("server-prefixes." + msg.senderServer(), "");
        if (prefix.isEmpty()) {
            // No prefix configured for this server, use default format
            prefix = "<gray>" + msg.senderServer() + " ";
        }

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
}

