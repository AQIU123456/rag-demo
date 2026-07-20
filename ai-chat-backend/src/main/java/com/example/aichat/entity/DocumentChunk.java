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

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", nullable = false)
    private KnowledgeDocument document;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(nullable = false)
    private Integer chunkIndex;

    @Column(length = 1000)
    private String metadata;

    @Column(nullable = false)
    private Integer vectorDimension;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
