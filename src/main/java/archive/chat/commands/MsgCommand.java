package archive.chat.commands;

import archive.chat.ArchiveChat;
import archive.chat.messaging.MessageService;
import archive.chat.messaging.VanishManager;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Brigadier command handler for /msg and all its aliases.
 * Each command is registered as a separate literal to override vanilla commands.
 */
public class MsgCommand {
    private final ArchiveChat plugin;
    private final MessageService messageService;

    public MsgCommand(ArchiveChat plugin, MessageService messageService) {
        this.plugin = plugin;
        this.messageService = messageService;
    }

    /**
     * Registers the /msg command and all its aliases using Paper's Commands API
     */
    public void register(Commands registrar) {
        // Register all command variants as literals to override vanilla commands
        String[] commandNames = {"msg", "w", "whisper", "tell", "pm"};

        for (String commandName : commandNames) {
            registrar.register(
                Commands.literal(commandName)
                    .requires(source -> source.getSender().hasPermission("archivechat.msg"))
                    .then(Commands.argument("player", StringArgumentType.word())
                        .suggests((context, builder) -> {
                            // Suggest online player names (excluding sender and vanished players)
                            var sender = context.getSource().getSender();
                            Bukkit.getOnlinePlayers().forEach(player -> {
                                // Don't suggest sender's own name
                                if (sender instanceof Player senderPlayer &&
                                    player.getUniqueId().equals(senderPlayer.getUniqueId())) {
                                    return; // Skip
                                }
                                // Don't suggest vanished players (unless sender can see them)
                                if (sender instanceof Player senderPlayer &&
                                    !VanishManager.canSee(senderPlayer, player)) {
                                    return; // Skip
                                }
                                builder.suggest(player.getName());
                            });
                            return builder.buildFuture();
                        })
                        .then(Commands.argument("message", StringArgumentType.greedyString())
                            .executes(this::execute)
                        )
                    )
                    .build(),
                "Send a private message to another player"
            );
        }
    }

    /**
     * Executes the message command
     */
    private int execute(CommandContext<CommandSourceStack> context) {
        var source = context.getSource();

        if (!(source.getExecutor() instanceof Player player)) {
            source.getSender().sendPlainMessage("Only players can use this command");
            return 0;
        }

        String recipientName = StringArgumentType.getString(context, "player");
        String message = StringArgumentType.getString(context, "message");

        if (message.isBlank()) {
            player.sendMessage(MiniMessage.miniMessage().deserialize(
                plugin.getConfig().getString("messages.empty-message", "<red>Message cannot be empty")
            ));
            return 0;
        }

        if (recipientName.equalsIgnoreCase(player.getName())) {
            player.sendMessage(MiniMessage.miniMessage().deserialize(
                plugin.getConfig().getString("messages.cannot-message-self", "<red>You cannot message yourself")
            ));
            return 0;
        }

        messageService.sendPrivateMessage(player, recipientName, message);
        return 1;
    }
}
