package com.example.aichat.repository.graph;

import com.example.aichat.entity.graph.GraphRelation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 知识图谱关系仓储
 */
public interface GraphRelationRepository extends JpaRepository<GraphRelation, Long> {

    /**
     * 查找以指定实体为起点或终点的邻居关系（用于一跳扩展）
     */
    @Query("SELECT r FROM GraphRelation r WHERE r.sourceEntityId = :entityId OR r.targetEntityId = :entityId " +
           "ORDER BY r.weight DESC")
    List<GraphRelation> findNeighborRelations(@Param("entityId") Long entityId);

    /**
     * 按三元组精确查找关系（用于去重累加权重）
     */
    Optional<GraphRelation> findFirstBySourceEntityIdAndTargetEntityIdAndRelationType(
            Long sourceEntityId, Long targetEntityId, String relationType);

    /**
     * 批量查询多个实体的邻居关系（种子实体集合一次查出，减少数据库往返）
     */
    @Query("SELECT r FROM GraphRelation r WHERE r.sourceEntityId IN :entityIds OR r.targetEntityId IN :entityIds " +
           "ORDER BY r.weight DESC")
    List<GraphRelation> findRelationsForEntities(@Param("entityIds") List<Long> entityIds);
}
