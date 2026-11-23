package com.example.aichat.repository;

import com.example.aichat.entity.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
    
    List<ChatMessage> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    /**
     * 查询指定会话中最后一条 AI 回答，供"重试/重新生成"原地覆盖使用
     */
    ChatMessage findFirstBySessionIdAndRoleOrderByCreatedAtDesc(
            String sessionId, ChatMessage.MessageRole role);

    void deleteBySessionId(String sessionId);
}
