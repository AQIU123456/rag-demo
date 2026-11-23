package com.example.aichat.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 知识文档实体
 * 用于存储上传到知识库的文档及其元信息
 */
@Entity
@Table(name = "knowledge_documents")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeDocument {

    /**
     * 主键 ID
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 文档标题
     */
    @Column(nullable = false)
    private String title;

    /**
     * 文档正文内容
     */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /**
     * 文档描述（最长 500 字符）
     */
    @Column(length = 500)
    private String description;

    /**
     * 文件类型（如 txt、md、pdf）
     */
    @Column(nullable = false)
    private String fileType;

    /**
     * 文件大小（字节）
     */
    @Column(nullable = false)
    private Long fileSize;

    /**
     * 文档字数统计
     */
    @Column(nullable = false)
    private Integer wordCount;

    // ==================== 企业级上传元数据（非侵入式新增，均为可空） ====================

    /** 原始文件名（保留用户上传时的名字，title 可能被清洗） */
    @Column(length = 512)
    private String originalFilename;

    /** 真实 MIME 类型（由魔数嗅探得出，不信任扩展名） */
    @Column(length = 128)
    private String mimeType;

    /** 文件 sha256（秒传去重 + 内容溯源） */
    @Column(length = 64)
    private String fileHash;

    /** 原文件存储路径（相对 storage.root，支持重新解析/下载） */
    @Column(length = 1024)
    private String storagePath;

    /** 文档版本（同 hash 多版本可回溯） */
    @Column(length = 32)
    private String version;

    /** 上传来源：file-upload / quick-entry / api */
    @Column(length = 32)
    private String source;

    /** 上传者（无身份上下文时为 anonymous） */
    @Column(length = 128)
    private String uploadedBy;

    /** 租户 ID（无身份上下文时为 default） */
    @Column(length = 64)
    private String tenantId;

    /** 解析状态：SUCCESS / FAILED / TEXT_ONLY */
    @Column(length = 32)
    private String parseStatus;

    /** 创建时间
     */
    @Column(nullable = false)
    private LocalDateTime createdAt;

    /**
     * 最后更新时间
     */
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (updatedAt == null) {
            updatedAt = LocalDateTime.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
