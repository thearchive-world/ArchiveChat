package archive.chat.messaging;

import com.google.gson.Gson;

public record ChatMessage(
    String senderName,
    String senderServer,
    String message
) {
    private static final Gson GSON = new Gson();

    public String toJson() {
        return GSON.toJson(this);
    }

    public static ChatMessage fromJson(String json) {
        return GSON.fromJson(json, ChatMessage.class);
    }
}
