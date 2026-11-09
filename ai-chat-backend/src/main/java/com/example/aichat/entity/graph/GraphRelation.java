package com.example.aichat.entity.graph;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 知识图谱关系边（有向三元组：sourceEntity -[relationType]-> targetEntity）
 * 同一对实体 + 同一关系类型只保留一条记录，出现次数通过 weight 累加
 */
@Entity
@Table(name = "graph_relation",
        uniqueConstraints = @UniqueConstraint(name = "uk_graph_relation_triple",
                columnNames = {"source_entity_id", "target_entity_id", "relation_type"}),
        indexes = {
                @Index(name = "idx_graph_relation_source", columnList = "source_entity_id"),
                @Index(name = "idx_graph_relation_target", columnList = "target_entity_id")
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphRelation {

    /**
     * 主键 ID
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 起点实体 ID（GraphEntity.id）
     */
    @Column(name = "source_entity_id", nullable = false)
    private Long sourceEntityId;

    /**
     * 终点实体 ID（GraphEntity.id）
     */
    @Column(name = "target_entity_id", nullable = false)
    private Long targetEntityId;

    /**
     * 关系类型（如：属于、负责、适用于），保存时统一去除首尾空白
     */
    @Column(nullable = false, length = 200)
    private String relationType;

    /**
     * 关系权重（同一条关系被不同文段重复抽取的次数，用于排序优先级）
     */
    @Column(nullable = false)
    private Integer weight;

    /**
     * 来源文档 ID（可空，标识该关系首次/最近一次被抽取自哪个知识库文档）
     */
    @Column(name = "source_document_id")
    private Long sourceDocumentId;

    /**
     * 创建时间
     */
    @Column(updatable = false)
    private LocalDateTime createdAt;

    /**
     * 最后更新时间
     */
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
        if (weight == null || weight < 1) {
            weight = 1;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
