package com.example.aichat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 图 RAG（GraphRAG）问答请求 DTO（POST /api/chat/graph-rag）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphRagChatRequest {

    /**
     * 用户问题（必填）
     */
    private String question;

    /**
     * 向量检索最大返回结果数（为空时默认 5）
     */
    private Integer maxResults;

    /**
     * 是否在响应中返回图谱上下文与子图数据（为空时默认 true）
     */
    private Boolean showGraphContext;
}
