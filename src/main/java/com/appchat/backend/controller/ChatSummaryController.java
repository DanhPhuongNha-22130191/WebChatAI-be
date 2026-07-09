package com.appchat.backend.controller;

import com.appchat.backend.dto.ChatSummaryDto;
import com.appchat.backend.service.ChatSummaryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/chat/summary")
@RequiredArgsConstructor
public class ChatSummaryController {

    private final ChatSummaryService chatSummaryService;

    @GetMapping
    public ResponseEntity<Map<String, Object>> summarizeChat(
            @RequestParam(defaultValue = "room") String type,
            @RequestParam String target,
            @RequestParam(defaultValue = "latest") String period,
            @RequestParam(defaultValue = "general") String mode,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "false") boolean force
    ) {
        try {
            String currentUsername = getCurrentUsername();

            ChatSummaryDto summary = chatSummaryService.summarizeChat(
                    currentUsername,
                    type,
                    target,
                    period,
                    mode,
                    limit,
                    from,
                    to,
                    force
            );

            return ResponseEntity.ok(
                    response(
                            "success",
                            "CHAT_SUMMARY_SUCCESS",
                            "Tạo tóm tắt thành công.",
                            summary
                    )
            );
        } catch (AccessDeniedException ex) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    response(
                            "error",
                            "CHAT_SUMMARY_FORBIDDEN",
                            ex.getMessage(),
                            null
                    )
            );
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(
                    response(
                            "error",
                            "CHAT_SUMMARY_BAD_REQUEST",
                            ex.getMessage(),
                            null
                    )
            );
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    response(
                            "error",
                            "CHAT_SUMMARY_ERROR",
                            ex.getMessage(),
                            null
                    )
            );
        }
    }

    private String getCurrentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || authentication.getName() == null || authentication.getName().isBlank()) {
            throw new AccessDeniedException("Bạn chưa đăng nhập.");
        }

        return authentication.getName();
    }

    private Map<String, Object> response(String status, String event, String message, Object data) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("status", status);
        map.put("event", event);
        map.put("mes", message);
        map.put("data", data);
        return map;
    }
}