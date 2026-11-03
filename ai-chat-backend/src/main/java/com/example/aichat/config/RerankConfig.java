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
}
