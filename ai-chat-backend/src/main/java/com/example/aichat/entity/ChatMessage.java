package com.example.aichat.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 聊天消息实体
 * 用于持久化用户与 AI 之间的多轮对话记录
 */
@Entity
@Table(name = "chat_messages")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessage {

    /**
     * 主键 ID
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 会话 ID，用于关联同一会话的多条消息
     */
    @Column(nullable = false)
    private String sessionId;

    /**
     * 消息内容（最长 2000 字符）
     */
    @Column(nullable = false, length = 2000)
    private String content;

    /**
     * 消息角色：USER（用户）/ ASSISTANT（AI 助手）
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MessageRole role;

    /**
     * 消息创建时间
     */
    @Column(nullable = false)
    private LocalDateTime createdAt;

    /**
     * 消息角色枚举
     */
    public enum MessageRole {
        /**
         * 用户发送的消息
         */
        USER,
        /**
         * AI 助手回复的消息
         */
        ASSISTANT
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
