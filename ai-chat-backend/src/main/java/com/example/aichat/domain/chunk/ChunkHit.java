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

    /** 向量库条目 id，用于回查 liveIndex 做上下文扩展 */
    private String vectorId;
    /** 命中块原文（用于引用展示） */
    private String content;
    /** 送 LLM 的上下文（父块 / 相邻块扩展后可能比 content 长） */
    private String contextText;
    /** 向量条目元数据（doc_id / chunk_key / section_path / chunk_hash 等） */
    private Metadata metadata;

    /** 向量相似度（粗排分，未融合） */
    private double vectorScore;
    /** BM25 分（仅关键词通道命中时有值） */
    private double bm25Score;
    /** RRF 融合分；未启用混合召回时等于向量分 */
    private double fusedScore;
    /** 向量通道名次，0 表示该通道未召回 */
    private int vectorRank;
    /** 关键词通道名次，0 表示该通道未召回 */
    private int keywordRank;
}
