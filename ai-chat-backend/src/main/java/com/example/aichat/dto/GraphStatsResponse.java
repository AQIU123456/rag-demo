package com.example.aichat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 图谱统计信息响应 DTO（GET /api/graph/stats）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphStatsResponse {

    /**
     * 是否成功
     */
    private boolean success;

    /**
     * 提示信息（失败时返回错误原因）
     */
    private String message;

    /**
     * 实体总数
     */
    private long entityCount;

    /**
     * 关系总数
     */
    private long relationCount;

    /**
     * 平均度数（关系数 * 2 / 实体数，反映图的连通密度）
     */
    private double averageDegree;
}
