package com.example.aichat.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 向量存储配置类
 * 当前使用 InMemoryEmbeddingStore 进行快速验证
 * 后续可轻松切换到 Milvus 等其他向量数据库
 */
@Configuration
public class EmbeddingStoreConfig {

    /**
     * 配置内存向量存储（用于快速验证）
     * 注意：内存存储在应用重启后会丢失，生产环境请切换到持久化向量数据库
     */
    @Bean
    public EmbeddingStore<TextSegment> embeddingStore() {
        return new InMemoryEmbeddingStore<>();
    }

    /**
     * 预留：Milvus 向量存储配置示例
     * 
     * 切换到 Milvus 的步骤：
     * 1. 在 pom.xml 中取消 langchain4j-milvus 依赖的注释
     * 2. 在 application.yml 中添加 Milvus 配置
     * 3. 将此方法取消注释并修改实现
     * 4. 删除上面的 InMemoryEmbeddingStore Bean
     * 
     * @Bean
     * public EmbeddingStore<TextSegment> milvusEmbeddingStore(EmbeddingModel embeddingModel) {
     *     MilvusEmbeddingStore.Builder builder = MilvusEmbeddingStore.builder()
     *         .host(milvusHost)  // 从配置读取
     *         .port(milvusPort)  // 从配置读取
     *         .collectionName("knowledge_base")
     *         .embeddingModel(embeddingModel);
     *     return builder.build();
     * }
     */
}
