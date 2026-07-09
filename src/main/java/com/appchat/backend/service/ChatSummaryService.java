package com.appchat.backend.service;

import com.appchat.backend.dto.ChatSummaryDto;
import com.appchat.backend.entity.Message;
import com.appchat.backend.repository.MessageRepository;
import com.appchat.backend.repository.RoomMemberRepository;
import com.appchat.backend.repository.RoomRepository;
import com.appchat.backend.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ChatSummaryService {

    private final MessageRepository messageRepository;
    private final RoomMemberRepository roomMemberRepository;
    private final RoomRepository roomRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    @Value("${app.ai.gemini.api-key:}")
    private String geminiApiKey;

    @Value("${app.ai.gemini.model:gemini-2.5-flash-lite}")
    private String geminiModel;

    @Value("${app.ai.gemini.url:https://generativelanguage.googleapis.com/v1beta/models}")
    private String geminiBaseUrl;

    public ChatSummaryDto summarizeChat(
            String currentUsername,
            String rawType,
            String target,
            String rawPeriod,
            String rawMode,
            int limit,
            String from,
            String to,
            boolean force
    ) throws Exception {
        String type = normalizeType(rawType);
        String normalizedTarget = target == null ? "" : target.trim();
        int safeLimit = normalizeLimit(limit);

        if (normalizedTarget.isBlank()) {
            throw new IllegalArgumentException("Thiếu tên cuộc trò chuyện cần tóm tắt.");
        }

        List<Message> messages = loadLatestMessages(currentUsername, type, normalizedTarget, safeLimit);

        if (messages.isEmpty()) {
            return ChatSummaryDto.builder()
                    .type(type)
                    .target(normalizedTarget)
                    .messageCount(0)
                    .summary("Cuộc trò chuyện này chưa có tin nhắn để tóm tắt.")
                    .build();
        }

        String transcript = buildTranscript(messages);
        String prompt = buildPrompt(type, normalizedTarget, messages.size(), transcript);
        String summary = callGemini(prompt);

        return ChatSummaryDto.builder()
                .type(type)
                .target(normalizedTarget)
                .messageCount(messages.size())
                .summary(summary)
                .build();
    }

    private List<Message> loadLatestMessages(String currentUsername, String type, String target, int limit) {
        List<Message> latestMessages;

        if ("people".equals(type)) {
            if (!currentUsername.equals(target) && userRepository.findByUsername(target).isEmpty()) {
                throw new IllegalArgumentException("Người dùng không tồn tại.");
            }

            latestMessages = messageRepository.findPeopleMessages(
                    currentUsername,
                    target,
                    PageRequest.of(0, limit)
            );
        } else {
            if (roomRepository.findByName(target).isEmpty()) {
                throw new IllegalArgumentException("Nhóm chat không tồn tại.");
            }

            if (!roomMemberRepository.existsByRoomNameAndUsername(target, currentUsername)) {
                throw new AccessDeniedException("Bạn không thuộc nhóm chat này.");
            }

            latestMessages = messageRepository.findRoomMessages(
                    target,
                    PageRequest.of(0, limit)
            );
        }

        List<Message> chronologicalMessages = new ArrayList<>(latestMessages);
        java.util.Collections.reverse(chronologicalMessages);
        return chronologicalMessages;
    }

    private String buildTranscript(List<Message> messages) {
        StringBuilder builder = new StringBuilder();

        for (Message message : messages) {
            String sender = message.getSender() == null ? "Không rõ" : message.getSender();
            String content = normalizeMessageContent(message);

            if (content.isBlank()) {
                continue;
            }

            builder.append(sender)
                    .append(": ")
                    .append(truncate(content, 700))
                    .append('\n');

            if (builder.length() > 18000) {
                builder.append("...\n");
                break;
            }
        }

        return builder.toString().trim();
    }

    private String normalizeMessageContent(Message message) {
        if (Boolean.TRUE.equals(message.getRecalled())) {
            return "[Tin nhắn đã được thu hồi]";
        }

        String content = message.getContent() == null ? "" : message.getContent().trim();

        if (content.startsWith("[IMAGE]")) {
            return "[Đã gửi một hình ảnh]";
        }

        if (content.startsWith("[VIDEO]")) {
            return "[Đã gửi một video]";
        }

        if (content.startsWith("[FILE]")) {
            String fileContent = content.replaceFirst("^\\[FILE]", "");
            String[] parts = fileContent.split("\\|");
            String fileName = parts.length >= 2 ? parts[1] : "tệp đính kèm";
            return "[Đã gửi file: " + fileName + "]";
        }

        if (content.startsWith("[STICKER:")) {
            return "[Đã gửi sticker]";
        }

        return content;
    }

    private String buildPrompt(String type, String target, int messageCount, String transcript) {
        String conversationType = "room".equals(type) ? "chat nhóm" : "chat 1-1";

        return """
                Bạn là trợ lý AI trong ứng dụng web chat.
                Hãy tóm tắt cuộc trò chuyện bằng tiếng Việt, ngắn gọn, dễ hiểu.

                Yêu cầu:
                - Tóm tắt nội dung chính của cuộc trò chuyện.
                - Nếu có việc cần làm, hãy ghi rõ ai cần làm gì.
                - Nếu có quyết định/thống nhất, hãy liệt kê rõ.
                - Không bịa thêm thông tin ngoài tin nhắn.
                - Nếu nội dung chỉ là trò chuyện xã giao, hãy nói ngắn gọn là chưa có nội dung quan trọng.

                Thông tin cuộc trò chuyện:
                - Loại: %s
                - Tên/người nhận: %s
                - Số tin nhắn dùng để tóm tắt: %d

                Tin nhắn:
                %s
                """.formatted(conversationType, target, messageCount, transcript);
    }

    private String callGemini(String prompt) throws Exception {
        if (geminiApiKey == null || geminiApiKey.isBlank()) {
            throw new IllegalStateException("Chưa cấu hình GEMINI_API_KEY cho backend.");
        }

        Map<String, Object> textPart = new LinkedHashMap<>();
        textPart.put("text", prompt);

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("role", "user");
        content.put("parts", List.of(textPart));

        Map<String, Object> generationConfig = new LinkedHashMap<>();
        generationConfig.put("temperature", 0.3);
        generationConfig.put("maxOutputTokens", 500);

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("contents", List.of(content));
        requestBody.put("generationConfig", generationConfig);

        String jsonBody = objectMapper.writeValueAsString(requestBody);
        String endpoint = buildGeminiEndpoint();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(45))
                .header("Content-Type", "application/json")
                .header("X-goog-api-key", geminiApiKey)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(
                    "Gemini API lỗi: HTTP " + response.statusCode() + " - " + extractGeminiError(response.body())
            );
        }

        return extractGeminiSummaryText(response.body());
    }

    private String buildGeminiEndpoint() {
        String baseUrl = geminiBaseUrl == null || geminiBaseUrl.isBlank()
                ? "https://generativelanguage.googleapis.com/v1beta/models"
                : geminiBaseUrl.trim();

        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        return baseUrl + "/" + geminiModel + ":generateContent";
    }

    private String extractGeminiSummaryText(String responseBody) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode candidates = root.path("candidates");

        if (candidates.isArray()) {
            StringBuilder builder = new StringBuilder();

            for (JsonNode candidate : candidates) {
                JsonNode parts = candidate.path("content").path("parts");
                if (!parts.isArray()) continue;

                for (JsonNode part : parts) {
                    JsonNode textNode = part.path("text");
                    if (textNode.isTextual() && !textNode.asText().isBlank()) {
                        builder.append(textNode.asText()).append('\n');
                    }
                }
            }

            String text = builder.toString().trim();
            if (!text.isBlank()) {
                return text;
            }
        }

        JsonNode blockReason = root.path("promptFeedback").path("blockReason");
        if (blockReason.isTextual() && !blockReason.asText().isBlank()) {
            throw new IllegalStateException("Gemini không tạo tóm tắt vì nội dung bị chặn: " + blockReason.asText());
        }

        throw new IllegalStateException("Không đọc được nội dung tóm tắt từ Gemini API.");
    }

    private String extractGeminiError(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return "Không có nội dung lỗi từ Gemini.";
        }

        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode message = root.path("error").path("message");
            if (message.isTextual() && !message.asText().isBlank()) {
                return message.asText();
            }
        } catch (Exception ignored) {
        }

        return truncate(responseBody, 500);
    }

    private String normalizeType(String rawType) {
        String type = rawType == null ? "" : rawType.trim().toLowerCase(Locale.ROOT);

        if ("0".equals(type) || "people".equals(type) || "private".equals(type)) {
            return "people";
        }

        if ("1".equals(type) || "room".equals(type) || "group".equals(type)) {
            return "room";
        }

        throw new IllegalArgumentException("Loại cuộc trò chuyện không hợp lệ.");
    }

    private int normalizeLimit(int limit) {
        if (limit <= 0) return 100;
        return Math.min(limit, 100);
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "...";
    }
}
