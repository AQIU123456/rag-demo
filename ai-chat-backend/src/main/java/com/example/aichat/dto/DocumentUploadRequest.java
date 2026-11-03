package com.example.aichat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文档上传请求 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentUploadRequest {

    /**
     * 文档标题
     */
    private String title;

    /**
     * 文档描述（可选）
     */
    private String description;

    /**
     * 文档正文内容
     */
    private String content;

    /**
     * 文件类型（如 txt、md）
     */
    private String fileType;
}
