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
     * 分块元数据（结构化 JSON 字符串）
     */
    private String metadata;

    // ===== 纯增量字段：前端不消费也无影响 =====

    /** 标题 / 条款路径 */
    private String sectionPath;

    /** 送嵌文本 sha256 */
    private String contentHash;

    /** 估算 token 数 */
    private Integer tokenCount;

    /** 索引版本 */
    private String chunkVersion;

    /** 父块 chunk_key */
    private String parentId;

    /** 是否超长被标记截断 */
    private Boolean truncated;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;
}
