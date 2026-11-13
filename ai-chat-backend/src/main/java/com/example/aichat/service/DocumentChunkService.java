package com.example.aichat.service;

import com.example.aichat.config.ChunkProperties;
import com.example.aichat.domain.chunk.Chunk;
import com.example.aichat.domain.chunk.ChunkPolicy;
import com.example.aichat.dto.DocumentChunkDTO;
import com.example.aichat.entity.DocumentChunk;
import com.example.aichat.entity.KnowledgeDocument;
import com.example.aichat.enums.ChunkStrategy;
import com.example.aichat.repository.DocumentChunkRepository;
import com.example.aichat.repository.KnowledgeDocumentRepository;
import com.example.aichat.security.PromptInjectionGuard;
import dev.langchain4j.model.Tokenizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 文档分块管理服务：所有写操作委托给 {@link ChunkIndexingService} / {@link ChunkIndex}，
 * 本类只负责 DTO 映射与「分块级手工覆盖」的重建。
 *
 * <p>已删除原先的 4 套硬编码 splitter 与空格分词 Jaccard 伪语义切分
 * （中文无空格时会把全文并成一块，且无长度上限）。</p>
 */
@Service
@Transactional
public class DocumentChunkService {

    private static final Logger log = LoggerFactory.getLogger(DocumentChunkService.class);

    private final DocumentChunkRepository chunkRepository;
    private final KnowledgeDocumentRepository documentRepository;
    private final ChunkIndexingService chunkIndexingService;
    private final ChunkPipeline chunkPipeline;
    private final ChunkIndex chunkIndex;
    private final ChunkProperties props;
    private final Tokenizer tokenizer;

    public DocumentChunkService(DocumentChunkRepository chunkRepository,
                                KnowledgeDocumentRepository documentRepository,
                                ChunkIndexingService chunkIndexingService,
                                ChunkPipeline chunkPipeline,
                                ChunkIndex chunkIndex,
                                ChunkProperties props,
                                Tokenizer tokenizer) {
        this.chunkRepository = chunkRepository;
        this.documentRepository = documentRepository;
        this.chunkIndexingService = chunkIndexingService;
        this.chunkPipeline = chunkPipeline;
        this.chunkIndex = chunkIndex;
        this.props = props;
        this.tokenizer = tokenizer;
    }

    /** 获取文档的所有分块 */
    @Transactional(readOnly = true)
    public List<DocumentChunkDTO> getChunksByDocumentId(Long documentId) {
        KnowledgeDocument document = documentRepository.findById(documentId)
                .orElseThrow(() -> new RuntimeException("文档不存在，ID: " + documentId));

        List<DocumentChunk> chunks = chunkRepository.findByDocumentIdOrderByChunkIndex(documentId);
        String title = document.getTitle();
        return chunks.stream().map(c -> toDTO(c, title)).collect(Collectors.toList());
    }

    /**
     * 更新单个分块内容。
     * 手工覆盖的分块写回表后，以「表内行」为事实源重建整个文档的向量索引，
     * 因此不会产生只 add 不删的幽灵向量。后续文档正文变更时会重新按全文切分（文档为最终事实源）。
     */
    public DocumentChunkDTO updateChunk(Long chunkId, String newContent) {
        DocumentChunk chunk = chunkRepository.findById(chunkId)
                .orElseThrow(() -> new RuntimeException("分块不存在，ID: " + chunkId));

        // 源头消毒，防止通过分块编辑接口投放注入载荷
        chunk.setContent(PromptInjectionGuard.sanitizeDocument(newContent));
        DocumentChunk updated = chunkRepository.save(chunk);
        Long docId = updated.getDocument().getId();

        try {
            reindexFromRows(docId);
        } catch (Exception e) {
            log.warn("分块编辑后重建索引失败 docId={} reason={}", docId, e.getMessage());
        }

        DocumentChunk reloaded = chunkRepository.findById(chunkId).orElse(updated);
        return toDTO(reloaded, reloaded.getDocument().getTitle());
    }

    /** 删除单个分块：删记录 + 重建该文档索引，检索侧不再召回已删内容 */
    public void deleteChunk(Long chunkId) {
        DocumentChunk chunk = chunkRepository.findById(chunkId)
                .orElseThrow(() -> new RuntimeException("分块不存在，ID: " + chunkId));
        Long docId = chunk.getDocument().getId();

        chunkRepository.deleteById(chunkId);
        chunkRepository.flush();
        try {
            reindexFromRows(docId);
        } catch (Exception e) {
            log.warn("分块删除后重建索引失败 docId={} reason={}", docId, e.getMessage());
        }
    }

    /**
     * 重新生成分块：前端三种策略名保持不变，映射到新流水线的策略覆盖参数。
     */
    public List<DocumentChunkDTO> regenerateChunks(Long documentId, ChunkStrategy strategy) {
        KnowledgeDocument document = documentRepository.findById(documentId)
                .orElseThrow(() -> new RuntimeException("文档不存在，ID: " + documentId));

        chunkIndexingService.indexNow(document, ChunkPolicy.of(strategy));

        List<DocumentChunk> saved = chunkRepository.findByDocumentIdOrderByChunkIndex(documentId);
        return saved.stream().map(c -> toDTO(c, document.getTitle())).collect(Collectors.toList());
    }

    /** 批量更新分块顺序，并同步重建受影响文档的索引（保证 prev/next 与父块关系正确） */
    public void updateChunkOrder(List<Long> chunkIds) {
        Set<Long> affectedDocs = new LinkedHashSet<>();
        for (int i = 0; i < chunkIds.size(); i++) {
            Long chunkId = chunkIds.get(i);
            DocumentChunk chunk = chunkRepository.findById(chunkId)
                    .orElseThrow(() -> new RuntimeException("分块不存在，ID: " + chunkId));
            chunk.setChunkIndex(i);
            chunkRepository.save(chunk);
            affectedDocs.add(chunk.getDocument().getId());
        }
        chunkRepository.flush();
        for (Long docId : affectedDocs) {
            try {
                reindexFromRows(docId);
            } catch (Exception e) {
                log.warn("排序后重建索引失败 docId={} reason={}", docId, e.getMessage());
            }
        }
    }

    /**
     * 以 document_chunks 表内行为事实源重建送嵌文本并统一索引。
     * 用于分块编辑 / 删除 / 排序这些「不重新切分全文」的操作。
     */
    private void reindexFromRows(Long docId) {
        KnowledgeDocument doc = documentRepository.findById(docId)
                .orElseThrow(() -> new RuntimeException("文档不存在，ID: " + docId));
        List<DocumentChunk> rows = chunkRepository.findByDocumentIdOrderByChunkIndex(docId);
        String title = PromptInjectionGuard.sanitizeLabel(
                doc.getTitle() == null ? "未命名文档" : doc.getTitle());

        List<Chunk> chunks = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            DocumentChunk row = rows.get(i);
            String embedded = chunkPipeline.embedText(title, row.getSectionPath(), row.getContent());
            String hash = ChunkPipeline.sha256(embedded);
            int tokens = tokenizer.estimateTokenCountInText(embedded);
            chunks.add(Chunk.builder()
                    .documentId(docId)
                    .documentTitle(title)
                    .index(i)
                    .chunkKey("doc_" + docId + "_" + i + "_" + hash.substring(0, 8))
                    .sectionPath(row.getSectionPath())
                    .content(row.getContent())
                    .embeddedText(embedded)
                    .contentHash(hash)
                    .tokenCount(tokens)
                    .truncated(tokens > props.getMaxTokens() * 1.2)
                    .build());
        }
        chunkPipeline.relink(chunks);
        ChunkIndex.IndexResult result;
        try {
            result = chunkIndex.reindex(docId, chunks);
        } catch (Exception e) {
            log.warn("分块编辑后向量重建失败，表数据仍照常保存 docId={} reason={}",
                    docId, e.getMessage());
            result = new ChunkIndex.IndexResult(List.of(), 0);
        }

        List<String> ids = result.getVectorIds();
        for (int i = 0; i < rows.size(); i++) {
            Chunk c = chunks.get(i);
            DocumentChunk row = rows.get(i);
            row.setChunkIndex(i);
            row.setContentHash(c.getContentHash());
            row.setSectionPath(c.getSectionPath());
            row.setTokenCount(c.getTokenCount());
            row.setParentId(c.getParentKey());
            row.setChunkVersion(props.getActiveVersion());
            row.setActive(Boolean.TRUE);
            row.setVectorDimension(Math.max(result.getDimension(), 1));
            row.setVectorId(i < ids.size() ? ids.get(i) : null);
            row.setMetadata(chunkIndexingService.metadataJson(c));
        }
        chunkRepository.saveAll(rows);
    }

    private DocumentChunkDTO toDTO(DocumentChunk chunk, String documentTitle) {
        Boolean truncated = false;
        if (chunk.getTokenCount() != null) {
            truncated = chunk.getTokenCount() > props.getMaxTokens() * 1.2;
        }
        return DocumentChunkDTO.builder()
                .id(chunk.getId())
                .documentId(chunk.getDocument().getId())
                .documentTitle(documentTitle)
                .content(chunk.getContent())
                .chunkIndex(chunk.getChunkIndex())
                .metadata(chunk.getMetadata())
                .sectionPath(chunk.getSectionPath())
                .contentHash(chunk.getContentHash())
                .tokenCount(chunk.getTokenCount())
                .chunkVersion(chunk.getChunkVersion())
                .parentId(chunk.getParentId())
                .truncated(truncated)
                .createdAt(chunk.getCreatedAt())
                .build();
    }
}
