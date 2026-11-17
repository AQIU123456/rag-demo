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

    /** 全局分块参数（块长、重叠、各阶段开关、索引版本），改配置即可 A/B 或回滚 */
    private final ChunkProperties props;

    /** 统一 token 计量器：块长判断、预算兜底、truncated 标记都以它为准 */
    private final Tokenizer tokenizer;

    public ChunkPipeline(ChunkProperties props, Tokenizer tokenizer) {
        this.props = props;
        this.tokenizer = tokenizer;
    }

    /** 用全局配置切分：上传、文档编辑、启动重建等常规路径走这里 */
    public List<Chunk> split(KnowledgeDocument doc) {
        return split(doc, ChunkPolicy.defaults());
    }

    /**
     * 按「本次策略覆盖 + 全局配置」执行完整切分。
     *
     * <p>policy 中为 null 的字段一律回落到 {@link ChunkProperties}，
     * 因此前端三种策略只需覆盖差异项，不会污染全局默认行为。</p>
     *
     * @param doc    待切分文档（content 为唯一事实源，title 参与上下文前缀）
     * @param policy 本次策略覆盖，可为 null
     * @return 有序的分块列表，已带 prev/next、父子键、hash 与 token 数
     */
    public List<Chunk> split(KnowledgeDocument doc, ChunkPolicy policy) {
        // 1. 解析本次生效参数：策略覆盖优先，未指定则取全局配置
        ChunkPolicy p = policy == null ? ChunkPolicy.defaults() : policy;
        int maxTokens = p.getMaxTokens() != null ? p.getMaxTokens() : props.getMaxTokens();
        int overlap = p.getOverlapTokens() != null ? p.getOverlapTokens() : props.getOverlapTokens();
        boolean structureAware = p.getStructureAware() != null
                ? p.getStructureAware() : props.isStructureAware();
        boolean contextPrefix = p.getContextPrefix() != null
                ? p.getContextPrefix() : props.isContextPrefix();
        boolean parentChild = p.getParentChild() != null
                ? p.getParentChild() : props.isParentChild();

        // 2. 组装切分器：按 token 计量的递归切分器做底座，结构感知器在其上按标题/条款先分段
        DocumentSplitter base = DocumentSplitters.recursive(maxTokens, overlap, tokenizer);
        DocumentSplitter splitter = structureAware
                ? new StructureAwareSplitter(base, props.getHardMaxChars())
                : base;

        // 3. 切分后过 token 预算硬兜底：0.29.1 的中文句子切分不可靠，必须二次限长
        String raw = doc.getContent() == null ? "" : doc.getContent();
        List<TextSegment> segments = raw.isBlank()
                ? List.of()
                : enforceTokenBudget(splitter.split(Document.from(raw)), maxTokens);

        // 标题来自用户输入，先做标签消毒，避免上下文前缀被注入结构标记
        String safeTitle = PromptInjectionGuard.sanitizeLabel(
                doc.getTitle() == null ? "未命名文档" : doc.getTitle());

        // 4. 逐个 segment 组装 Chunk：
        //    content = 原文（落 document_chunks.content，供展示与编辑）
        //    embeddedText = 带上下文前缀的送嵌文本（只进向量库，不落正文列）
        //    contentHash = sha256(embeddedText)，作为幂等短路与重复块检测的键
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

        // 5. 建立块间关系：相邻键用于上下文窗口扩展，父子键用于「子块检索、父块生成」
        linkNeighbors(chunks);
        if (parentChild) {
            linkParents(chunks, props.getParentMaxTokens());
        }
        // 6. 输出块质量指标（块长分布 / 截断率 / 重复率），供参数调优与线上观测
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
                // 按字符成本累加到预算上限：ASCII 0.25 token、其余 1 token，与 HeuristicTokenizer 口径一致
                while (end < text.length()) {
                    double cost = text.charAt(end) < 0x80 ? 0.25d : 1.0d;
                    if (acc + cost > maxTokens) {
                        break;
                    }
                    acc += cost;
                    end++;
                }
                if (end == start) {
                    // 单字符就超预算时的保底，避免死循环
                    end = start + 1;
                }
                // 复用原 metadata（含 section_path），否则二次切分后章节路径会丢失
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

    /**
     * 重建相邻 / 父子关系。供 document_chunks 表内行重建（分块编辑、删除、排序）后调用：
     * 这些路径不重新切分全文，但 prev/next/parentKey 必须与新顺序保持一致。
     */
    public void relink(List<Chunk> chunks) {
        linkNeighbors(chunks);
        if (props.isParentChild()) {
            linkParents(chunks, props.getParentMaxTokens());
        }
    }

    /** 线性串联相邻块：首块 prevKey=null、末块 nextKey=null */
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

    /** 把同一父块组内的子块统一指向组内首块的 chunkKey（parentKey 即父块的稳定引用） */
    private void assignParent(List<Chunk> chunks, int from, int to) {
        String parentKey = chunks.get(from).getChunkKey();
        for (int i = from; i <= to; i++) {
            chunks.get(i).setParentKey(parentKey);
        }
    }

    /**
     * 输出块质量指标。三个指标分别对应三类典型故障：
     * truncatedRate（块被模型静默截断）、duplicateRate（overlap 或切分器重复产出）、
     * maxTokensObserved（超出配置的异常块）。
     */
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

    /** 送嵌文本的 sha256（小写 hex），用作 content_hash 与 chunkKey 后缀 */
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
