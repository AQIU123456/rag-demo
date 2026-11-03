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
     * 分块元数据（JSON 字符串，如标题层级、来源位置等）
     */
    @Column(length = 1000)
    private String metadata;

    /**
     * 向量嵌入维度
     */
    @Column(nullable = false)
    private Integer vectorDimension;

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
