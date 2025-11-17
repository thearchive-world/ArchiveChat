package archive.chat.messaging;

import archive.chat.ArchiveChat;
import archive.chat.redis.RedisManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class MessageService implements Listener {
    private final ArchiveChat plugin;
    private final RedisManager redis;
    private final Map<UUID, TargetInfo> replyTargets = new ConcurrentHashMap<>();
    private final Map<UUID, TargetInfo> lastSentTargets = new ConcurrentHashMap<>();
    private final String serverName;

    // Message formats from config
    private final String sentFormat;
    private final String receivedFormat;

    public MessageService(ArchiveChat plugin, RedisManager redis) {
        this.plugin = plugin;
        this.redis = redis;
        this.serverName = plugin.getConfig().getString("server-name", "server1");
        this.sentFormat = plugin.getConfig().getString("formats.sent",
            "<gray>[<white>You <gray>-> <white><recipient><gray>] <message>");
        this.receivedFormat = plugin.getConfig().getString("formats.received",
            "<gray>[<white><sender> <gray>-> <white>You<gray>] <message>");
    }

    public void sendPrivateMessage(Player sender, String recipientName, String message) {
        // Check if player is online locally
        Player localRecipient = Bukkit.getPlayerExact(recipientName);

        if (localRecipient != null) {
            // Local delivery
            deliverMessage(sender, localRecipient, message);
        } else if (redis != null && redis.isConnected()) {
            // Check if player is online on any server before sending
            if (!redis.isPlayerOnlineAnywhere(recipientName)) {
                sender.sendMessage(MiniMessage.miniMessage().deserialize(
                    plugin.getConfig().getString("messages.player-not-found", "<red>Player not found")
                ));
                return;
            }

            // Cross-server delivery via Redis
            redis.sendCrossServerMessage(new PrivateMessage(
                sender.getUniqueId(),
                sender.getName(),
                serverName,
                recipientName,
                message
            ));

            // Update last sent target for cross-server (store name only since UUID is unknown)
            lastSentTargets.put(sender.getUniqueId(), TargetInfo.crossServer(recipientName));

            // Show "sent" message to sender
            showSentMessage(sender, recipientName, message);

            plugin.getLogger().fine("Cross-server message sent: " + sender.getName() + " -> " + recipientName);
        } else {
            // No Redis, player not found locally
            sender.sendMessage(MiniMessage.miniMessage().deserialize(
                plugin.getConfig().getString("messages.player-not-found", "<red>Player not found")
            ));
        }
    }

    private void deliverMessage(Player sender, Player recipient, String message) {
        // Update reply targets (both directions for local delivery)
        replyTargets.put(recipient.getUniqueId(), new TargetInfo(sender.getUniqueId(), sender.getName()));
        replyTargets.put(sender.getUniqueId(), new TargetInfo(recipient.getUniqueId(), recipient.getName()));

        // Update last sent target
        lastSentTargets.put(sender.getUniqueId(), new TargetInfo(recipient.getUniqueId(), recipient.getName()));

        // Escape user input to prevent MiniMessage injection
        var mm = MiniMessage.miniMessage();
        String escapedRecipient = mm.escapeTags(recipient.getName());
        String escapedSender = mm.escapeTags(sender.getName());
        String escapedMessage = mm.escapeTags(message);

        // Format and send messages using MiniMessage
        Component sentMsg = mm.deserialize(
            sentFormat.replace("<recipient>", escapedRecipient)
                      .replace("<message>", escapedMessage)
        );
        Component receivedMsg = mm.deserialize(
            receivedFormat.replace("<sender>", escapedSender)
                          .replace("<message>", escapedMessage)
        );

        sender.sendMessage(sentMsg);
        recipient.sendMessage(receivedMsg);
    }

    public void handleIncomingMessage(PrivateMessage msg) {
        Player recipient = Bukkit.getPlayerExact(msg.recipientName());
        if (recipient == null) return;

        // Update reply target (cross-server - store sender info with name for cross-server reply)
        replyTargets.put(recipient.getUniqueId(), new TargetInfo(msg.senderUUID(), msg.senderName()));

        // Escape user input to prevent MiniMessage injection
        var mm = MiniMessage.miniMessage();
        String escapedSender = mm.escapeTags(msg.senderName());
        String escapedMessage = mm.escapeTags(msg.message());

        Component receivedMsg = mm.deserialize(
            receivedFormat.replace("<sender>", escapedSender)
                          .replace("<message>", escapedMessage)
        );
        recipient.sendMessage(receivedMsg);
    }

    public TargetInfo getReplyTarget(UUID playerUUID) {
        return replyTargets.get(playerUUID);
    }

    public TargetInfo getLastSentTarget(UUID playerUUID) {
        return lastSentTargets.get(playerUUID);
    }

    public void showSentMessage(Player sender, String recipientName, String message) {
        // Escape user input to prevent MiniMessage injection
        var mm = MiniMessage.miniMessage();
        String escapedRecipient = mm.escapeTags(recipientName);
        String escapedMessage = mm.escapeTags(message);

        Component sentMsg = mm.deserialize(
            sentFormat.replace("<recipient>", escapedRecipient)
                      .replace("<message>", escapedMessage)
        );
        sender.sendMessage(sentMsg);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerUUID = event.getPlayer().getUniqueId();
        replyTargets.remove(playerUUID);
        lastSentTargets.remove(playerUUID);
    }
}
