package archive.chat.commands;

import archive.chat.ArchiveChat;
import archive.chat.messaging.MessageService;
import archive.chat.messaging.TargetInfo;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Brigadier command handler for /reply and its alias /r.
 * Allows players to reply to the last person who messaged them.
 */
public class ReplyCommand {
    private final ArchiveChat plugin;
    private final MessageService messageService;

    public ReplyCommand(ArchiveChat plugin, MessageService messageService) {
        this.plugin = plugin;
        this.messageService = messageService;
    }

    /**
     * Registers the /reply command and /r alias using Paper's Commands API
     */
    public void register(Commands registrar) {
        // Register both /reply and /r
        String[] commandNames = {"reply", "r"};

        for (String commandName : commandNames) {
            registrar.register(
                Commands.literal(commandName)
                    .requires(source -> source.getSender().hasPermission("archivechat.reply"))
                    .then(Commands.argument("message", StringArgumentType.greedyString())
                        .executes(this::execute)
                    )
                    .build(),
                "Reply to the last person who messaged you"
            );
        }
    }

    /**
     * Executes the reply command
     */
    private int execute(CommandContext<CommandSourceStack> context) {
        var source = context.getSource();

        if (!(source.getExecutor() instanceof Player player)) {
            source.getSender().sendPlainMessage("Only players can use this command");
            return 0;
        }

        TargetInfo targetInfo = messageService.getReplyTarget(player.getUniqueId());
        if (targetInfo == null) {
            player.sendMessage(MiniMessage.miniMessage().deserialize(
                plugin.getConfig().getString("messages.no-reply-target", "<red>No one to reply to")
            ));
            return 0;
        }

        String message = StringArgumentType.getString(context, "message");

        if (message.isBlank()) {
            player.sendMessage(MiniMessage.miniMessage().deserialize(
                plugin.getConfig().getString("messages.empty-message", "<red>Message cannot be empty")
            ));
            return 0;
        }

        // Try to find target player locally first
        Player target = targetInfo.uuid() != null ? Bukkit.getPlayer(targetInfo.uuid()) : null;

        if (target != null) {
            // Target is online locally
            messageService.sendPrivateMessage(player, target.getName(), message);
            return 1;
        } else if (targetInfo.name() != null) {
            // Target is on another server - send cross-server message by name
            messageService.sendPrivateMessage(player, targetInfo.name(), message);
            return 1;
        } else {
            player.sendMessage(MiniMessage.miniMessage().deserialize(
                plugin.getConfig().getString("messages.player-offline", "<red>Player is no longer online")
            ));
            return 0;
        }
    }
}
