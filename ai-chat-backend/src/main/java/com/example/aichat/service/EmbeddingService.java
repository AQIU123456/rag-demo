package com.example.aichat.service;

import com.example.aichat.domain.chunk.ChunkHit;
import com.example.aichat.domain.chunk.ChunkPolicy;
import com.example.aichat.entity.KnowledgeDocument;
import com.example.aichat.repository.KnowledgeDocumentRepository;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 检索门面 + 向后兼容包装。
 *
 * <p>S1 起本类<b>不再负责切分与写入</b>：所有写路径统一由 {@link ChunkIndexingService}
 * 编排（{@link ChunkPipeline} 切分 + {@link ChunkIndex} 索引）。
 * 检索统一走 {@code ChunkIndex.search}，再复用既有的 RerankService 两段式精排。</p>
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private final EmbeddingModel embeddingModel;
    private final RerankService rerankService;
    private final ChunkIndex chunkIndex;
    private final ChunkIndexingService chunkIndexingService;
    private final KnowledgeDocumentRepository documentRepository;

    public EmbeddingService(EmbeddingModel embeddingModel,
                            RerankService rerankService,
                            ChunkIndex chunkIndex,
                            ChunkIndexingService chunkIndexingService,
                            KnowledgeDocumentRepository documentRepository) {
        this.embeddingModel = embeddingModel;
        this.rerankService = rerankService;
        this.chunkIndex = chunkIndex;
        this.chunkIndexingService = chunkIndexingService;
        this.documentRepository = documentRepository;
    }

    /**
     * 兼容包装：保留原 public 签名，仅供外部遗留调用 / 测试使用。
     * 主链路（上传、编辑、重建）已不再调用，避免同一文档被索引两次。
     *
     * @param text     原始文本（忽略，以库中的文档内容为准，保证单一事实源）
     * @param metadata 形如 doc_&lt;id&gt;
     * @return 生成的向量数量；解析 / 查询失败时返回 0，不抛异常
     */
    public int embedAndStore(String text, String metadata) {
        Long docId = parseDocId(metadata);
        if (docId == null) {
            log.warn("embedAndStore 兼容包装未识别来源元数据，已跳过：{}", metadata);
            return 0;
        }
        try {
            KnowledgeDocument doc = documentRepository.findById(docId).orElse(null);
            if (doc == null) {
                log.warn("embedAndStore 兼容包装未找到文档 docId={}", docId);
                return 0;
            }
            chunkIndexingService.indexNow(doc, ChunkPolicy.defaults());
            return chunkIndex.vectorIdsOf(docId).size();
        } catch (Exception e) {
            // 保留原有「向量化失败不影响文档保存」的降级语义
            log.warn("embedAndStore 兼容包装索引失败 docId={} reason={}", docId, e.getMessage());
            return 0;
        }
    }

    private Long parseDocId(String source) {
        if (source == null || !source.startsWith("doc_")) {
            return null;
        }
        try {
            return Long.parseLong(source.substring(4));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 基于语义（+ 可选 BM25 混合）搜索相似文本块，并执行两段式精排。
     */
    public List<SearchResult> searchSimilar(String query, int maxResults) {
        boolean rerankEnabled = rerankService.isEnabled();
        int recallSize = maxResults;
        if (rerankEnabled) {
            int multiplier = Math.max(1, getRerankCandidateMultiplier());
            recallSize = Math.max(maxResults, maxResults * multiplier);
        }

        Embedding queryEmbedding = embeddingModel.embed(query).content();
        List<ChunkHit> hits = chunkIndex.search(query, queryEmbedding, recallSize);

        List<SearchResult> results = new ArrayList<>(hits.size());
        for (ChunkHit hit : hits) {
            results.add(toSearchResult(hit));
        }

        if (rerankEnabled && results.size() > 1) {
            results = applyRerank(query, results, maxResults);
        }
        return results;
    }

    private SearchResult toSearchResult(ChunkHit hit) {
        String source = hit.getMetadata().getString("source");
        Long docId = null;
        String rawDocId = hit.getMetadata().getString("doc_id");
        if (rawDocId != null) {
            try {
                docId = Long.parseLong(rawDocId);
            } catch (NumberFormatException ignored) {
                // 元数据异常不影响检索结果
            }
        }
        return new SearchResult(
                hit.getContent(),
                hit.getFusedScore(),
                source,
                hit.getVectorScore(),
                hit.getMetadata().getString("chunk_key"),
                docId,
                hit.getMetadata().getString("doc_title"),
                hit.getMetadata().getString("section_path"),
                hit.getContextText());
    }

    private int getRerankCandidateMultiplier() {
        try {
            return rerankService.getCandidateMultiplier();
        } catch (Exception e) {
            return 3;
        }
    }

    /** 对粗排召回结果执行重排精排，保留全部溯源字段 */
    private List<SearchResult> applyRerank(String query, List<SearchResult> candidates, int topN) {
        try {
            List<String> documents = new ArrayList<>();
            double[] vectorScores = new double[candidates.size()];
            for (int i = 0; i < candidates.size(); i++) {
                // 精排用扩展后的上下文，让父子块信息也参与相关性判断
                documents.add(candidates.get(i).getContextText() != null
                        ? candidates.get(i).getContextText() : candidates.get(i).getContent());
                vectorScores[i] = candidates.get(i).getScore();
            }

            List<RerankService.RerankResult> rerankResults =
                    rerankService.localRerankWithVectorScores(query, documents, vectorScores, topN);

            List<SearchResult> reranked = new ArrayList<>();
            for (RerankService.RerankResult rr : rerankResults) {
                SearchResult origin = candidates.get(rr.getIndex());
                reranked.add(origin.withScore(rr.getRelevanceScore(), origin.getVectorScore()));
            }
            log.debug("重排完成：候选 {} 条 -> 保留 {} 条", candidates.size(), reranked.size());
            return reranked;
        } catch (Exception e) {
            log.warn("重排执行失败，降级为向量相似度排序：{}", e.getMessage());
            return candidates.size() > topN ? new ArrayList<>(candidates.subList(0, topN)) : candidates;
        }
    }

    /**
     * 搜索结果 DTO。原三参 / 四参构造器保持不变，新增溯源字段走全参构造器。
     */
    public static class SearchResult {
        private final String content;
        private final double score;
        private final String source;
        private final double vectorScore;
        private final String chunkId;
        private final Long documentId;
        private final String documentTitle;
        private final String sectionPath;
        /** 送 LLM 的上下文（父块 / 相邻块扩展后可能比 content 长） */
        private final String contextText;

        public SearchResult(String content, double score, String source) {
            this(content, score, source, score);
        }

        public SearchResult(String content, double score, String source, double vectorScore) {
            this(content, score, source, vectorScore, null, null, null, null, content);
        }

        public SearchResult(String content, double score, String source, double vectorScore,
                            String chunkId, Long documentId, String documentTitle,
                            String sectionPath, String contextText) {
            this.content = content;
            this.score = score;
            this.source = source;
            this.vectorScore = vectorScore;
            this.chunkId = chunkId;
            this.documentId = documentId;
            this.documentTitle = documentTitle;
            this.sectionPath = sectionPath;
            this.contextText = contextText == null ? content : contextText;
        }

        /** 重排后生成同溯源字段、仅分数不同的新结果 */
        public SearchResult withScore(double newScore, double newVectorScore) {
            return new SearchResult(content, newScore, source, newVectorScore,
                    chunkId, documentId, documentTitle, sectionPath, contextText);
        }

        public String getContent() { return content; }
        public double getScore() { return score; }
        public String getSource() { return source; }
        public double getVectorScore() { return vectorScore; }
        public String getChunkId() { return chunkId; }
        public Long getDocumentId() { return documentId; }
        public String getDocumentTitle() { return documentTitle; }
        public String getSectionPath() { return sectionPath; }
        public String getContextText() { return contextText; }
    }
}
