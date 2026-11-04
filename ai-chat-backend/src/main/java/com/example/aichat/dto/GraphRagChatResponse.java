package com.example.aichat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 图 RAG（GraphRAG）问答响应 DTO（POST /api/chat/graph-rag）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphRagChatResponse {

    /**
     * 是否成功
     */
    private boolean success;

    /**
     * 提示信息（失败时返回错误原因）
     */
    private String message;

    /**
     * AI 回答
     */
    private String answer;

    /**
     * 融合后的完整上下文（向量检索片段 + 图谱关系）
     */
    private String context;

    /**
     * 引用来源列表（向量检索命中的文档片段）
     */
    private List<RagChatResponse.CitationDTO> citations;

    /**
     * 从问题中抽取并匹配到的种子实体名称列表
     */
    private List<String> seedEntities;

    /**
     * 图谱关系上下文的文本描述（三元组列表）
     */
    private String graphContext;

    /**
     * 本次检索命中的子图数据（供前端可视化渲染）
     */
    private SubGraphDTO subgraph;

    /**
     * 子图数据 DTO：节点 + 边
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SubGraphDTO {
        /**
         * 节点列表
         */
        private List<GraphNodeDTO> nodes;

        /**
         * 边列表
         */
        private List<GraphEdgeDTO> edges;
    }

    /**
     * 子图节点 DTO
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GraphNodeDTO {
        /**
         * 实体 ID
         */
        private Long id;

        /**
         * 实体名称
         */
        private String name;

        /**
         * 实体类型（PERSON / DEPARTMENT / POLICY 等）
         */
        private String type;

        /**
         * 是否为查询命中的种子实体（前端可高亮显示）
         */
        private boolean seed;
    }

    /**
     * 子图边 DTO
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GraphEdgeDTO {
        /**
         * 起点实体 ID
         */
        private Long sourceId;

        /**
         * 终点实体 ID
         */
        private Long targetId;

        /**
         * 关系类型标签
         */
        private String relation;

        /**
         * 关系权重（抽取出现次数）
         */
        private int weight;
    }
}
