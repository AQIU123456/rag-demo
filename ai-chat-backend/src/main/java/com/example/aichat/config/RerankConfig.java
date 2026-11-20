package com.example.aichat.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 重排（Rerank）功能配置
 * 对应 application.yml 中的 rerank.* 配置项
 */
@Data
@Component
@ConfigurationProperties(prefix = "rerank")
public class RerankConfig {

    /**
     * 是否启用重排功能（关闭时检索行为与原来完全一致）
     */
    private boolean enabled = false;

    /**
     * 重排 API 地址（OpenAI / vLLM / TEI 兼容的 /v1/rerank 接口）
     */
    private String apiUrl;

    /**
     * API 密钥（本地部署无鉴权时可为空）
     */
    private String apiKey;

    /**
     * 重排模型名称（如 BAAI/bge-reranker-v2-m3）
     */
    private String modelName;

    /**
     * 粗排（向量召回）阶段的候选数量倍数
     * 实际召回数 = maxResults * candidateMultiplier，再交给重排模型精排出前 maxResults 条
     */
    private int candidateMultiplier = 3;

    /**
     * 调用远程重排接口的超时时间（毫秒），超时后自动降级为纯向量排序
     */
    private int timeoutMs = 5000;

    /**
     * 相似度过滤总开关：false 时检索结果原样透传，行为与改造前完全一致
     */
    private boolean filterEnabled = true;

    /**
     * 精排后的绝对最低分——本地兜底尺度（api-url 为空时生效）。
     * 本地分 = 0.7×字符二元组重合度 + 0.3×向量分，中文长文档天然偏低，故取小值。
     */
    private double minRelevanceScore = 0.10;

    /**
     * 精排后的绝对最低分——远程交叉编码器尺度（api-url 非空时生效）。
     * bge-reranker 一类模型 sigmoid 后相关命中通常在 0.3 以上。
     */
    private double remoteMinRelevanceScore = 0.25;

    /**
     * 相对阈值：低于「本次最高分 × 该比例」的候选一并丢弃，用于抵抗跨查询的分数漂移
     */
    private double relevanceRatio = 0.4;

    /**
     * 无论分数多低都至少保留的条数，防止过滤过狠导致上下文全空
     */
    private int minKeep = 1;

    /**
     * 拒答开关：最高分低于对应拒答阈值时跳过 LLM，直接返回「无相关内容」
     */
    private boolean rejectEnabled = true;

    /**
     * 拒答阈值——本地兜底尺度（api-url 为空时生效），须低于 minRelevanceScore
     */
    private double rejectThreshold = 0.05;

    /**
     * 拒答阈值——远程交叉编码器尺度（api-url 非空时生效），须低于 remoteMinRelevanceScore
     */
    private double remoteRejectThreshold = 0.15;
}
