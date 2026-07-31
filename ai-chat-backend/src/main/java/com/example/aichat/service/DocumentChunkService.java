package com.example.aichat.service;

import com.example.aichat.dto.DocumentChunkDTO;
import com.example.aichat.entity.DocumentChunk;
import com.example.aichat.entity.KnowledgeDocument;
import com.example.aichat.repository.DocumentChunkRepository;
import com.example.aichat.repository.KnowledgeDocumentRepository;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 文档分块管理服务
 * 提供分块的查看、编辑、重新生成等功能
 */
@Service
@Transactional
public class DocumentChunkService {

    private final DocumentChunkRepository chunkRepository;
    private final KnowledgeDocumentRepository documentRepository;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final DocumentSplitter documentSplitter;

    public DocumentChunkService(DocumentChunkRepository chunkRepository,
                                KnowledgeDocumentRepository documentRepository,
                                EmbeddingModel embeddingModel,
                                EmbeddingStore<TextSegment> embeddingStore) {
        this.chunkRepository = chunkRepository;
        this.documentRepository = documentRepository;
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        // 配置递归分块参数：每块 512 字符，重叠 100 字符
        this.documentSplitter = DocumentSplitters.recursive(512, 100);
    }

    /**
     * 获取文档的所有分块
     */
    @Transactional(readOnly = true)
    public List<DocumentChunkDTO> getChunksByDocumentId(Long documentId) {
        KnowledgeDocument document = documentRepository.findById(documentId)
                .orElseThrow(() -> new RuntimeException("文档不存在，ID: " + documentId));
        
        List<DocumentChunk> chunks = chunkRepository.findByDocumentIdOrderByChunkIndex(documentId);
        
        return chunks.stream()
                .map(chunk -> DocumentChunkDTO.builder()
                        .id(chunk.getId())
                        .documentId(chunk.getDocument().getId())
                        .documentTitle(document.getTitle())
                        .content(chunk.getContent())
                        .chunkIndex(chunk.getChunkIndex())
                        .metadata(chunk.getMetadata())
                        .createdAt(chunk.getCreatedAt())
                        .build())
                .collect(Collectors.toList());
    }

    /**
     * 更新单个分块内容
     */
    public DocumentChunkDTO updateChunk(Long chunkId, String newContent) {
        DocumentChunk chunk = chunkRepository.findById(chunkId)
                .orElseThrow(() -> new RuntimeException("分块不存在，ID: " + chunkId));
        
        // 更新分块内容
        chunk.setContent(newContent);
        DocumentChunk updated = chunkRepository.save(chunk);
        
        // 重新生成该分块的向量嵌入
        try {
            regenerateEmbedding(updated);
        } catch (Exception e) {
            System.err.println("重新生成向量嵌入失败：" + e.getMessage());
        }
        
        KnowledgeDocument document = chunk.getDocument();
        return DocumentChunkDTO.builder()
                .id(updated.getId())
                .documentId(document.getId())
                .documentTitle(document.getTitle())
                .content(updated.getContent())
                .chunkIndex(updated.getChunkIndex())
                .metadata(updated.getMetadata())
                .createdAt(updated.getCreatedAt())
                .build();
    }

    /**
     * 删除单个分块
     */
    public void deleteChunk(Long chunkId) {
        DocumentChunk chunk = chunkRepository.findById(chunkId)
                .orElseThrow(() -> new RuntimeException("分块不存在，ID: " + chunkId));
        
        // 注意：向量存储在内存中，暂不处理清理逻辑
        // 切换到 Milvus 后需要在此处清理对应的向量数据
        
        chunkRepository.deleteById(chunkId);
    }

    /**
     * 重新生成分块（基于当前文档内容重新切分）
     */
    public List<DocumentChunkDTO> regenerateChunks(Long documentId) {
        KnowledgeDocument document = documentRepository.findById(documentId)
                .orElseThrow(() -> new RuntimeException("文档不存在，ID: " + documentId));
        
        // 删除旧的分块记录
        chunkRepository.deleteByDocumentId(documentId);
        
        // 重新分块
        Document doc = Document.from(document.getContent());
        List<TextSegment> segments = documentSplitter.split(doc);
        
        List<DocumentChunk> newChunks = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            TextSegment segment = segments.get(i);
            DocumentChunk chunk = DocumentChunk.builder()
                    .document(document)
                    .content(segment.text())
                    .chunkIndex(i)
                    .metadata(segment.metadata().toString())
                    .vectorDimension(embeddingModel.embed(segment).content().dimension())
                    .build();
            newChunks.add(chunk);
        }
        
        // 保存新分块
        List<DocumentChunk> savedChunks = chunkRepository.saveAll(newChunks);
        
        // 重新生成向量嵌入
        try {
            List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
            for (int i = 0; i < embeddings.size(); i++) {
                TextSegment segment = segments.get(i);
                segment.metadata().put("source", "doc_" + documentId);
                segment.metadata().put("chunk_id", String.valueOf(savedChunks.get(i).getId()));
                embeddingStore.add(embeddings.get(i), segment);
            }
        } catch (Exception e) {
            System.err.println("重新生成向量嵌入失败：" + e.getMessage());
        }
        
        return savedChunks.stream()
                .map(chunk -> DocumentChunkDTO.builder()
                        .id(chunk.getId())
                        .documentId(document.getId())
                        .documentTitle(document.getTitle())
                        .content(chunk.getContent())
                        .chunkIndex(chunk.getChunkIndex())
                        .metadata(chunk.getMetadata())
                        .createdAt(chunk.getCreatedAt())
                        .build())
                .collect(Collectors.toList());
    }

    /**
     * 为单个分块重新生成向量嵌入
     */
    private void regenerateEmbedding(DocumentChunk chunk) {
        try {
            TextSegment segment = TextSegment.from(chunk.getContent());
            segment.metadata().put("source", "doc_" + chunk.getDocument().getId());
            segment.metadata().put("chunk_id", String.valueOf(chunk.getId()));
            
            Embedding embedding = embeddingModel.embed(segment).content();
            
            // 注意：InMemoryEmbeddingStore 不支持更新操作
            // 这里只是添加新的向量，实际生产中应使用支持更新的向量库
            embeddingStore.add(embedding, segment);
        } catch (Exception e) {
            System.err.println("重新生成向量嵌入失败：" + e.getMessage());
            throw e;
        }
    }

    /**
     * 批量更新分块顺序（用于调整分块顺序）
     */
    public void updateChunkOrder(List<Long> chunkIds) {
        for (int i = 0; i < chunkIds.size(); i++) {
            DocumentChunk chunk = chunkRepository.findById(chunkIds.get(i))
                    .orElseThrow(() -> new RuntimeException("分块不存在，ID: " + chunkIds.get(i)));
            chunk.setChunkIndex(i);
            chunkRepository.save(chunk);
        }
    }
}
