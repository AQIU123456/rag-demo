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
 * 知识图谱实体节点
 * 由文档抽取或问答过程中的 LLM 抽取产生，name 全局唯一（大小写不敏感）
 */
@Entity
@Table(name = "graph_entity",
        uniqueConstraints = @UniqueConstraint(name = "uk_graph_entity_name", columnNames = "name"),
        indexes = @Index(name = "idx_graph_entity_name", columnList = "name"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphEntity {

    /**
     * 主键 ID
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 实体名称（如：张伟、研发部、年假制度），保存时统一去除首尾空白
     */
    @Column(nullable = false, length = 200, unique = true)
    private String name;

    /**
     * 实体类型（如：PERSON / DEPARTMENT / POLICY / PROJECT，未知时为 OTHER）
     */
    @Column(length = 50)
    private String entityType;

    /**
     * 实体描述（来自抽取时的补充说明，可为空）
     */
    @Column(length = 1000)
    private String description;

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
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
