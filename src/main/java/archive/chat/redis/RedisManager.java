package archive.chat.redis;

import archive.chat.ArchiveChat;
import archive.chat.messaging.ChatMessage;
import archive.chat.messaging.PrivateMessage;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.RedisConnectionStateAdapter;
import org.bukkit.Bukkit;

public class RedisManager {
    private final ArchiveChat plugin;
    private final String uri;
    private final String serverName;
    private RedisClient client;
    private StatefulRedisPubSubConnection<String, String> pubSubConnection;
    private StatefulRedisConnection<String, String> connection;
    private volatile boolean connected = false;

    private static final String PRIVATE_CHANNEL = "archivechat:private";
    private static final String CHAT_CHANNEL = "archivechat:chat";
    private static final String ONLINE_PREFIX = "archivechat:online:";

    public RedisManager(ArchiveChat plugin, String uri, String serverName) {
        this.plugin = plugin;
        this.uri = uri;
        this.serverName = serverName;
    }

    public boolean connect() {
        try {
            client = RedisClient.create(uri);
            connection = client.connect();
            pubSubConnection = client.connectPubSub();

            // Add connection state listener to detect disconnects
            connection.addListener(new RedisConnectionStateAdapter() {
                @Override
                public void onRedisDisconnected(io.lettuce.core.RedisChannelHandler<?, ?> connection) {
                    connected = false;
                    plugin.getLogger().warning("Redis connection lost");
                }

                @Override
                public void onRedisConnected(io.lettuce.core.RedisChannelHandler<?, ?> connection, java.net.SocketAddress socketAddress) {
                    connected = true;
                    plugin.getLogger().info("Redis connection restored");
                }
            });

            // Subscribe to message channels
            pubSubConnection.addListener(new RedisPubSubAdapter<>() {
                @Override
                public void message(String channel, String message) {
                    if (channel.equals(PRIVATE_CHANNEL)) {
                        handlePrivateMessage(message);
                    } else if (channel.equals(CHAT_CHANNEL)) {
                        handleChatMessage(message);
                    }
                }
            });

            pubSubConnection.sync().subscribe(PRIVATE_CHANNEL, CHAT_CHANNEL);
            connected = true;
            plugin.getLogger().info("Connected to Redis at " + uri);
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to connect to Redis: " + e.getMessage());
            plugin.getLogger().info("Running in local-only mode (no cross-server messaging)");
            connected = false;
            return false;
        }
    }

    public void disconnect() {
        if (pubSubConnection != null) pubSubConnection.close();
        if (connection != null) connection.close();
        if (client != null) client.shutdown();
    }

    public boolean isConnected() {
        return connected;
    }

    public void sendCrossServerMessage(PrivateMessage msg) {
        if (!connected) return;
        connection.async().publish(PRIVATE_CHANNEL, msg.toJson());
    }

    public void sendChatMessage(ChatMessage msg) {
        if (!connected) return;
        connection.async().publish(CHAT_CHANNEL, msg.toJson());
    }

    private void handlePrivateMessage(String json) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                var msg = PrivateMessage.fromJson(json);
                // Validate required fields
                if (msg.senderName() == null || msg.recipientName() == null || msg.message() == null) {
                    plugin.getLogger().warning("Invalid private message: missing required fields");
                    return;
                }
                plugin.getMessageService().handleIncomingMessage(msg);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to deserialize private message: " + e.getMessage());
            }
        });
    }

    private void handleChatMessage(String json) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                var msg = ChatMessage.fromJson(json);
                // Validate required fields
                if (msg.senderName() == null || msg.senderServer() == null || msg.message() == null) {
                    plugin.getLogger().warning("Invalid chat message: missing required fields");
                    return;
                }
                plugin.handleIncomingChat(msg);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to deserialize chat message: " + e.getMessage());
            }
        });
    }

    /**
     * Register a player as online on this server
     */
    public void registerPlayer(String playerName) {
        if (!connected) return;
        connection.async().sadd(ONLINE_PREFIX + serverName, playerName.toLowerCase());
    }

    /**
     * Unregister a player from this server
     */
    public void unregisterPlayer(String playerName) {
        if (!connected) return;
        connection.async().srem(ONLINE_PREFIX + serverName, playerName.toLowerCase());
    }

    /**
     * Check if a player is online on any server.
     * Uses SCAN to discover all server sets (safe, non-blocking cursor-based iteration).
     */
    public boolean isPlayerOnlineAnywhere(String playerName) {
        if (!connected) return false;
        try {
            String lowerName = playerName.toLowerCase();
            var scanArgs = ScanArgs.Builder.matches(ONLINE_PREFIX + "*").limit(100);
            var cursor = connection.sync().scan(scanArgs);

            while (true) {
                for (String key : cursor.getKeys()) {
                    if (connection.sync().sismember(key, lowerName)) {
                        return true;
                    }
                }
                if (cursor.isFinished()) {
                    break;
                }
                cursor = connection.sync().scan(cursor, scanArgs);
            }
            return false;
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to check player online status: " + e.getMessage());
            return false;
        }
    }

    /**
     * Refresh TTL on this server's player set (heartbeat for crash recovery).
     * If server crashes, the set will auto-expire after TTL seconds.
     */
    public void refreshHeartbeat(long ttlSeconds) {
        if (!connected) return;
        connection.async().expire(ONLINE_PREFIX + serverName, ttlSeconds);
    }

    /**
     * Remove all players registered for this server (cleanup on shutdown)
     */
    public void cleanupServerPlayers() {
        if (!connected) return;
        try {
            connection.sync().del(ONLINE_PREFIX + serverName);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to cleanup server players: " + e.getMessage());
        }
    }
}


