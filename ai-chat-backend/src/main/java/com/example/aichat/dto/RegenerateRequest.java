package com.example.aichat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 重新生成（重试）请求 DTO
 * 复用已持久化的用户问题，仅重新生成对应的 AI 回答，不重复插入用户消息。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegenerateRequest {

    /**
     * 会话 ID，用于定位需要覆盖的最后一条 AI 回答
     */
    private String sessionId;

    /**
     * 需要重新回答的原问题
     */
    private String question;
}
