package com.example.aichat.service;

import com.example.aichat.config.RerankConfig;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 重排（Rerank）服务
 * 封装对远程交叉编码器（Cross-Encoder）重排接口的调用，
 * 用于在向量粗排之后对"问题-片段"对进行精细打分与重新排序。
 *
 * 接口协议兼容 OpenAI / vLLM / HuggingFace TEI 的 /v1/rerank 风格：
 * 请求体：{"model": "...", "query": "...", "documents": [...], "top_n": N}
 * 响应体：{"results": [{"index": 0, "relevance_score": 0.98}, ...]}
 *
 * 降级策略：未启用、未配置地址或调用失败/超时时，自动退回按向量相似度排序，保证主流程不受影响。
 */
@Service
public class RerankService {

    private static final Logger log = LoggerFactory.getLogger(RerankService.class);

    /**
     * 本地兜底重排时使用的关键词重叠度权重
     * 最终分数 = rerankScore * 0.7 + vectorScore * 0.3
     */
    private static final double KEYWORD_WEIGHT = 0.7;
    private static final double VECTOR_WEIGHT = 0.3;

    private final RerankConfig rerankConfig;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public RerankService(RerankConfig rerankConfig, ObjectMapper objectMapper) {
        this.rerankConfig = rerankConfig;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(rerankConfig.getTimeoutMs()))
                .build();
    }

    /**
     * 是否已配置远程重排 API（enabled 且 apiUrl 非空）
     * @return true 表示可调用远程重排接口
     */
    public boolean isRemoteEnabled() {
        return rerankConfig.isEnabled()
                && rerankConfig.getApiUrl() != null
                && !rerankConfig.getApiUrl().isBlank();
    }

    /**
     * 是否启用了重排功能（远程或本地兜底任一生效即视为启用）
     * @return true 表示检索链路应执行重排
     */
    public boolean isEnabled() {
        return rerankConfig.isEnabled();
    }

    /**
     * 获取粗排召回候选数量倍数配置
     * @return 实际召回数 = maxResults * 该倍数
     */
    public int getCandidateMultiplier() {
        return Math.max(1, rerankConfig.getCandidateMultiplier());
    }

    /**
     * 对候选文档进行重排的统一入口（带向量分数版本）
     * 优先调用远程重排 API；未配置或调用失败/超时时自动降级为本地轻量重排。
     *
     * @param query        用户查询
     * @param documents    候选文档内容列表
     * @param vectorScores 各候选对应的向量相似度分数（可为 null）
     * @param topN         最终保留的条数
     * @return 重排结果列表（含原始下标与相关性分数），按相关性降序
     */
    public List<RerankResult> rerank(String query, List<String> documents,
                                     double[] vectorScores, int topN) {
        if (documents == null || documents.isEmpty() || topN <= 0) {
            return new ArrayList<>();
        }

        List<RerankResult> results;
        if (isRemoteEnabled()) {
            try {
                results = callRemoteRerankApi(query, documents, topN);
            } catch (Exception e) {
                log.warn("远程重排接口调用失败，降级为本地轻量重排：{}", e.getMessage());
                results = localRerank(query, documents, vectorScores, topN);
            }
        } else {
            // 开关打开但未配置 API 地址时使用本地兜底重排
            results = localRerank(query, documents, vectorScores, topN);
        }
        return results;
    }

    /**
     * 对候选文档进行重排的简化入口（不携带向量分数）
     *
     * @param query     用户查询
     * @param documents 候选文档内容列表
     * @param topN      最终保留的条数
     * @return 重排结果列表（含原始下标与相关性分数），按相关性降序
     */
    public List<RerankResult> rerank(String query, List<String> documents, int topN) {
        return rerank(query, documents, null, topN);
    }

    /**
     * EmbeddingService 精排阶段使用的入口：
     * 携带候选的向量相似度分数，便于本地兜底重排时做分数融合。
     *
     * @param query        用户查询
     * @param documents    候选文档内容列表
     * @param vectorScores 各候选对应的向量相似度分数
     * @param topN         最终保留的条数
     * @return 重排后的结果列表
     */
    public List<RerankResult> localRerankWithVectorScores(String query, List<String> documents,
                                                          double[] vectorScores, int topN) {
        return rerank(query, documents, vectorScores, topN);
    }

    /**
     * 调用远程重排接口（OpenAI / vLLM / TEI 兼容协议）
     */
    private List<RerankResult> callRemoteRerankApi(String query, List<String> documents, int topN)
            throws Exception {
        // 构建请求体
        RerankApiRequest apiRequest = new RerankApiRequest(
                rerankConfig.getModelName(), query, documents, topN);
        String requestBody = objectMapper.writeValueAsString(apiRequest);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(rerankConfig.getApiUrl()))
                .timeout(Duration.ofMillis(rerankConfig.getTimeoutMs()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody));
        if (rerankConfig.getApiKey() != null && !rerankConfig.getApiKey().isBlank()) {
            builder.header("Authorization", "Bearer " + rerankConfig.getApiKey());
        }

        HttpResponse<String> httpResponse = httpClient.send(builder.build(),
                HttpResponse.BodyHandlers.ofString());

        if (httpResponse.statusCode() != 200) {
            throw new IllegalStateException("重排接口返回状态码 " + httpResponse.statusCode()
                    + "，响应：" + truncate(httpResponse.body()));
        }

        RerankApiResponse apiResponse = objectMapper.readValue(
                httpResponse.body(), RerankApiResponse.class);
        if (apiResponse.results == null || apiResponse.results.isEmpty()) {
            throw new IllegalStateException("重排接口返回结果为空");
        }

        List<RerankResult> results = new ArrayList<>();
        for (RerankApiResponse.Item item : apiResponse.results) {
            if (item.index >= 0 && item.index < documents.size()) {
                results.add(new RerankResult(item.index, item.relevanceScore));
            }
        }
        // 接口可能不按分数排序返回，这里统一按分数降序
        results.sort(Comparator.comparingDouble(RerankResult::getRelevanceScore).reversed());
        return results;
    }

    /**
     * 本地轻量重排（兜底方案，无需外部依赖）
     * 使用"字符二元组重合度"近似词级相似度，衡量查询与文档的词面相关性，
     * 并与向量相似度加权融合后重新排序。
     * 注意：这是对交叉编码器的粗略模拟，精度有限，建议生产环境配置远程重排 API。
     *
     * @param query        用户查询
     * @param documents    候选文档列表
     * @param vectorScores 各候选对应的向量相似度分数（可为空，缺省视为 0）
     * @param topN         最终保留条数
     * @return 重排后的结果列表
     */
    public List<RerankResult> localRerank(String query, List<String> documents,
                                          double[] vectorScores, int topN) {
        List<RerankResult> results = new ArrayList<>();
        for (int i = 0; i < documents.size(); i++) {
            double keywordScore = computeKeywordOverlap(query, documents.get(i));
            double vectorScore = vectorScores != null && i < vectorScores.length
                    ? vectorScores[i] : 0.0;
            double fused = KEYWORD_WEIGHT * keywordScore + VECTOR_WEIGHT * vectorScore;
            results.add(new RerankResult(i, fused));
        }
        results.sort(Comparator.comparingDouble(RerankResult::getRelevanceScore).reversed());
        return results.size() > topN ? new ArrayList<>(results.subList(0, topN)) : results;
    }

    /**
     * 本地轻量重排（不带向量分数时的简化入口）
     */
    private List<RerankResult> localRerank(String query, List<String> documents, int topN) {
        return localRerank(query, documents, null, topN);
    }

    /**
     * 计算查询与文档之间的字符二元组（bigram）重合度，取值 [0, 1]
     * 对中文等无空格语言按相邻两字符切分，近似词面匹配程度
     */
    private double computeKeywordOverlap(String query, String document) {
        if (query == null || document == null) {
            return 0.0;
        }
        String q = query.toLowerCase().replaceAll("\\s+", "");
        String d = document.toLowerCase().replaceAll("\\s+", "");
        if (q.length() < 2 || d.isEmpty()) {
            return q.isEmpty() ? 0.0 : (d.contains(q) ? 1.0 : 0.0);
        }
        List<String> qBigrams = toBigrams(q);
        List<String> dBigrams = toBigrams(d);
        long matched = qBigrams.stream().distinct().filter(dBigrams::contains).count();
        long total = qBigrams.stream().distinct().count();
        return total == 0 ? 0.0 : (double) matched / total;
    }

    /**
     * 将文本切分为相邻两字符组成的二元组列表
     */
    private List<String> toBigrams(String text) {
        List<String> bigrams = new ArrayList<>();
        for (int i = 0; i + 2 <= text.length(); i++) {
            bigrams.add(text.substring(i, i + 2));
        }
        return bigrams;
    }

    /**
     * 截断过长的响应体，避免日志刷屏
     */
    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 200 ? text.substring(0, 200) + "..." : text;
    }

    /**
     * 单条重排结果
     */
    public static class RerankResult {
        /**
         * 该结果在原始候选列表中的下标
         */
        private final int index;

        /**
         * 重排后的相关性分数（远程模型输出或本地融合分数），取值约 [0, 1]
         */
        private final double relevanceScore;

        public RerankResult(int index, double relevanceScore) {
            this.index = index;
            this.relevanceScore = relevanceScore;
        }

        public int getIndex() {
            return index;
        }

        public double getRelevanceScore() {
            return relevanceScore;
        }
    }

    /**
     * 远程重排 API 请求体
     */
    static class RerankApiRequest {
        /**
         * 重排模型名称
         */
        @JsonProperty("model")
        final String model;

        /**
         * 查询文本
         */
        @JsonProperty("query")
        final String query;

        /**
         * 候选文档列表
         */
        @JsonProperty("documents")
        final List<String> documents;

        /**
         * 返回的最相关文档条数
         */
        @JsonProperty("top_n")
        final int topN;

        RerankApiRequest(String model, String query, List<String> documents, int topN) {
            this.model = model;
            this.query = query;
            this.documents = documents;
            this.topN = topN;
        }
    }

    /**
     * 远程重排 API 响应体
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class RerankApiResponse {
        /**
         * 重排结果列表
         */
        @JsonProperty("results")
        List<Item> results;

        /**
         * 单条重排结果项
         */
        @JsonIgnoreProperties(ignoreUnknown = true)
        static class Item {
            /**
             * 对应请求中 documents 的下标
             */
            @JsonProperty("index")
            int index;

            /**
             * 相关性分数
             */
            @JsonProperty("relevance_score")
            double relevanceScore;
        }
    }
}
