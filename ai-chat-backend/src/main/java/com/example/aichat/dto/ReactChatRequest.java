package com.example.aichat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * ReAct 智能问答请求 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReactChatRequest {

    /**
     * 用户问题
     */
    private String question;

    /**
     * 最大检索结果数，默认 3
     */
    @Builder.Default
    private Integer maxResults = 3;

    /**
     * 是否返回推理过程，默认 true
     */
    @Builder.Default
    private Boolean showReasoning = true;
}
