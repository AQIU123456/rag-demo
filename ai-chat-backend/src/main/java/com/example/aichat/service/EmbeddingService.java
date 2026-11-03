package com.example.aichat.service;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 文档向量化服务
 * 负责文档分块、向量嵌入和相似度搜索
 * 支持两段式检索：向量粗排召回 -> 重排模型精排（Rerank）
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;
    private final RerankService rerankService;
    private final DocumentSplitter documentSplitter;

    public EmbeddingService(EmbeddingStore<TextSegment> embeddingStore,
                           EmbeddingModel embeddingModel,
                           RerankService rerankService) {
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
        this.rerankService = rerankService;
        // 配置递归分块参数：每块 400 字符，重叠 80 字符
        // 较小的块大小提高检索精度，较大的重叠度保持语义连贯性
        this.documentSplitter = DocumentSplitters.recursive(400, 80);
    }

    /**
     * 将文本内容分块并生成向量嵌入
     * @param text 原始文本内容
     * @param metadata 元数据（如文档 ID、标题等）
     * @return 生成的向量数量
     */
    public int embedAndStore(String text, String metadata) {
        // 1. 创建文档对象
        Document document = Document.from(text);

        // 2. 分割文档为文本块
        List<TextSegment> segments = documentSplitter.split(document);

        // 3. 为每个文本块添加元数据
        for (TextSegment segment : segments) {
            segment.metadata().put("source", metadata);
        }

        // 4. 生成向量嵌入
        List<Embedding> embeddings = embeddingModel.embedAll(segments).content();

        // 5. 存储到向量数据库
        for (int i = 0; i < embeddings.size(); i++) {
            embeddingStore.add(embeddings.get(i), segments.get(i));
        }

        return embeddings.size();
    }

    /**
     * 基于语义搜索相似的文本块
     * 启用重排时执行两段式检索：先按 maxResults * candidateMultiplier 条做向量粗排召回，
     * 再调用重排服务精排出前 maxResults 条；未启用或重排失败时退回纯向量排序。
     * @param query 查询文本
     * @param maxResults 最大返回结果数
     * @return 匹配的文本块及其相似度分数（已按最终相关性降序排列）
     */
    public List<SearchResult> searchSimilar(String query, int maxResults) {
        // 计算粗排召回数量：启用重排时多召回一些候选交给精排阶段
        boolean rerankEnabled = rerankService.isEnabled();
        int recallSize = maxResults;
        if (rerankEnabled) {
            int multiplier = Math.max(1, getRerankCandidateMultiplier());
            recallSize = Math.max(maxResults, maxResults * multiplier);
        }

        // 生成查询向量
        Embedding queryEmbedding = embeddingModel.embed(query).content();

        // 第一阶段：在向量库中搜索相似内容（粗排召回）
        List<EmbeddingMatch<TextSegment>> matches = embeddingStore.findRelevant(queryEmbedding, recallSize);

        // 转换为搜索结果
        List<SearchResult> results = new ArrayList<>();
        for (EmbeddingMatch<TextSegment> match : matches) {
            results.add(new SearchResult(
                match.embedded().text(),
                match.score(),
                match.embedded().metadata().getString("source")
            ));
        }

        // 第二阶段：重排精排（失败时内部自动降级，不影响主流程）
        if (rerankEnabled && results.size() > 1) {
            results = applyRerank(query, results, maxResults);
        }

        return results;
    }

    /**
     * 读取重排候选数量倍数配置
     */
    private int getRerankCandidateMultiplier() {
        try {
            return rerankService.getCandidateMultiplier();
        } catch (Exception e) {
            return 3;
        }
    }

    /**
     * 对粗排召回结果执行重排精排
     * @param query 查询文本
     * @param candidates 向量粗排得到的候选结果
     * @param topN 精排后保留的条数
     * @return 重排后的结果列表；任何异常都降级返回原始候选的前 topN 条
     */
    private List<SearchResult> applyRerank(String query, List<SearchResult> candidates, int topN) {
        try {
            List<String> documents = new ArrayList<>();
            double[] vectorScores = new double[candidates.size()];
            for (int i = 0; i < candidates.size(); i++) {
                documents.add(candidates.get(i).getContent());
                vectorScores[i] = candidates.get(i).getScore();
            }

            List<RerankService.RerankResult> rerankResults =
                rerankService.localRerankWithVectorScores(query, documents, vectorScores, topN);

            List<SearchResult> reranked = new ArrayList<>();
            for (RerankService.RerankResult rr : rerankResults) {
                SearchResult origin = candidates.get(rr.getIndex());
                reranked.add(new SearchResult(
                    origin.getContent(),
                    rr.getRelevanceScore(),   // 对外统一暴露重排后的相关性分数
                    origin.getSource(),
                    origin.getVectorScore()   // 保留原始向量相似度便于分析
                ));
            }
            log.debug("重排完成：候选 {} 条 -> 保留 {} 条", candidates.size(), reranked.size());
            return reranked;
        } catch (Exception e) {
            log.warn("重排执行失败，降级为向量相似度排序：{}", e.getMessage());
            return candidates.size() > topN ? new ArrayList<>(candidates.subList(0, topN)) : candidates;
        }
    }

    /**
     * 搜索结果 DTO
     */
    public static class SearchResult {
        /**
         * 匹配到的文本块内容
         */
        private final String content;

        /**
         * 最终展示的相关性分数（启用重排时为重排分数，否则为向量相似度）
         */
        private final double score;

        /**
         * 来源标识（文档标题等元数据）
         */
        private final String source;

        /**
         * 原始向量相似度分数（未经过重排时与 score 相同）
         */
        private final double vectorScore;

        public SearchResult(String content, double score, String source) {
            this(content, score, source, score);
        }

        public SearchResult(String content, double score, String source, double vectorScore) {
            this.content = content;
            this.score = score;
            this.source = source;
            this.vectorScore = vectorScore;
        }

        public String getContent() {
            return content;
        }

        public double getScore() {
            return score;
        }

        public String getSource() {
            return source;
        }

        public double getVectorScore() {
            return vectorScore;
        }
    }
}
