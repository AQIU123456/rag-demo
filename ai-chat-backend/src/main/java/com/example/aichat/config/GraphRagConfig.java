package com.example.aichat.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 图 RAG（GraphRAG）功能配置
 * 对应 application.yml 中的 graphrag.* 配置项
 */
@Data
@Component
@ConfigurationProperties(prefix = "graphrag")
public class GraphRagConfig {

    /**
     * 是否启用图增强检索（关闭时问答行为与纯向量 RAG 完全一致）
     */
    private boolean enabled = true;

    /**
     * 图中一跳邻居的最大扩展数量
     * 种子实体每个只取前 N 个相邻实体参与上下文构建
     */
    private int maxHops = 1;

    /**
     * 最多参与上下文构建的种子实体数量
     * 由 LLM 从问题中抽取，超出部分按重要性截断
     */
    private int maxSeedEntities = 5;

    /**
     * 最终注入提示词的关系三元组最大条数
     */
    private int maxTriplesInContext = 20;

    /**
     * 用于实体抽取与关系匹配的 LLM 调用温度（低温度保证输出稳定）
     */
    private double extractionTemperature = 0.0;
}
