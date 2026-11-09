package com.example.aichat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 图谱构建请求 DTO（POST /api/graph/build）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphBuildRequest {

    /**
     * 仅处理指定文档 ID；为空时处理知识库全部文档
     */
    private Long documentId;

    /**
     * 是否清空现有图谱后重建（默认 false，增量合并）
     */
    private Boolean clearExisting;
}
