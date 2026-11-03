package com.example.aichat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 文档分块 DTO
 * 用于前后端传输分块数据
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentChunkDTO {

    /**
     * 分块 ID
     */
    private Long id;

    /**
     * 所属文档 ID
     */
    private Long documentId;

    /**
     * 所属文档标题
     */
    private String documentTitle;

    /**
     * 分块文本内容
     */
    private String content;

    /**
     * 分块在文档中的序号（从 0 开始）
     */
    private Integer chunkIndex;

    /**
     * 分块元数据（JSON 字符串）
     */
    private String metadata;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;
}
