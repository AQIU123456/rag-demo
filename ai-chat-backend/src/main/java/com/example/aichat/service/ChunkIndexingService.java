package com.example.aichat.service;

import com.example.aichat.config.ChunkProperties;
import com.example.aichat.domain.chunk.Chunk;
import com.example.aichat.domain.chunk.ChunkPolicy;
import com.example.aichat.entity.DocumentChunk;
import com.example.aichat.entity.KnowledgeDocument;
import com.example.aichat.repository.DocumentChunkRepository;
import com.example.aichat.repository.KnowledgeDocumentRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 唯一写路径编排器：切分 -> 索引 -> 落表（或幂等回填），以及删除闭环。
 *
 * <p>设计为共享 Service 而非某个 Service 的私有方法，是因为上传、文档编辑、分块编辑、
 * 分块重新生成、启动重建五条路径都需要同一套逻辑。</p>
 *
 * <p>{@code indexAfterCommit} 把索引动作放到数据库事务提交之后，保住原有
 * "向量化失败不影响文档保存" 的降级语义，同时避免事务被标记 rollback-only。</p>
 */
@Service
public class ChunkIndexingService {

    private static final Logger log = LoggerFactory.getLogger(ChunkIndexingService.class);

    private final KnowledgeDocumentRepository documentRepository;
    private final DocumentChunkRepository chunkRepository;
    private final ChunkPipeline chunkPipeline;
    private final ChunkIndex chunkIndex;
    private final ChunkProperties props;
    private final ObjectMapper objectMapper;

    @Value("${langchain4j.open-ai.embedding-model.model-name:unknown}")
    private String embeddingModelName;

    public ChunkIndexingService(KnowledgeDocumentRepository documentRepository,
                                DocumentChunkRepository chunkRepository,
                                ChunkPipeline chunkPipeline,
                                ChunkIndex chunkIndex,
                                ChunkProperties props,
                                ObjectMapper objectMapper) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.chunkPipeline = chunkPipeline;
        this.chunkIndex = chunkIndex;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    /** 事务提交后异步（同一线程 afterCommit）重建索引；无事务时立即执行 */
    public void indexAfterCommit(Long docId) {
        indexAfterCommit(docId, ChunkPolicy.defaults());
    }

    public void indexAfterCommit(Long docId, ChunkPolicy policy) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            safeIndex(docId, policy);
                        }
                    });
        } else {
            safeIndex(docId, policy);
        }
    }

    private void safeIndex(Long docId, ChunkPolicy policy) {
        try {
            indexNow(docId, policy);
        } catch (Exception e) {
            // 保持降级语义：索引失败只记录，不影响已保存的文档
            log.warn("分块索引失败 docId={} reason={}", docId, e.getMessage());
        }
    }

    /** 同步重建，供「重新生成分块」这类需要立即返回结果的入口使用 */
    public int indexNow(Long docId, ChunkPolicy policy) {
        KnowledgeDocument doc = documentRepository.findById(docId)
                .orElseThrow(() -> new IllegalArgumentException("文档不存在，ID: " + docId));
        return indexNow(doc, policy);
    }

    public int indexNow(KnowledgeDocument doc, ChunkPolicy policy) {
        List<Chunk> chunks = chunkPipeline.split(doc, policy);
        // 降级：嵌入 API 不可用时仍保证分块表与文档一致，向量由下次启动重建补齐
        ChunkIndex.IndexResult result;
        try {
            result = chunkIndex.reindex(doc.getId(), chunks);
        } catch (Exception e) {
            log.warn("向量索引写入失败，分块表仍照常落库 docId={} reason={}",
                    doc.getId(), e.getMessage());
            result = new ChunkIndex.IndexResult(List.of(), 0);
        }

        List<DocumentChunk> old = chunkRepository.findByDocumentIdOrderByChunkIndex(doc.getId());
        boolean same = isSameVersionAndContent(old, chunks);
        if (same) {
            // hash 序列一致：不 DELETE+INSERT，避免分块主键漂移导致前端编辑引用失效
            List<String> ids = result.getVectorIds();
            for (int i = 0; i < old.size() && i < ids.size(); i++) {
                old.get(i).setVectorId(ids.get(i));
            }
            chunkRepository.saveAll(old);
            log.debug("幂等短路：仅重建内存向量 docId={} chunks={}", doc.getId(), chunks.size());
            return chunks.size();
        }

        chunkRepository.deleteByDocumentId(doc.getId());
        chunkRepository.flush();

        List<String> ids = result.getVectorIds();
        List<DocumentChunk> rows = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            Chunk c = chunks.get(i);
            rows.add(DocumentChunk.builder()
                    .document(doc)
                    .content(c.getContent())
                    .chunkIndex(c.getIndex())
                    .metadata(metadataJson(c))
                    .vectorDimension(Math.max(result.getDimension(), 1))
                    .vectorId(i < ids.size() ? ids.get(i) : null)
                    .parentId(c.getParentKey())
                    .sectionPath(c.getSectionPath())
                    .contentHash(c.getContentHash())
                    .chunkVersion(props.getActiveVersion())
                    .tokenCount(c.getTokenCount())
                    .active(Boolean.TRUE)
                    .tenantId(props.getCurrentTenant())
                    .aclTag("public")
                    .embeddingModel(embeddingModelName)
                    .build());
        }
        chunkRepository.saveAll(rows);
        return rows.size();
    }

    private boolean isSameVersionAndContent(List<DocumentChunk> old, List<Chunk> chunks) {
        if (old.size() != chunks.size() || old.isEmpty()) {
            return false;
        }
        List<String> oldHashes = old.stream()
                .map(DocumentChunk::getContentHash).collect(Collectors.toList());
        List<String> newHashes = chunks.stream()
                .map(Chunk::getContentHash).collect(Collectors.toList());
        if (!newHashes.equals(oldHashes)) {
            return false;
        }
        return old.stream().allMatch(r -> props.getActiveVersion().equals(r.getChunkVersion()));
    }

    /** metadata 从 Metadata.toString()（非 JSON）改为结构化 JSON */
    public String metadataJson(Chunk c) {
        Map<String, Object> md = new LinkedHashMap<>();
        md.put("chunk_key", c.getChunkKey());
        md.put("section_path", c.getSectionPath());
        md.put("content_hash", c.getContentHash());
        md.put("parent_id", c.getParentKey());
        md.put("prev_key", c.getPrevKey());
        md.put("next_key", c.getNextKey());
        md.put("token_count", c.getTokenCount());
        md.put("truncated", c.isTruncated());
        md.put("chunk_version", props.getActiveVersion());
        md.put("active", true);
        md.put("tenant_id", props.getCurrentTenant());
        md.put("acl_tag", "public");
        md.put("embedding_model", embeddingModelName);
        try {
            return objectMapper.writeValueAsString(md);
        } catch (JsonProcessingException e) {
            log.warn("分块元数据序列化失败 chunkKey={}", c.getChunkKey());
            return "{}";
        }
    }

    /** 删除闭环：表记录 + 向量墓碑（调用方负责在事务提交后触发） */
    public void removeByDocId(Long docId) {
        chunkIndex.removeByDocId(docId);
    }

    public void removeByDocIdAfterCommit(Long docId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            chunkIndex.removeByDocId(docId);
                        }
                    });
        } else {
            chunkIndex.removeByDocId(docId);
        }
    }

}
