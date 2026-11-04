package com.example.aichat.repository.graph;

import com.example.aichat.entity.graph.GraphEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 知识图谱实体仓储
 */
public interface GraphEntityRepository extends JpaRepository<GraphEntity, Long> {

    /**
     * 按名称精确查找实体（大小写不敏感）
     */
    Optional<GraphEntity> findFirstByNameIgnoreCase(String name);

    /**
     * 查找名称中包含指定关键词的实体（用于问题 -> 种子实体匹配，大小写不敏感）
     */
    @Query("SELECT e FROM GraphEntity e WHERE LOWER(e.name) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
           "OR LOWER(:keyword) LIKE LOWER(CONCAT('%', e.name, '%'))")
    List<GraphEntity> findByNameContainingKeyword(@Param("keyword") String keyword);

    /**
     * 统计所有实体数量
     */
    long countByEntityTypeIsNotNull();
}
