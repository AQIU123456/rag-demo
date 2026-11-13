package com.example.aichat.service;

import com.example.aichat.config.ChunkProperties;
import com.example.aichat.domain.chunk.Chunk;
import com.example.aichat.domain.chunk.ChunkPolicy;
import com.example.aichat.domain.chunk.StructureAwareSplitter;
import com.example.aichat.entity.KnowledgeDocument;
import com.example.aichat.security.PromptInjectionGuard;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.Tokenizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * 唯一切分入口。所有写路径（上传 / 编辑 / 重新生成 / 启动重建）都必须经过这里，
 * 禁止任何 Service 再自行 new splitter 或直接调 embeddingStore.add()。
 */
@Service
public class ChunkPipeline {

    private static final Logger log = LoggerFactory.getLogger(ChunkPipeline.class);

    private final ChunkProperties props;
    private final Tokenizer tokenizer;

    public ChunkPipeline(ChunkProperties props, Tokenizer tokenizer) {
        this.props = props;
        this.tokenizer = tokenizer;
    }

    public List<Chunk> split(KnowledgeDocument doc) {
        return split(doc, ChunkPolicy.defaults());
    }

    public List<Chunk> split(KnowledgeDocument doc, ChunkPolicy policy) {
        ChunkPolicy p = policy == null ? ChunkPolicy.defaults() : policy;
        int maxTokens = p.getMaxTokens() != null ? p.getMaxTokens() : props.getMaxTokens();
        int overlap = p.getOverlapTokens() != null ? p.getOverlapTokens() : props.getOverlapTokens();
        boolean structureAware = p.getStructureAware() != null
                ? p.getStructureAware() : props.isStructureAware();
        boolean contextPrefix = p.getContextPrefix() != null
                ? p.getContextPrefix() : props.isContextPrefix();
        boolean parentChild = p.getParentChild() != null
                ? p.getParentChild() : props.isParentChild();

        DocumentSplitter base = DocumentSplitters.recursive(maxTokens, overlap, tokenizer);
        DocumentSplitter splitter = structureAware
                ? new StructureAwareSplitter(base, props.getHardMaxChars())
                : base;

        String raw = doc.getContent() == null ? "" : doc.getContent();
        List<TextSegment> segments = raw.isBlank()
                ? List.of()
                : enforceTokenBudget(splitter.split(Document.from(raw)), maxTokens);

        String safeTitle = PromptInjectionGuard.sanitizeLabel(
                doc.getTitle() == null ? "未命名文档" : doc.getTitle());

        List<Chunk> chunks = new ArrayList<>(segments.size());
        for (int i = 0; i < segments.size(); i++) {
            TextSegment seg = segments.get(i);
            String content = seg.text();
            String path = seg.metadata().getString("section_path");
            String embeddedText = buildEmbeddedText(safeTitle, path, content, contextPrefix);
            int tokens = tokenizer.estimateTokenCountInText(embeddedText);
            String hash = sha256(embeddedText);
            chunks.add(Chunk.builder()
                    .documentId(doc.getId())
                    .documentTitle(safeTitle)
                    .index(i)
                    .chunkKey("doc_" + doc.getId() + "_" + i + "_" + hash.substring(0, 8))
                    .sectionPath(path)
                    .content(content)
                    .embeddedText(embeddedText)
                    .contentHash(hash)
                    .tokenCount(tokens)
                    .truncated(tokens > maxTokens * 1.2)
                    .build());
        }

        linkNeighbors(chunks);
        if (parentChild) {
            linkParents(chunks, props.getParentMaxTokens());
        }
        if (props.isObservability()) {
            logQuality(doc.getId(), chunks, maxTokens);
        }
        return chunks;
    }

    /**
     * token 预算硬兜底。
     *
     * <p>实测 LangChain4j 0.29.1 的层级切分器在「结构感知路径 + 无换行的中文长段落」上
     * 会产出约 3 倍于 maxSegmentSizeInTokens 的块（英文 OpenNLP 句子模型识别不了中文句读），
     * 直接送嵌入会被模型静默截断。这里按 tokenizer 估算做二次滑窗硬切，
     * 保证任何文档类型下块长都有确定的上限。</p>
     */
    private List<TextSegment> enforceTokenBudget(List<TextSegment> segments, int maxTokens) {
        List<TextSegment> result = new ArrayList<>(segments.size());
        for (TextSegment seg : segments) {
            String text = seg.text();
            if (text == null || text.isEmpty()
                    || tokenizer.estimateTokenCountInText(text) <= maxTokens) {
                result.add(seg);
                continue;
            }
            int start = 0;
            while (start < text.length()) {
                int end = start;
                double acc = 0d;
                while (end < text.length()) {
                    double cost = text.charAt(end) < 0x80 ? 0.25d : 1.0d;
                    if (acc + cost > maxTokens) {
                        break;
                    }
                    acc += cost;
                    end++;
                }
                if (end == start) {
                    end = start + 1;
                }
                result.add(TextSegment.from(text.substring(start, end), seg.metadata().copy()));
                start = end;
            }
        }
        return result;
    }

    /**
     * 用全局配置构造送嵌文本。供「分块编辑 / 删除」路径从表内行重建时使用，
     * 保证手工覆盖的块与流水线产出的块采用完全一致的前缀规则与 hash 口径。
     */
    public String embedText(String title, String sectionPath, String content) {
        return buildEmbeddedText(
                PromptInjectionGuard.sanitizeLabel(title == null ? "未命名文档" : title),
                sectionPath, content, props.isContextPrefix());
    }

    private String buildEmbeddedText(String safeTitle, String sectionPath,
                                     String content, boolean contextPrefix) {
        if (!contextPrefix) {
            return content;
        }
        // 前缀属于不可信数据：标题/路径均先过 sanitizeLabel（结构标记被转义）
        String safePath = (sectionPath == null || sectionPath.isBlank())
                ? "" : " > " + PromptInjectionGuard.sanitizeLabel(sectionPath);
        return "[" + safeTitle + safePath + "]\n" + content;
    }

    /** 重建相邻/父子关系（供表内行重建后调用） */
    public void relink(List<Chunk> chunks) {
        linkNeighbors(chunks);
        if (props.isParentChild()) {
            linkParents(chunks, props.getParentMaxTokens());
        }
    }

    private void linkNeighbors(List<Chunk> chunks) {
        for (int i = 0; i < chunks.size(); i++) {
            chunks.get(i).setPrevKey(i > 0 ? chunks.get(i - 1).getChunkKey() : null);
            chunks.get(i).setNextKey(i + 1 < chunks.size() ? chunks.get(i + 1).getChunkKey() : null);
        }
    }

    /** 按 parentMaxTokens 聚合相邻子块，同组共享 parentKey（指向组内首块） */
    private void linkParents(List<Chunk> chunks, int parentMaxTokens) {
        int start = 0;
        int accumulated = 0;
        for (int i = 0; i < chunks.size(); i++) {
            int t = chunks.get(i).getTokenCount();
            if (accumulated + t > parentMaxTokens && i > start) {
                assignParent(chunks, start, i - 1);
                start = i;
                accumulated = 0;
            }
            accumulated += t;
        }
        if (start < chunks.size()) {
            assignParent(chunks, start, chunks.size() - 1);
        }
    }

    private void assignParent(List<Chunk> chunks, int from, int to) {
        String parentKey = chunks.get(from).getChunkKey();
        for (int i = from; i <= to; i++) {
            chunks.get(i).setParentKey(parentKey);
        }
    }

    private void logQuality(Long docId, List<Chunk> chunks, int maxTokens) {
        if (chunks.isEmpty()) {
            log.info("CHUNK-METRIC docId={} chunks=0", docId);
            return;
        }
        long truncated = chunks.stream().filter(Chunk::isTruncated).count();
        long distinctHash = chunks.stream().map(Chunk::getContentHash).distinct().count();
        double avg = chunks.stream().mapToInt(Chunk::getTokenCount).average().orElse(0);
        int max = chunks.stream().mapToInt(Chunk::getTokenCount).max().orElse(0);
        log.info("CHUNK-METRIC docId={} chunks={} maxTokensConfig={} avgTokens={} "
                        + "maxTokensObserved={} truncated={} truncationRate={} duplicateRate={}",
                docId, chunks.size(), maxTokens, String.format("%.1f", avg), max, truncated,
                String.format("%.4f", (double) truncated / chunks.size()),
                String.format("%.4f", 1.0 - (double) distinctHash / chunks.size()));
    }

    public static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
