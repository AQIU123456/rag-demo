package com.example.aichat.domain.chunk;

import dev.langchain4j.data.document.Metadata;
import lombok.Builder;
import lombok.Data;

/**
 * 检索命中结果。相比裸 {@code EmbeddingMatch} 多了混合召回的排序信息与
 * 引用溯源字段，供 EmbeddingService 组装 SearchResult。
 */
@Data
@Builder
public class ChunkHit {

    private String vectorId;
    /** 命中块原文（用于引用展示） */
    private String content;
    /** 送 LLM 的上下文（父块 / 相邻块扩展后可能比 content 长） */
    private String contextText;
    private Metadata metadata;

    private double vectorScore;
    private double bm25Score;
    /** RRF 融合分；未启用混合召回时等于向量分 */
    private double fusedScore;
    private int vectorRank;
    private int keywordRank;
}
