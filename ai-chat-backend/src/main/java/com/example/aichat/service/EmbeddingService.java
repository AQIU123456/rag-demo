package com.example.aichat.service;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 文档向量化服务
 * 负责文档分块、向量嵌入和相似度搜索
 */
@Service
public class EmbeddingService {

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;
    private final DocumentSplitter documentSplitter;

    public EmbeddingService(EmbeddingStore<TextSegment> embeddingStore, 
                           EmbeddingModel embeddingModel) {
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
        // 配置分块参数：每块 500 字符，重叠 50 字符
        this.documentSplitter = DocumentSplitters.recursive(500, 50);
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
     * @param query 查询文本
     * @param maxResults 最大返回结果数
     * @return 匹配的文本块及其相似度分数
     */
    public List<SearchResult> searchSimilar(String query, int maxResults) {
        // 生成查询向量
        Embedding queryEmbedding = embeddingModel.embed(query).content();
        
        // 在向量库中搜索相似内容
        List<EmbeddingMatch<TextSegment>> matches = embeddingStore.findRelevant(queryEmbedding, maxResults);
        
        // 转换为搜索结果
        List<SearchResult> results = new ArrayList<>();
        for (EmbeddingMatch<TextSegment> match : matches) {
            results.add(new SearchResult(
                match.embedded().text(),
                match.score(),
                match.embedded().metadata().getString("source")
            ));
        }
        
        return results;
    }

    /**
     * 搜索结果 DTO
     */
    public static class SearchResult {
        private final String content;
        private final double score;
        private final String source;

        public SearchResult(String content, double score, String source) {
            this.content = content;
            this.score = score;
            this.source = source;
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
    }
}
