package com.example.aichat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * RAG 智能问答请求 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagChatRequest {

    /**
     * 用户问题
     */
    private String question;

    /**
     * 最大检索结果数，默认 5
     */
    @Builder.Default
    private Integer maxResults = 5;
}
