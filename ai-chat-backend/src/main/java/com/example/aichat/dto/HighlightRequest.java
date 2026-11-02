package com.example.aichat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 关键词高亮请求 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HighlightRequest {

    /**
     * 待高亮的文本
     */
    private String text;

    /**
     * 关键词（多个以空白分隔）
     */
    private String keywords;

    /**
     * 高亮标签，默认 <em>
     */
    @Builder.Default
    private String highlightTag = "<em>";
}
