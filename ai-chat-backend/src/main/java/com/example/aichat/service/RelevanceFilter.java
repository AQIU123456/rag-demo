package com.example.aichat.service;

import com.example.aichat.config.RerankConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 相似度过滤：检索结果送入 LLM 之前按相关性分数卡一道闸门。
 *
 * <p><b>只在精排成功之后生效</b>。原因：链路里存在三种量纲完全不同的分数——
 * Milvus 向量分（余弦 0~1）、RRF 融合分（Σ1/(k+rank)，上限约 0.033）、精排分（语义相关度）。
 * 只有精排分跨查询可比，用同一套阈值去卡前两者会把候选成批误杀。</p>
 *
 * <p>为此本类提供两组阈值：{@code api-url} 为空时走本地兜底重排（0.7×bigram + 0.3×向量），
 * 分数整体偏低；{@code api-url} 非空时走远程交叉编码器，分数偏高。按精排来源自动切换，
 * 避免上线后因换精排实现而大面积空召回。</p>
 */
@Component
public class RelevanceFilter {

    private static final Logger log = LoggerFactory.getLogger(RelevanceFilter.class);

    private final RerankConfig config;

    public RelevanceFilter(RerankConfig config) {
        this.config = config;
    }

    /**
     * 过滤是否真正生效：过滤总开关与精排总开关必须同时打开。
     * 精排关闭时拿不到跨查询可比的分数，此时强行过滤只会误伤。
     */
    public boolean isActive() {
        return config.isFilterEnabled() && config.isEnabled();
    }

    /**
     * 绝对阈值 + 相对阈值双条件过滤，前 {@code minKeep} 条无条件保留。
     *
     * @param hits     已按分数降序排列的检索结果
     * @param reranked 本次结果是否经过<b>成功</b>的精排；精排失败降级时为 false，直接透传
     * @return 过滤后的结果，保持原顺序
     */
    public List<EmbeddingService.SearchResult> apply(List<EmbeddingService.SearchResult> hits,
                                                     boolean reranked) {
        if (hits == null || hits.isEmpty()) {
            return hits == null ? List.of() : hits;
        }
        if (!reranked || !isActive()) {
            return hits;
        }
        double top = hits.get(0).getScore();
        double floor = Math.max(effectiveMinScore(), top * config.getRelevanceRatio());
        List<EmbeddingService.SearchResult> kept = new ArrayList<>(hits.size());
        for (EmbeddingService.SearchResult hit : hits) {
            // 前 minKeep 条不做阈值判断，避免整批被吃掉后 LLM 拿到空上下文反而更容易胡编
            if (kept.size() < config.getMinKeep() || hit.getScore() >= floor) {
                kept.add(hit);
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("RELEVANCE-FILTER in={} out={} top={} floor={}",
                    hits.size(), kept.size(), round(top), round(floor));
        }
        return kept;
    }

    /**
     * 最高分已低于拒答阈值 → 调用方应跳过 LLM，直接返回「无相关内容」。
     * 既省一次生成开销，也避免模型面对无关上下文硬编造成幻觉。
     */
    public boolean shouldReject(List<EmbeddingService.SearchResult> kept) {
        if (!isActive() || !config.isRejectEnabled()) {
            return false;
        }
        return kept == null || kept.isEmpty()
                || kept.get(0).getScore() < effectiveRejectThreshold();
    }

    /** 是否配置了远程精排地址：决定用哪一组阈值 */
    private boolean remoteRerank() {
        return config.getApiUrl() != null && !config.getApiUrl().isBlank();
    }

    private double effectiveMinScore() {
        return remoteRerank() ? config.getRemoteMinRelevanceScore() : config.getMinRelevanceScore();
    }

    private double effectiveRejectThreshold() {
        return remoteRerank() ? config.getRemoteRejectThreshold() : config.getRejectThreshold();
    }

    private static String round(double v) {
        return String.format(Locale.ROOT, "%.4f", v);
    }
}
