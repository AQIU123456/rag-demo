package com.example.aichat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 对话请求 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequest {

    /**
     * 会话 ID，用于关联多轮对话上下文
     */
    private String sessionId;

    /**
     * 用户发送的消息内容
     */
    private String message;
}
