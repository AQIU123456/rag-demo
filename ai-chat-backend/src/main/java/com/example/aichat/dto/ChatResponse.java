package com.example.aichat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatResponse {
    
    private String sessionId;
    private String message;
    private LocalDateTime timestamp;
    private List<ToolCallInfo> toolCalls;
    
    /**
     * 工具调用信息 DTO
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToolCallInfo {
        private String name;
        private String description;
        private Object arguments;
        private Object result;
    }
}
