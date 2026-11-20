package com.example.aichat.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 向量存储配置：项目统一使用 Milvus（持久化向量库）。
 *
 * <p>调用方（ChunkIndex 等）只依赖 {@link EmbeddingStore} 接口，不感知具体实现。
 * 向量由上传/编辑等写路径实时生成并写入 Milvus；启动阶段不再做全量重建。</p>
 */
@Configuration
public class EmbeddingStoreConfig {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingStoreConfig.class);

    @Bean
    public EmbeddingStore<TextSegment> embeddingStore(
            @Value("${milvus.uri}") String uri,
            @Value("${milvus.token}") String token,
            @Value("${milvus.collection-name:knowledge_base}") String collectionName,
            @Value("${milvus.dimension:1024}") int dimension) {
        log.info("向量存储实现：Milvus uri={} collection={} dimension={}", uri, collectionName, dimension);
        return MilvusEmbeddingStore.builder()
                .uri(uri)
                .token(token)
                .collectionName(collectionName)
                .dimension(dimension)
                .build();
    }
}
