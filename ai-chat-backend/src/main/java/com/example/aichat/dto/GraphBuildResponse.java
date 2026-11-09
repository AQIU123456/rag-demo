package com.example.aichat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 图谱构建响应 DTO（POST /api/graph/build）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphBuildResponse {

    /**
     * 是否成功
     */
    private boolean success;

    /**
     * 提示信息（失败时返回错误原因）
     */
    private String message;

    /**
     * 本次处理的文档数量
     */
    private int processedDocuments;

    /**
     * 本次新增/更新的实体数量
     */
    private int entityCount;

    /**
     * 本次新增/更新的关系数量
     */
    private int relationCount;

    /**
     * 构建耗时（毫秒）
     */
    private long elapsedMs;
}
