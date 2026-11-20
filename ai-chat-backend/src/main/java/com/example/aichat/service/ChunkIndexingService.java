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
import dev.langchain4j.model.Tokenizer;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
    /** 切分（唯一入口） */
    private final ChunkPipeline chunkPipeline;
    /** 向量索引（唯一写入口） */
    private final ChunkIndex chunkIndex;
    /** 索引版本、租户等参数，写入分块行用于回溯 */
    private final ChunkProperties props;
    /** 序列化 metadata JSON */
    private final ObjectMapper objectMapper;

    /** 嵌入模型名，落库用于「模型换代后哪些块需要重嵌」的排查 */
    @Value("${langchain4j.open-ai.embedding-model.model-name:unknown}")
    private String embeddingModelName;

    /** 启动从表恢复时用同口径估算 token；字段注入避免改动构造签名（非侵入） */
    @Autowired
    private Tokenizer tokenizer;

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

    /**
     * 事务提交后重建索引（用默认策略），无事务时立即执行。
     * 放 afterCommit 是为了：事务回滚时不留下无用向量；索引失败也不会把事务标记为 rollback-only。
     */
    public void indexAfterCommit(Long docId) {
        indexAfterCommit(docId, ChunkPolicy.defaults());
    }

    /** 同 {@link #indexAfterCommit(Long)}，可指定本次策略（如前端选择的 PARENT_CHILD） */
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

    /** 吞异常的包装：afterCommit 阶段抛异常已无人接管，只能降级记录 */
    private void safeIndex(Long docId, ChunkPolicy policy) {
        try {
            indexNow(docId, policy);
        } catch (Exception e) {
            // 保持降级语义：索引失败只记录，不影响已保存的文档
            log.warn("分块索引失败 docId={} reason={}", docId, e.getMessage());
        }
    }

    /**
     * 同步重建（按 id），供「重新生成分块」这类需要立即返回结果的入口使用。
     *
     * @return 重建后的分块数
     */
    public int indexNow(Long docId, ChunkPolicy policy) {
        KnowledgeDocument doc = documentRepository.findById(docId)
                .orElseThrow(() -> new IllegalArgumentException("文档不存在，ID: " + docId));
        return indexNow(doc, policy);
    }

    /**
     * 同步重建主流程：切分 → 写向量 → 落分块表。
     *
     * <p>表与向量分两步写，天然存在不一致窗口：向量失败时表照常落库（文档可读、可编辑），
     * 但该批次 vectorId 为空、启动恢复会跳过这些行，需重新上传或走重新生成分块补向量；
     * 表写入本身失败则抛异常，由调用方决定是否回滚。</p>
     *
     * @param doc    文档实体（content 为切分事实源）
     * @param policy 策略覆盖，null 表示用全局配置
     * @return 分块数
     */
    public int indexNow(KnowledgeDocument doc, ChunkPolicy policy) {
        // 1. 切分
        List<Chunk> chunks = chunkPipeline.split(doc, policy);
        // 降级：嵌入 API 不可用时仍保证分块表与文档一致；该批次无向量，后续需重新生成补齐
        ChunkIndex.IndexResult result;
        try {
            result = chunkIndex.reindex(doc.getId(), chunks);
        } catch (Exception e) {
            log.warn("向量索引写入失败，分块表仍照常落库 docId={} reason={}",
                    doc.getId(), e.getMessage());
            result = new ChunkIndex.IndexResult(List.of(), 0);
        }

        // 2. 与库内旧行比对：hash 序列与版本都一致时只刷新 vector_id，不重写表
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

        // 3. 内容或版本变化：整批替换。先删再 flush，避免同 doc 下新旧行唯一键冲突
        chunkRepository.deleteByDocumentId(doc.getId());
        chunkRepository.flush();

        // 4. 按 chunks 顺序生成表行；vector_ids 与 chunks 同序，下标即对应关系
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

    /**
     * 判断能否幂等短路：块数相同、送嵌文本 hash 序列逐位相同、且版本未变。
     * 三者都满足说明切分结果与索引口径完全没变，无需删表重建（保住主键，前端编辑引用不失效）。
     */
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

    /** 删除闭环：把该文档的全部向量 id 置墓碑（0.29.1 无物理删除能力），检索侧立即不可见 */
    public void removeByDocId(Long docId) {
        chunkIndex.removeByDocId(docId);
    }

    /** 事务提交后再删向量：放在提交前删，事务回滚会导致索引与库不一致 */
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

    // ==================== 启动恢复（持久化向量库专用，零嵌入 API 调用） ====================

    /**
     * 启动时从 {@code document_chunks} 表恢复内存检索白名单。
     *
     * <p>向量本体在 Milvus 里重启不丢，但 {@code ChunkIndex} 的 liveIndex / docVectors / docChunks
     * 是纯内存态、重启即空；若不恢复，检索的向量通道与 BM25 通道都会因白名单为空而过滤掉全部候选，
     * 知识库等于「失忆」。本方法只把表内已落库的 vectorId 灌回白名单，<b>不调用嵌入 API、不写向量库</b>，
     * 与已删除的「启动重算向量」是两回事。</p>
     *
     * <p>数据库不可用、单文档异常均只记日志不阻断启动。非侵入，不影响上传/编辑/重新生成等实时写路径。</p>
     */
    @PostConstruct
    public void restoreIndexOnStartup() {
        List<KnowledgeDocument> documents;
        try {
            documents = documentRepository.findAll();
        } catch (Exception e) {
            log.warn("启动索引恢复跳过：数据库不可用 - {}", e.getMessage());
            return;
        }
        int restoredDocs = 0;
        int restoredVectors = 0;
        for (KnowledgeDocument doc : documents) {
            try {
                int n = restoreDocFromRows(doc);
                if (n > 0) {
                    restoredDocs++;
                    restoredVectors += n;
                }
            } catch (Exception e) {
                // 单文档失败不阻断启动（例如历史分块缺 vectorId 或脏数据）
                log.warn("启动索引恢复失败 docId={} title={} reason={}",
                        doc.getId(), doc.getTitle(), e.getMessage());
            }
        }
        log.info("启动索引恢复完成 documents={} vectors={}", restoredDocs, restoredVectors);
    }

    /**
     * 从表内行恢复单个文档的检索白名单。
     *
     * <p>送嵌文本 / hash / token 用与 {@link DocumentChunkService} 表内重建完全相同的口径生成，
     * 保证 {@code metadataOf} 产出的元数据键与运行时一致；vectorId 直接取表内已落库值，不重算向量。
     * 跳过 vectorId 为空的历史占位行。</p>
     *
     * @return 恢复的向量数（0 表示该文档无可恢复向量）
     */
    private int restoreDocFromRows(KnowledgeDocument doc) {
        List<DocumentChunk> rows = chunkRepository.findByDocumentIdOrderByChunkIndex(doc.getId());
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        String safeTitle = com.example.aichat.security.PromptInjectionGuard.sanitizeLabel(
                doc.getTitle() == null ? "未命名文档" : doc.getTitle());

        List<Chunk> chunks = new ArrayList<>(rows.size());
        List<String> vectorIds = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            DocumentChunk row = rows.get(i);
            String content = row.getContent() == null ? "" : row.getContent();
            String embedded = chunkPipeline.embedText(safeTitle, row.getSectionPath(), content);
            String hash = ChunkPipeline.sha256(embedded);
            int tokens = row.getTokenCount() == null
                    ? tokenizer.estimateTokenCountInText(embedded) : row.getTokenCount();
            chunks.add(Chunk.builder()
                    .documentId(doc.getId())
                    .documentTitle(safeTitle)
                    .index(i)
                    .chunkKey("doc_" + doc.getId() + "_" + i + "_" + hash.substring(0, 8))
                    .sectionPath(row.getSectionPath())
                    .content(content)
                    .embeddedText(embedded)
                    .contentHash(hash)
                    .tokenCount(tokens)
                    .parentKey(row.getParentId())
                    .truncated(tokens > props.getMaxTokens() * 1.2)
                    .build());
            vectorIds.add(row.getVectorId());
        }
        // 重排 prev/next（及开启时的父子键），与运行时 relink 口径一致
        chunkPipeline.relink(chunks);
        return chunkIndex.restoreFromChunkRows(doc.getId(), chunks, vectorIds);
    }

}
