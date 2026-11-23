package com.example.aichat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 知识文档响应 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentResponse {

    /**
     * 文档 ID
     */
    private Long id;

    /**
     * 文档标题
     */
    private String title;

    /**
     * 文档描述
     */
    private String description;

    /**
     * 文件类型（如 txt、md、pdf）
     */
    private String fileType;

    /**
     * 文件大小（字节）
     */
    private Long fileSize;

    /**
     * 文档字数统计
     */
    private Integer wordCount;

    /**
     * 文档正文内容
     */
    private String content;

    // ==================== 企业级上传元数据（非侵入式新增） ====================
    private String originalFilename;
    private String mimeType;
    private String fileHash;
    private String version;
    private String source;
    private String uploadedBy;
    private String tenantId;
    private String parseStatus;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 最后更新时间
     */
    private LocalDateTime updatedAt;
}
