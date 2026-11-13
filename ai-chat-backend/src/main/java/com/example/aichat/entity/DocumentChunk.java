package com.example.aichat.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 文档分块实体
 * 用于存储文档被切分后的文本块及其向量嵌入信息
 */
@Entity
@Table(name = "document_chunks")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentChunk {

    /**
     * 主键 ID
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 所属知识文档（懒加载关联）
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", nullable = false)
    private KnowledgeDocument document;

    /**
     * 分块文本内容
     */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /**
     * 分块在文档中的序号（从 0 开始）
     */
    @Column(nullable = false)
    private Integer chunkIndex;

    /**
     * 分块元数据（结构化 JSON：chunk_key / section_path / content_hash / 版本 / ACL ...）
     * 原先存的是 Metadata.toString()（非合法 JSON），历史数据由迁移脚本清洗
     */
    @Column(columnDefinition = "TEXT")
    private String metadata;

    /**
     * 向量嵌入维度
     */
    @Column(nullable = false)
    private Integer vectorDimension;

    // ===== 企业级索引可追溯字段（S1 新增，原字段与构造签名保持不变） =====

    /** 向量库条目 id，建立 chunk_id ↔ vector_id 映射 */
    @Column(length = 64)
    private String vectorId;

    /** 父块 chunk_key（父子索引：子块检索、父块生成） */
    @Column(length = 64)
    private String parentId;

    /** 标题 / 条款路径，如 "第二章 报销 > 2.1 差旅" */
    @Column(length = 512)
    private String sectionPath;

    /** sha256(送嵌文本)，用于幂等短路与重复块检测 */
    @Column(length = 64)
    private String contentHash;

    /** 索引版本，灰度重建时切换 */
    @Column(length = 32)
    private String chunkVersion;

    /** 送嵌文本的估算 token 数 */
    private Integer tokenCount;

    /** 逻辑有效标记 */
    private Boolean active;

    /** 预留：租户隔离 */
    @Column(length = 64)
    private String tenantId;

    /** 预留：ACL 标签 */
    @Column(length = 128)
    private String aclTag;

    /** 生成该块向量的嵌入模型名 */
    @Column(length = 128)
    private String embeddingModel;

    /**
     * 创建时间
     */
    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
