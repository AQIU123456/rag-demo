package com.example.aichat.service;

import com.example.aichat.config.ChunkProperties;
import com.example.aichat.domain.chunk.Chunk;
import com.example.aichat.domain.chunk.ChunkHit;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.Tokenizer;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 唯一索引入口：幂等 upsert、墓碑删除、版本/权限过滤、BM25+RRF 混合召回、多样性控制。
 *
 * <p><b>硬约束</b>：LangChain4j 0.29.1 的 {@code EmbeddingStore} 没有任何 remove 接口，
 * 因此本实现用「向量 id 墓碑 + liveIndex 白名单」做逻辑删除。只有经 {@link #reindex}
 * 写入的向量才对检索可见，任何绕过流水线直接 add 的旧向量自动不可见。
 * 切到 Milvus/Qdrant 后只需在 {@link #removeByDocId} 增加物理删除分支。</p>
 */
@Service
public class ChunkIndex {

    private static final Logger log = LoggerFactory.getLogger(ChunkIndex.class);

    private final EmbeddingStore<TextSegment> store;
    private final EmbeddingModel embeddingModel;
    private final ChunkProperties props;
    private final Bm25Scorer bm25Scorer;
    private final Tokenizer tokenizer;

    /** docId -> 当前生效的向量 id 列表 */
    private final Map<Long, List<String>> docVectors = new ConcurrentHashMap<>();
    /** 已逻辑删除的向量 id */
    private final Set<String> tombstones = ConcurrentHashMap.newKeySet();
    /** 当前生效向量 id -> 索引条目（检索白名单 + 邻居回溯依据） */
    private final Map<String, IndexedChunk> liveIndex = new ConcurrentHashMap<>();
    /** docId -> 该文档当前送嵌文本快照（用于启动重建诊断） */
    private final Map<Long, List<Chunk>> docChunks = new ConcurrentHashMap<>();

    public ChunkIndex(EmbeddingStore<TextSegment> store,
                      EmbeddingModel embeddingModel,
                      ChunkProperties props,
                      Bm25Scorer bm25Scorer,
                      Tokenizer tokenizer) {
        this.store = store;
        this.embeddingModel = embeddingModel;
        this.props = props;
        this.bm25Scorer = bm25Scorer;
        this.tokenizer = tokenizer;
    }

    /**
     * 幂等重建：同一文档永远只保留最新一批向量。
     * 先完成外部嵌入调用（可能失败），成功后再切换内部状态，避免 API 失败导致索引被清空。
     */
    public synchronized IndexResult reindex(Long docId, List<Chunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            removeByDocId(docId);
            return new IndexResult(List.of(), 0);
        }
        List<TextSegment> segments = new ArrayList<>(chunks.size());
        for (Chunk c : chunks) {
            segments.add(TextSegment.from(c.getEmbeddedText(), metadataOf(docId, c)));
        }
        List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
        if (embeddings == null || embeddings.size() != segments.size()) {
            throw new IllegalStateException("嵌入结果数量与分块数量不一致，docId=" + docId);
        }

        removeByDocId(docId);
        List<String> ids = new ArrayList<>(embeddings.size());
        for (int i = 0; i < embeddings.size(); i++) {
            String id = store.add(embeddings.get(i), segments.get(i));
            ids.add(id);
            liveIndex.put(id, new IndexedChunk(id, chunks.get(i), segments.get(i)));
        }
        docVectors.put(docId, ids);
        docChunks.put(docId, new ArrayList<>(chunks));
        log.debug("索引重建完成 docId={} vectors={}", docId, ids.size());
        return new IndexResult(ids, embeddings.get(0).dimension());
    }

    private Metadata metadataOf(Long docId, Chunk c) {
        Metadata md = new Metadata();
        // source 是既有 hybridSearch / RagService 依赖的键，必须保留 doc_<id> 格式
        md.put("source", "doc_" + docId);
        md.put("doc_id", String.valueOf(docId));
        md.put("doc_title", safe(c.getDocumentTitle()));
        md.put("chunk_key", safe(c.getChunkKey()));
        md.put("chunk_index", c.getIndex());
        md.put("section_path", safe(c.getSectionPath()));
        md.put("chunk_hash", safe(c.getContentHash()));
        md.put("chunk_version", props.getActiveVersion());
        md.put("active", "true");
        md.put("token_count", c.getTokenCount());
        md.put("parent_key", safe(c.getParentKey()));
        md.put("prev_key", safe(c.getPrevKey()));
        md.put("next_key", safe(c.getNextKey()));
        md.put("tenant_id", safe(props.getCurrentTenant()));
        md.put("acl_tag", "public");
        return md;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    /** 逻辑删除：0.29.1 无 remove，用向量 id 墓碑 + liveIndex 移除 */
    public void removeByDocId(Long docId) {
        List<String> ids = docVectors.remove(docId);
        docChunks.remove(docId);
        if (ids == null) {
            return;
        }
        tombstones.addAll(ids);
        for (String id : ids) {
            liveIndex.remove(id);
        }
    }

    public List<String> vectorIdsOf(Long docId) {
        return docVectors.getOrDefault(docId, List.of());
    }

    public int liveVectorCount() {
        return liveIndex.size();
    }

    public long tombstoneCount() {
        return tombstones.size();
    }

    /** 兼容方案中的签名 */
    public List<ChunkHit> search(Embedding query, int k) {
        return search(null, query, k);
    }

    /**
     * 混合召回：向量粗排 -> BM25 关键词通道 -> RRF 融合 -> 版本/权限/墓碑过滤
     * -> 按 doc_id 多样性限流 -> 父块/相邻块扩展。
     */
    public List<ChunkHit> search(String queryText, Embedding queryEmbedding, int k) {
        if (k <= 0 || queryEmbedding == null) {
            return List.of();
        }
        int recall = Math.max(k * props.getRecallMultiplier(), k + 20);
        List<EmbeddingMatch<TextSegment>> vectorMatches =
                store.findRelevant(queryEmbedding, recall, props.getMinScore());

        Map<String, Integer> vectorRank = new LinkedHashMap<>();
        Map<String, IndexedChunk> candidates = new LinkedHashMap<>();
        int rank = 1;
        for (EmbeddingMatch<TextSegment> m : vectorMatches) {
            IndexedChunk ic = liveIndex.get(m.embeddingId());
            if (ic == null || tombstones.contains(m.embeddingId()) || !visible(ic)) {
                continue;
            }
            ic.vectorScore = m.score();
            candidates.put(ic.vectorId, ic);
            vectorRank.put(ic.vectorId, rank++);
        }

        Map<String, Integer> keywordRank = new LinkedHashMap<>();
        Map<String, Double> bm25Scores = new HashMap<>();
        if (props.isBm25Enabled() && queryText != null && !queryText.isBlank()) {
            List<Bm25Scorer.Doc> corpus = liveIndex.values().stream()
                    .filter(this::visible)
                    .map(ic -> new Bm25Scorer.Doc(ic.vectorId, ic.chunk.getContent()))
                    .collect(Collectors.toList());
            Map<String, Double> scored = bm25Scorer.score(queryText, corpus);
            int kr = 1;
            for (Map.Entry<String, Double> e : scored.entrySet()) {
                if (kr > props.getKeywordCandidates()) {
                    break;
                }
                keywordRank.put(e.getKey(), kr++);
                bm25Scores.put(e.getKey(), e.getValue());
                IndexedChunk ic = liveIndex.get(e.getKey());
                if (ic != null && !candidates.containsKey(ic.vectorId)) {
                    candidates.put(ic.vectorId, ic);
                }
            }
        }

        List<ChunkHit> fused = new ArrayList<>();
        for (IndexedChunk ic : candidates.values()) {
            int vr = vectorRank.getOrDefault(ic.vectorId, 0);
            int kr = keywordRank.getOrDefault(ic.vectorId, 0);
            double score = props.isBm25Enabled()
                    ? rrf(vr, kr)
                    : ic.vectorScore;
            fused.add(ChunkHit.builder()
                    .vectorId(ic.vectorId)
                    .content(ic.chunk.getContent())
                    .contextText(ic.segment.text())
                    .metadata(ic.segment.metadata())
                    .vectorScore(ic.vectorScore)
                    .bm25Score(bm25Scores.getOrDefault(ic.vectorId, 0d))
                    .fusedScore(score)
                    .vectorRank(vr)
                    .keywordRank(kr)
                    .build());
        }
        fused.sort(Comparator.comparingDouble(ChunkHit::getFusedScore).reversed());

        List<ChunkHit> diversified = diversify(fused, k);
        for (ChunkHit hit : diversified) {
            IndexedChunk ic = liveIndex.get(hit.getVectorId());
            hit.setContextText(buildContextText(ic));
        }
        if (props.isObservability()) {
            log.debug("RETRIEVAL-METRIC vectorCandidates={} bm25Candidates={} returned={} "
                            + "tombstones={} liveVectors={}",
                    vectorRank.size(), keywordRank.size(), diversified.size(),
                    tombstones.size(), liveIndex.size());
        }
        return diversified;
    }

    private double rrf(int vectorRank, int keywordRank) {
        double k = props.getRrfK();
        double score = 0d;
        if (vectorRank > 0) {
            score += 1.0 / (k + vectorRank);
        }
        if (keywordRank > 0) {
            score += 1.0 / (k + keywordRank);
        }
        return score;
    }

    private boolean visible(IndexedChunk ic) {
        Metadata md = ic.segment.metadata();
        if (!props.getActiveVersion().equals(md.getString("chunk_version"))) {
            return false;
        }
        if (!"true".equals(md.getString("active"))) {
            return false;
        }
        if (!props.isTenantFilterEnabled()) {
            return true;
        }
        String tenant = md.getString("tenant_id");
        String acl = md.getString("acl_tag");
        return props.getCurrentTenant().equals(tenant)
                && ("public".equals(acl) || props.getCurrentAclTag().equals(acl));
    }

    /** 按 chunk_hash 去重 + 单文档最多 maxPerDocument 条，避免 topK 被同一文档霸占 */
    private List<ChunkHit> diversify(List<ChunkHit> hits, int k) {
        Set<String> seenHash = new HashSet<>();
        Map<String, Integer> perDoc = new HashMap<>();
        List<ChunkHit> result = new ArrayList<>();
        for (ChunkHit hit : hits) {
            String hash = hit.getMetadata().getString("chunk_hash");
            if (hash != null && !seenHash.add(hash)) {
                continue;
            }
            String docId = hit.getMetadata().getString("doc_id");
            int used = perDoc.getOrDefault(docId, 0);
            if (props.getMaxPerDocument() > 0 && used >= props.getMaxPerDocument()) {
                continue;
            }
            perDoc.put(docId, used + 1);
            result.add(hit);
            if (result.size() >= k) {
                break;
            }
        }
        return result;
    }

    /**
     * 检索单元 ≠ 生成单元：父子模式回溯整个父块，否则按 neighborWindow 扩展相邻块。
     */
    private String buildContextText(IndexedChunk ic) {
        if (ic == null) {
            return "";
        }
        List<Chunk> siblings = docChunks.get(ic.chunk.getDocumentId());
        if (siblings == null || siblings.isEmpty()) {
            return ic.segment.text();
        }
        List<Chunk> group = new ArrayList<>();
        if (props.isParentChild() && ic.chunk.getParentKey() != null
                && !ic.chunk.getParentKey().isBlank()) {
            for (Chunk c : siblings) {
                if (ic.chunk.getParentKey().equals(c.getParentKey())) {
                    group.add(c);
                }
            }
        } else if (props.getNeighborWindow() > 0) {
            int from = Math.max(0, ic.chunk.getIndex() - props.getNeighborWindow());
            int to = Math.min(siblings.size() - 1, ic.chunk.getIndex() + props.getNeighborWindow());
            for (int i = from; i <= to; i++) {
                group.add(siblings.get(i));
            }
        }
        if (group.size() <= 1) {
            return ic.segment.text();
        }
        StringBuilder sb = new StringBuilder(ic.segment.text());
        int budget = props.getParentMaxTokens();
        for (Chunk c : group) {
            if (c.getIndex() == ic.chunk.getIndex()) {
                continue;
            }
            int cost = tokenizer.estimateTokenCountInText(c.getContent());
            if (cost > budget) {
                break;
            }
            budget -= cost;
            sb.append('\n').append(c.getContent());
        }
        return sb.toString();
    }

    /** 重建结果，供落表时回填 vector_id 与向量维度 */
    public static class IndexResult {
        private final List<String> vectorIds;
        private final int dimension;

        public IndexResult(List<String> vectorIds, int dimension) {
            this.vectorIds = vectorIds;
            this.dimension = dimension;
        }

        public List<String> getVectorIds() {
            return vectorIds;
        }

        public int getDimension() {
            return dimension;
        }
    }

    private static class IndexedChunk {
        private final String vectorId;
        private final Chunk chunk;
        private final TextSegment segment;
        private double vectorScore;

        private IndexedChunk(String vectorId, Chunk chunk, TextSegment segment) {
            this.vectorId = vectorId;
            this.chunk = chunk;
            this.segment = segment;
        }
    }
}
