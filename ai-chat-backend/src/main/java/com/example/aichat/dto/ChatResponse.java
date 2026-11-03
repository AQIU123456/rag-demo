package com.example.aichat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 对话响应 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatResponse {

    /**
     * 会话 ID
     */
    private String sessionId;

    /**
     * AI 回复的消息内容
     */
    private String message;

    /**
     * 响应生成时间
     */
    private LocalDateTime timestamp;

    /**
     * 本轮对话中触发的工具调用列表（无工具调用时为 null）
     */
    private List<ToolCallInfo> toolCalls;

    /**
     * 工具调用信息 DTO
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToolCallInfo {
        /**
         * 工具名称
         */
        private String name;

        /**
         * 工具描述
         */
        private String description;

        /**
         * 工具调用参数
         */
        private Object arguments;

        /**
         * 工具调用返回结果
         */
        private Object result;
    }
}
