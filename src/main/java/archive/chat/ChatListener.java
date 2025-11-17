package archive.chat;

import archive.chat.messaging.ChatMessage;
import archive.chat.redis.RedisManager;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

public class ChatListener implements Listener {
    private final RedisManager redisManager;
    private final String serverName;

    public ChatListener(RedisManager redisManager, String serverName) {
        this.redisManager = redisManager;
        this.serverName = serverName;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        var player = event.getPlayer();
        var message = PlainTextComponentSerializer.plainText().serialize(event.message());

        var chatMessage = new ChatMessage(
            player.getName(),
            serverName,
            message
        );

        redisManager.sendChatMessage(chatMessage);
    }
}
