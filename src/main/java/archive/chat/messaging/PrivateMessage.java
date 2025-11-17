package archive.chat.messaging;

import com.google.gson.Gson;
import java.util.UUID;

public record PrivateMessage(
    UUID senderUUID,
    String senderName,
    String senderServer,
    String recipientName,
    String message
) {
    private static final Gson GSON = new Gson();

    public String toJson() {
        return GSON.toJson(this);
    }

    public static PrivateMessage fromJson(String json) {
        return GSON.fromJson(json, PrivateMessage.class);
    }
}
