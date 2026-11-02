package com.example.aichat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 关键词高亮响应 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HighlightResponse {

    /**
     * 高亮处理后的文本
     */
    private String highlightedText;
}
