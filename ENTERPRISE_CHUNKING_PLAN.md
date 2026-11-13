# 企业级 RAG 分块改造方案（可执行版）

| 项 | 内容 |
|---|---|
| 版本 | v1.0 / 2026-11-13 |
| 适用工程 | `D:/proj/rag-demo/ai-chat-backend`（Spring Boot 3.2 + LangChain4j 0.29.1） |
| 目标 | 把演示级「按字符硬切 + 双轨不同步」改造为结构感知、可追溯、可增量、可评估的企业级分块流水线 |
| 交付边界 | **本文件是方案，不含代码改动**；确认后再按 Sprint 落地 |
| 编码原则 | 非侵入式：保留全部 Controller 路径与现有方法签名，只新增类 + 在 Service 内部替换实现 |

---

## 0. 已验证的事实基线（动手前必读）

### 0.1 环境基线（已实测）

| 项 | 实测值 |
|---|---|
| JDK | OpenJDK 17.0.1（`D:\devTool\openjdk17`） |
| Maven | 3.9.11（`D:\devTool\apache-maven-3.9.11`） |
| 本地仓库 | `D:\devTool\apache-maven-3.9.11\repository`（**不是** `~/.m2/repository`） |
| 离线编译 | `mvn -o -q -DskipTests compile` → **EXIT=0，已通过** |
| 依赖 jar | langchain4j / langchain4j-core / langchain4j-open-ai 均 0.29.1，含 `-sources.jar` |

> 注意：本项目是 **JDK 17**，与 qifu-erp 的 JDK 11 链路不是同一套环境，不要套用那边的 `JAVA_HOME`。

### 0.2 LangChain4j 0.29.1 API 实测结论（用 `javap` + sources jar 核对过）

| 能力 | 结论 | 对方案的约束 |
|---|---|---|
| `DocumentSplitters.recursive(int,int)` | 按**字符**，段落→行→句→词递归，重叠只取完整句 | 现有实现用的是这个 |
| `DocumentSplitters.recursive(int,int,Tokenizer)` | **存在，按 token 计量** | P0 可直接切到 token 计量，无需新依赖 |
| `DocumentByRegexSplitter / ByParagraphSplitter / ByLineSplitter / BySentenceSplitter` | **都存在** | 结构感知切分可复用 |
| `Tokenizer` | 接口，5 个抽象方法 | 可自实现，避免引入 jtokkit |
| `EmbeddingStore.add(Embedding, TextSegment)` | **返回 `String` 向量 id** | 可建立 `chunk_id ↔ vector_id` 映射 |
| `EmbeddingMatch.embeddingId()` | **存在** | 检索结果可按 id 过滤 |
| `EmbeddingStore.findRelevant(Embedding,int,double)` / `InMemoryEmbeddingStore.search()` | 支持 `Filter`（基于 metadata） | 可做版本/权限/软删除过滤 |
| **`EmbeddingStore.remove* / removeAll`** | **0.29.1 完全没有删除接口** | ⚠️ **不能写「按 doc_id 删向量」这种代码，编译不过** |
| `Metadata.put/copy/getString/asMap` | 存在；`toString()` 不是 JSON | 现有 `metadata(segment.metadata().toString())` 必须改掉 |

**这条硬约束决定了 P0 的技术路线**：不升级依赖、不联网拉 Milvus，先自建 `ChunkIndex` 服务层，用「向量 id 墓碑 + 版本/active 元数据过滤」实现逻辑删除与幂等重建；物理删除留到 Sprint 3 换持久化向量库时做。这是当前依赖下唯一能同时满足「闭环」和「离线可编译」的做法。

### 0.3 代码现状定位（上一轮走读结果）

| 文件:行 | 现状 |
|---|---|
| `EmbeddingService.java:42` | 主链路 `recursive(400, 80)`，硬编码 |
| `DocumentChunkService.java:51-64` | 另有 `512/100`、`300/150`、`256/50` 三套参数 |
| `DocumentChunkService.java:140-179` | 重分块只删 DB 记录，向量只 add 不删 |
| `DocumentChunkService.java:200-254` | `SEMANTIC` = 空格分词 Jaccard，中文失效 |
| `DocumentChunkService.java:259-273` | 编辑分块只 add 新向量 |
| `KnowledgeDocumentService.java:42-55` | `@PostConstruct` 每次启动全文重嵌一遍 |
| `KnowledgeDocumentService.java:85` | 上传不写 `document_chunks` 表 |
| `KnowledgeDocumentService.java:387` | 删除文档不删向量 |
| `EmbeddingStoreConfig.java:22` | `InMemoryEmbeddingStore` |
| `DocumentChunk.java:21-65` | 无 `parent_id / vector_id / content_hash / version` |
| `KNOWLEDGE_BASE_PHASE2.md:28` | 文档写 500/50，与代码 400/80 不符 |

---

## 1. 目标架构与验收指标

### 1.1 三条架构原则

1. **单一事实源**：`document_chunks` 表是唯一分块事实源，向量库只是派生物，可随时从表重建。
2. **单一入口**：所有写路径（上传 / 编辑文档 / 编辑分块 / 重新生成 / 删除 / 启动重建）统一走 `ChunkPipeline` + `ChunkIndex`，禁止任何 Service 直接调 `embeddingStore.add()`。
3. **检索单元 ≠ 生成单元**：子块（~300 token）做向量匹配，父块 / 相邻块做上下文补全。

### 1.2 量化验收指标

| 指标 | 现状 | 目标 | 测量方式 |
|---|---|---|---|
| 同一文档向量份数 | 1~4 份（随操作累积） | **恒为 1 份** | `ChunkIndex` 计数 + 冒烟脚本 |
| 删除后仍可召回（幽灵向量） | 存在 | **0** | 自动化测试 |
| 重复上传产生的新向量 | 每次 +N | **0（hash 幂等）** | 单元测试 |
| 块长超嵌入上限比例（截断率） | 不可测 | **< 1%** | `token_count` 统计 |
| 中文长文本切分块数 | 可能被 `SEMANTIC` 并成 1 块 | 按结构/长度合理分布 | `ChunkPipelineTest` |
| 引用可定位到章节 | 只有 `doc_1` | `docId + chunkId + sectionPath` | RAG 接口响应 |
| RAGAS `context_recall` | 未测 | 相对 baseline 不下降，目标 +10% | `rag-eval` A/B |

### 1.3 非目标（本方案不做）

多模态/扫描件 OCR、Agentic chunking、Late chunking、多租户完整鉴权体系（只预留字段）、前端 UI 重构。

---

## 2. 改造路线总览

| Sprint | 目标 | 主要产出 | 验收 |
|---|---|---|---|
| **S0** | 预检与分支 | 基线编译、测试探针、回滚点 | `mvn -o ... compile` EXIT=0 |
| **S1（P0）** | 链路收敛 + 生命周期闭环 | DDL、`ChunkProperties`、`HeuristicTokenizer`、`ChunkPipeline`、`ChunkIndex`、5 处 Service 改造 | 单测 + API 冒烟全部通过 |
| **S2（P1）** | 结构感知 + 上下文增强 + 父子索引 | `StructureAwareSplitter`、上下文前缀、parent/child、相邻块扩展、引用 DTO 扩展 | RAGAS A/B 达标 |
| **S3（P1/P2）** | 混合召回、权限、观测、持久化 | BM25+RRF、MMR、`active/tenant` 过滤、指标日志、Milvus 切换 | 生产级检查表 |

---

## 3. Sprint 0：预检与回滚点

```bash
cd /d/proj/rag-demo/ai-chat-backend

# 1. 基线（已实测通过）
mvn -o -q -DskipTests compile ; echo "EXIT=$?"

# 2. 基线测试（确认现有回归套件是绿的）
mvn -o test -Dtest=PromptInjectionRegressionTest,RerankServiceTest

# 3. 建分支 / 打回滚点
git checkout -b feat/enterprise-chunking
```

**API 预检探针**（S1 编码前先跑一次，避免签名猜测）：

```bash
CORE=/d/devTool/apache-maven-3.9.11/repository/dev/langchain4j/langchain4j-core/0.29.1/langchain4j-core-0.29.1.jar
MAIN=/d/devTool/apache-maven-3.9.11/repository/dev/langchain4j/langchain4j/0.29.1/langchain4j-0.29.1.jar
javap -classpath "$CORE:$MAIN" dev.langchain4j.store.embedding.EmbeddingStore
javap -classpath "$CORE:$MAIN" dev.langchain4j.data.document.splitter.DocumentSplitters
```

> 预期看到 `add(Embedding, Embedded)` 返回 `String`、**没有** `remove`，以及 `recursive(int,int,Tokenizer)` 重载。

---

## 4. Sprint 1（P0）：链路收敛 + 生命周期闭环

### 4.1 DDL（新建 `sql/20261113_chunk_pipeline.sql`）

```sql
-- DDL：分块表扩展为可追溯索引单元
-- 注意：metadata 现存的是 Metadata.toString()（非合法 JSON），先清洗再改类型
UPDATE document_chunks SET metadata = NULL WHERE metadata IS NOT NULL;
ALTER TABLE document_chunks MODIFY COLUMN metadata TEXT NULL;

ALTER TABLE document_chunks
  ADD COLUMN vector_id      VARCHAR(64)  NULL COMMENT '向量库条目 id',
  ADD COLUMN parent_id      VARCHAR(64)  NULL COMMENT '父块 chunk_key（S2 使用）',
  ADD COLUMN section_path   VARCHAR(512) NULL COMMENT '标题/条款路径',
  ADD COLUMN content_hash   CHAR(64)     NULL COMMENT 'sha256(embeddedText)',
  ADD COLUMN chunk_version  VARCHAR(32)  NOT NULL DEFAULT 'v1',
  ADD COLUMN token_count    INT          NOT NULL DEFAULT 0,
  ADD COLUMN active         TINYINT(1)   NOT NULL DEFAULT 1,
  ADD COLUMN tenant_id      VARCHAR(64)  NULL,
  ADD COLUMN acl_tag        VARCHAR(128) NULL,
  ADD COLUMN embedding_model VARCHAR(128) NULL,
  ADD INDEX idx_chunk_doc_active (document_id, active, chunk_version),
  ADD INDEX idx_chunk_hash (document_id, content_hash);
```

配套 `sql/20261113_chunk_pipeline_init.sql`（存量数据回填，按双文件约定）：

```sql
-- init data：存量分块标为旧版本，由启动重建任务按新链路重新索引
UPDATE document_chunks SET chunk_version = 'legacy', active = 0
WHERE content_hash IS NULL;
```

> `spring.jpa.hibernate.ddl-auto: update` 会自动加列，但**不要让 Hibernate 隐式改生产表类型**；上述脚本显式执行并纳入变更单。

### 4.2 新增类清单

| 新增文件 | 职责 |
|---|---|
| `config/ChunkProperties.java` | 分块参数配置化 |
| `domain/chunk/Chunk.java` | 流水线中间模型 |
| `domain/chunk/HeuristicTokenizer.java` | 零依赖 Tokenizer（中英混排估算） |
| `domain/chunk/StructureAwareSplitter.java` | 标题/条款边界切分（S2 可先置开关关闭） |
| `service/ChunkPipeline.java` | 唯一切分入口：切分 + 上下文前缀 + hash |
| `service/ChunkIndex.java` | 唯一索引入口：幂等 upsert / 墓碑删除 / 过滤检索 |
| `test/.../ChunkPipelineTest.java` | 单元与回归测试 |

### 4.3 配置（`application.yml` 追加，不改既有键）

```yaml
chunk:
  max-tokens: 300
  overlap-tokens: 40
  parent-max-tokens: 900
  hard-max-chars: 4000
  structure-aware: false   # S1 保持基线，S2 打开为 true（A/B 的对照组）
  context-prefix: false     # S1 保持基线，S2 打开为 true
  active-version: v1
  recall-multiplier: 6
  min-score: 0.0
```

> S1 两个开关都为 `false`，目的是先把「单一入口 + 生命周期」验证干净，不与策略效果混在一起；S2 再逐个打开，正好形成第 7 节的 A/B 对照。

`AiChatApplication.java` 追加一行以启用配置绑定：

```java
@SpringBootApplication
@EnableConfigurationProperties(ChunkProperties.class)   // 新增
public class AiChatApplication { ... }
```

`config/ChunkProperties.java`：

```java
package com.example.aichat.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "chunk")
public class ChunkProperties {
    private int maxTokens = 300;
    private int overlapTokens = 40;
    private int parentMaxTokens = 900;
    private int hardMaxChars = 4000;
    private boolean structureAware = false;
    private boolean contextPrefix = false;
    private String activeVersion = "v1";
    private int recallMultiplier = 6;
    private double minScore = 0.0;
}
```

### 4.4 核心代码（可直接落地，已按 0.29.1 签名校准）

**(0) `domain/chunk/Chunk.java`** —— 流水线中间模型，`content` 落库、`embeddedText` 送嵌入，两者必须分开：

```java
package com.example.aichat.domain.chunk;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class Chunk {
    private Long documentId;
    private int index;
    private String sectionPath;
    /** 原文：落 document_chunks.content，展示与编辑用 */
    private String content;
    /** 带上下文前缀：仅送 embedding，不落正文列 */
    private String embeddedText;
    private String contentHash;
    private String parentKey;
    private int tokenCount;
    private boolean truncated;
}
```

**(1) `domain/chunk/HeuristicTokenizer.java`** —— 不引入 jtokkit，中文 1 字≈1 token、ASCII 4 字符≈1 token，保守上取整：

```java
package com.example.aichat.domain.chunk;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.Tokenizer;
import org.springframework.stereotype.Component;

@Component
public class HeuristicTokenizer implements Tokenizer {

    @Override
    public int estimateTokenCountInText(String text) {
        if (text == null || text.isEmpty()) return 0;
        double tokens = 0d;
        for (int i = 0; i < text.length(); i++) {
            tokens += text.charAt(i) < 0x80 ? 0.25d : 1.0d;
        }
        return (int) Math.ceil(tokens);
    }

    @Override
    public int estimateTokenCountInMessage(ChatMessage message) {
        return message == null ? 0 : estimateTokenCountInText(String.valueOf(message));
    }

    @Override
    public int estimateTokenCountInMessages(Iterable<ChatMessage> messages) {
        if (messages == null) return 0;
        int total = 0;
        for (ChatMessage m : messages) total += estimateTokenCountInMessage(m);
        return total;
    }

    @Override
    public int estimateTokenCountInToolSpecifications(Iterable<ToolSpecification> specs) {
        if (specs == null) return 0;
        int total = 0;
        for (ToolSpecification s : specs) total += estimateTokenCountInText(String.valueOf(s));
        return total;
    }

    @Override
    public int estimateTokenCountInToolExecutionRequests(Iterable<ToolExecutionRequest> requests) {
        if (requests == null) return 0;
        int total = 0;
        for (ToolExecutionRequest r : requests) total += estimateTokenCountInText(String.valueOf(r));
        return total;
    }
}
```

**(2) `service/ChunkPipeline.java`** —— 唯一分块入口，产出「原文 + 送嵌文本 + hash」：

```java
package com.example.aichat.service;

import com.example.aichat.config.ChunkProperties;
import com.example.aichat.domain.chunk.Chunk;
import com.example.aichat.domain.chunk.StructureAwareSplitter;
import com.example.aichat.entity.KnowledgeDocument;
import com.example.aichat.security.PromptInjectionGuard;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.Tokenizer;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

@Service
public class ChunkPipeline {

    private final ChunkProperties props;
    private final Tokenizer tokenizer;

    public ChunkPipeline(ChunkProperties props, Tokenizer tokenizer) {
        this.props = props;
        this.tokenizer = tokenizer;
    }

    public List<Chunk> split(KnowledgeDocument doc) {
        DocumentSplitter base = DocumentSplitters.recursive(
                props.getMaxTokens(), props.getOverlapTokens(), tokenizer);
        DocumentSplitter splitter = props.isStructureAware()
                ? new StructureAwareSplitter(base, props.getHardMaxChars())
                : base;

        List<TextSegment> segments = splitter.split(Document.from(doc.getContent()));
        List<Chunk> chunks = new ArrayList<>(segments.size());

        for (int i = 0; i < segments.size(); i++) {
            TextSegment seg = segments.get(i);
            String content = seg.text();
            String path = seg.metadata().getString("section_path");
            String prefix = "";
            if (props.isContextPrefix()) {
                String title = PromptInjectionGuard.sanitizeLabel(doc.getTitle());
                String safePath = (path == null || path.isBlank())
                        ? "" : " > " + PromptInjectionGuard.sanitizeLabel(path);
                prefix = "[" + title + safePath + "]\n";
            }
            String embeddedText = prefix + content;
            int tokens = tokenizer.estimateTokenCountInText(embeddedText);
            chunks.add(Chunk.builder()
                    .documentId(doc.getId())
                    .index(i)
                    .sectionPath(path)
                    .content(content)
                    .embeddedText(embeddedText)
                    .contentHash(sha256(embeddedText))
                    .tokenCount(tokens)
                    .truncated(tokens > props.getMaxTokens() * 1.2)
                    .build());
        }
        return chunks;
    }

    private static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
```

> 安全要点：上下文前缀来自 `doc.title`（已 `sanitizeLabel`）与 `sectionPath`，**拼接后仍要当作不可信数据处理**；`RagService` 现有的 `<UNTRUSTED_DATA>` 定界与 `guardOutput` 保持不变，`prompt-injection-tests` 回归必须重跑。

**(3) `service/ChunkIndex.java`** —— 绕开 0.29.1 无删除 API 的关键实现：

```java
package com.example.aichat.service;

import com.example.aichat.config.ChunkProperties;
import com.example.aichat.domain.chunk.Chunk;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class ChunkIndex {

    private final EmbeddingStore<TextSegment> store;
    private final EmbeddingModel embeddingModel;
    private final ChunkProperties props;
    private final Map<Long, List<String>> docVectors = new ConcurrentHashMap<>();
    private final Set<String> tombstones = ConcurrentHashMap.newKeySet();

    public ChunkIndex(EmbeddingStore<TextSegment> store,
                      EmbeddingModel embeddingModel, ChunkProperties props) {
        this.store = store;
        this.embeddingModel = embeddingModel;
        this.props = props;
    }

    /** 幂等重建：同文档永远只保留最新一批向量 */
    public synchronized int reindex(Long docId, List<Chunk> chunks) {
        removeByDocId(docId);
        List<TextSegment> segments = new ArrayList<>(chunks.size());
        for (Chunk c : chunks) {
            Metadata md = new Metadata();
            md.put("doc_id", String.valueOf(docId));
            md.put("chunk_hash", c.getContentHash());
            md.put("section_path", c.getSectionPath() == null ? "" : c.getSectionPath());
            md.put("chunk_version", props.getActiveVersion());
            md.put("active", "true");
            md.put("token_count", c.getTokenCount());
            segments.add(TextSegment.from(c.getEmbeddedText(), md));
        }
        List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
        List<String> ids = new ArrayList<>(embeddings.size());
        for (int i = 0; i < embeddings.size(); i++) {
            ids.add(store.add(embeddings.get(i), segments.get(i)));
        }
        docVectors.put(docId, ids);
        return ids.size();
    }

    /** 逻辑删除：0.29.1 无 remove，用向量 id 墓碑在检索侧过滤 */
    public void removeByDocId(Long docId) {
        List<String> ids = docVectors.remove(docId);
        if (ids != null) tombstones.addAll(ids);
    }

    public List<EmbeddingMatch<TextSegment>> search(Embedding query, int k) {
        int recall = Math.max(k * props.getRecallMultiplier(), k + 20);
        return store.findRelevant(query, recall, props.getMinScore()).stream()
                .filter(m -> !tombstones.contains(m.embeddingId()))
                .filter(m -> props.getActiveVersion().equals(
                        m.embedded().metadata().getString("chunk_version")))
                .filter(m -> !"false".equals(m.embedded().metadata().getString("active")))
                .limit(k)
                .collect(Collectors.toList());
    }

    public List<String> vectorIdsOf(Long docId) {
        return docVectors.getOrDefault(docId, List.of());
    }
}
```

> 已知限制（必须写进变更单）：墓碑方案内存只增不减，**重启即回到干净状态**；这正好与「启动从 DB 重建」配合。物理删除与持久化在 S3 换 Milvus/Qdrant 时解决。若墓碑占比高，`recall-multiplier` 需相应调大。

**(4) `domain/chunk/StructureAwareSplitter.java`** —— 标题/条款边界 + 段内 token 递归 + 硬上限保护：

```java
package com.example.aichat.domain.chunk;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class StructureAwareSplitter implements DocumentSplitter {

    private static final Pattern HEADING = Pattern.compile(
        "(?m)^\\s{0,3}(#{1,6}\\s+\\S.*|第[一二三四五六七八九十百零〇\\d]+[章节条]\\s*\\S.*|\\d+(?:\\.\\d+){0,3}\\s+\\S.*)$");

    private final DocumentSplitter delegate;
    private final int hardMaxChars;

    public StructureAwareSplitter(DocumentSplitter delegate, int hardMaxChars) {
        this.delegate = delegate;
        this.hardMaxChars = hardMaxChars;
    }

    @Override
    public List<TextSegment> split(Document document) {
        String text = document.text();
        Matcher m = HEADING.matcher(text);
        List<int[]> ranges = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        int last = 0;
        String pending = "";
        while (m.find()) {
            if (m.start() > last) { ranges.add(new int[]{last, m.start()}); titles.add(pending); }
            pending = m.group(1).trim();
            last = m.start();
        }
        ranges.add(new int[]{last, text.length()});
        titles.add(pending);

        List<TextSegment> result = new ArrayList<>();
        Deque<String> path = new ArrayDeque<>();
        for (int i = 0; i < ranges.size(); i++) {
            String title = titles.get(i);
            if (!title.isEmpty()) {
                int level = headingLevel(title);
                while (!path.isEmpty() && path.size() >= level) path.removeLast();
                path.addLast(title);
            }
            String sectionPath = String.join(" > ", path);
            int[] r = ranges.get(i);
            String body = text.substring(r[0], r[1]).trim();
            if (body.isEmpty()) continue;
            for (String slice : hardSlice(body)) {
                for (TextSegment seg : delegate.split(Document.from(slice))) {
                    Metadata md = seg.metadata().copy();
                    md.put("section_path", sectionPath);
                    result.add(TextSegment.from(seg.text(), md));
                }
            }
        }
        return result.isEmpty() ? delegate.split(document) : result;
    }

    private int headingLevel(String t) {
        if (t.startsWith("#")) {
            int n = 0;
            while (n < t.length() && t.charAt(n) == '#') n++;
            return Math.min(n, 6);
        }
        if (t.startsWith("第")) return 2;
        int dots = 0;
        for (int i = 0; i < t.length(); i++) if (t.charAt(i) == '.') dots++;
        return Math.min(dots + 1, 6);
    }

    private List<String> hardSlice(String body) {
        if (body.length() <= hardMaxChars) return List.of(body);
        List<String> slices = new ArrayList<>();
        for (int i = 0; i < body.length(); i += hardMaxChars) {
            slices.add(body.substring(i, Math.min(i + hardMaxChars, body.length())));
        }
        return slices;
    }
}
```

**(5) 实体 `DocumentChunk.java` 新增字段**（Lombok `@Data/@Builder` 自动兼容，原字段不动）：

```java
    private String vectorId;        // @Column(length = 64)
    private String parentId;        // @Column(length = 64)
    private String sectionPath;     // @Column(length = 512)
    private String contentHash;     // @Column(length = 64)
    private String chunkVersion;    // @Column(length = 32)
    private Integer tokenCount;
    private Boolean active;
    private String tenantId;
    private String aclTag;
    private String embeddingModel;
```

### 4.5 Five 处写路径改造（改动点摘要表）

| 文件 | 改动点 | old | new |
|---|---|---|---|
| `EmbeddingService.java` | 构造 | 字段 `documentSplitter = recursive(400,80)` | 注入 `ChunkPipeline` / `ChunkIndex`，删除该字段 |
| `EmbeddingService.java` | `embedAndStore` | 自己 split + `store.add` | **降级为兼容包装**（仅供外部/测试调用）：从 `doc_<id>` 解析 docId → 查库 → `ChunkPipeline.split` → `ChunkIndex.reindex`；主链路不再调它 |
| `EmbeddingService.java` | `searchSimilar` | `store.findRelevant(query, recallSize)` | `chunkIndex.search(query, recallSize)`，再走既有 rerank |
| `KnowledgeDocumentService.java` | `@PostConstruct` | 按 `KnowledgeDocument` 全文重嵌 | 遍历文档调 `indexDocument(doc)`（见下方重建说明），不再自己拼向量 |
| `KnowledgeDocumentService.java` | `uploadPlainText` | 只 `embedAndStore(content, doc_id)` | 保存后调私有 `indexDocument(saved)`：落表 + reindex；**不再调 `embedAndStore`，避免索引两次** |
| `KnowledgeDocumentService.java` | `updateDocument` | 仅改 DB | 内容变更时调 `indexDocument(updated)` |
| `KnowledgeDocumentService.java` | `deleteDocument` | 只删 chunk 表 | 追加 `chunkIndex.removeByDocId(id)` |
| `DocumentChunkService.java` | `getSplitter` / `regenerateChunks` | 4 套 splitter + 自己 `store.add` | 全部委托 pipeline；`ChunkStrategy.fromCode` 保留做参数兼容映射 |
| `DocumentChunkService.java` | `updateChunk` / `deleteChunk` | 只 add / 只删表 | 编辑后 reindex 单文档；删除后 reindex 单文档 |
| `DocumentChunkService.java` | `optimizeSemanticSegments` / `calculateTextSimilarity` | 空格分词 Jaccard | **删除**（中文失效且会并成巨块），`SEMANTIC` 映射为结构感知策略 |
| `application.yml` / `AiChatApplication.java` | 配置 | 参数硬编码 | 新增 `chunk.*` + `@EnableConfigurationProperties` |
| `KNOWLEDGE_BASE_PHASE2.md` | 文档 | 500/50 | 对齐 `chunk.max-tokens=300 / overlap=40` |

> `EmbeddingService.embedAndStore(String text, String metadata)` 的兼容包装实现：从 `metadata`（`doc_<id>`）解析 docId，用**新增注入**的 `KnowledgeDocumentRepository.findById` 取文档，再走 `ChunkPipeline.split` + `ChunkIndex.reindex`；解析/查询失败时记 warn 并返回 0，不抛异常，保留原有「向量化失败不影响文档保存」的降级语义。这样构造签名虽变，`public` 方法签名与调用方完全不变。

统一私有方法（放 `KnowledgeDocumentService`；构造函数需追加 `ChunkPipeline`、`ChunkIndex`、`ChunkProperties`、`EmbeddingModel` 四个依赖，**对外 public 方法签名不变**）：

```java
private void indexDocument(KnowledgeDocument doc) {
    List<Chunk> chunks = chunkPipeline.split(doc);

    // 幂等短路：hash 序列一致且版本一致时只重建内存索引，不重复写库
    List<DocumentChunk> old = chunkRepository.findByDocumentIdOrderByChunkIndex(doc.getId());
    List<String> oldHashes = old.stream().map(DocumentChunk::getContentHash).collect(toList());
    List<String> newHashes = chunks.stream().map(Chunk::getContentHash).collect(toList());
    boolean sameVersion = old.stream().allMatch(
            r -> props.getActiveVersion().equals(r.getChunkVersion()));
    if (sameVersion && oldHashes.equals(newHashes)) {
        chunkIndex.reindex(doc.getId(), chunks);   // 仅重建内存向量（重启场景）
        return;
    }

    List<DocumentChunk> rows = new ArrayList<>(chunks.size());
    int dim = embeddingModel.embed(chunks.get(0).getEmbeddedText()).content().dimension();
    for (Chunk c : chunks) {
        rows.add(DocumentChunk.builder()
                .document(doc)
                .content(c.getContent())
                .chunkIndex(c.getIndex())
                .sectionPath(c.getSectionPath())
                .contentHash(c.getContentHash())
                .chunkVersion(props.getActiveVersion())
                .tokenCount(c.getTokenCount())
                .active(Boolean.TRUE)
                .vectorDimension(dim)
                .build());
    }
    chunkRepository.deleteByDocumentId(doc.getId());
    List<DocumentChunk> saved = chunkRepository.saveAll(rows);
    chunkIndex.reindex(doc.getId(), chunks);

    List<String> ids = chunkIndex.vectorIdsOf(doc.getId());
    for (int i = 0; i < saved.size() && i < ids.size(); i++) {
        saved.get(i).setVectorId(ids.get(i));
    }
    chunkRepository.saveAll(saved);
}
```

**写路径调用关系（S1 必须收敛成这一张图）**：

```
uploadPlainText ─┐
updateDocument  ─┼→ KnowledgeDocumentService.indexDocument(doc)
@PostConstruct  ─┘        ├→ ChunkPipeline.split(doc)   → document_chunks 表
                          └→ ChunkIndex.reindex(docId)  → 向量库
deleteDocument ─────────→ chunkRepository.deleteByDocumentId + ChunkIndex.removeByDocId
DocumentChunkController.regenerate/update/delete ─→ 同上 indexDocument / delete 路径
EmbeddingService.embedAndStore ─→ 兼容包装，仅外部调用，主链路不再经过
EmbeddingService.searchSimilar ─→ ChunkIndex.search → RerankService（既有逻辑不动）
```

> 两点说明：① `document_chunks` 表只存 `content` 原文，不存带前缀的 `embeddedText`，因此**启动重建必须重新跑 `ChunkPipeline.split(doc)`**，不能拿表里 content 直接送嵌入（否则上下文前缀丢失、hash 与向量不一致）；② 维度沿用现有 `embeddingModel.embed(...).content().dimension()` 写法，`embed(String)` 是 0.29.1 的 default 方法，已用 `javap` 确认。

### 4.6 验收（S1 完成定义）

```bash
cd /d/proj/rag-demo/ai-chat-backend
mvn -o -q -DskipTests compile ; echo "EXIT=$?"
mvn -o test -Dtest=ChunkPipelineTest,PromptInjectionRegressionTest,RerankServiceTest
mvn -o -q -DskipTests package
```

`ChunkPipelineTest` 至少覆盖：

- [ ] 纯中文无空格长文本 → 块数 > 1，且每块 `tokenCount <= maxTokens * 1.2`
- [ ] 中英混排 / 代码块 → 无超长静默截断，`truncated` 标记可统计
- [ ] 同一文档重复 `split` → `contentHash` 序列完全一致
- [ ] `chunkIndex.reindex` 两次 → 该 doc 的向量 id 列表长度不变
- [ ] `removeByDocId` 后 `search` 结果不含该 doc 的任何 id
- [ ] 构造含注入载荷的文档 → 前缀与正文经 `PromptInjectionGuard` 后不出现可执行指令

API 冒烟（后端需先启动，MySQL / API Key 就绪）：

```bash
# 上传
curl -s -X POST localhost:8080/api/knowledge/upload -H "Content-Type: application/json" \
  -d '{"title":"考勤制度","content":"第一章 请假\n员工请假需提前3天申请。\n第二章 报销\n差旅结束后7个工作日内提交报销单。","fileType":"md"}'
# 分块表应已有记录（不再需要手动点"重新生成分块"）
curl -s "localhost:8080/api/knowledge/chunks?documentId=1"
# RAG 问答
curl -s -X POST localhost:8080/api/chat/rag -H "Content-Type: application/json" \
  -d '{"question":"请假要提前几天？","maxResults":5}'
# 删除后再问 → 断言答案不含"提前3天"
curl -s -X DELETE localhost:8080/api/knowledge/documents/1
```

---

## 5. Sprint 2（P1）：结构感知、上下文增强、父子索引

| 步骤 | 动作 | 涉及文件 |
|---|---|---|
| 2.1 | 打开 `chunk.structure-aware=true`，用 `StructureAwareSplitter` 产出 `section_path`，并写入 metadata 与 DB | `ChunkPipeline` / `DocumentChunk` |
| 2.2 | `chunk.context-prefix=true`，`[文档标题 > 章节路径]` 前缀送嵌入（零成本上下文增强） | `ChunkPipeline` |
| 2.3 | 父块生成：按 `parent-max-tokens` 聚合相邻子块，写 `parent_id`；检索命中子块后回溯父块注入 LLM | 新增 `ParentChunkResolver`；改 `RagService.buildContext` |
| 2.4 | 相邻块窗口扩展（prev/next 各 1 块）+ 按 `doc_id` MMR 去重，避免 topK 被同文档霸占 | `EmbeddingService.searchSimilar` |
| 2.5 | 引用 DTO 扩展：`chunkId / sectionPath / docTitle`，让答案可定位到章节 | `RagService.Citation`、`RagChatResponse.CitationDTO`、`ChatController.ragChat` |
| 2.6 | 删除伪 `SEMANTIC`，`ChunkStrategy.SEMANTIC` 映射为结构感知、`PARENT_CHILD` 映射为父子模式，前端下拉不变 | `ChunkStrategy` / `DocumentChunkService` |
| 2.7 | 表格保护：Markdown 表格整块保留，大表按行切分且每行重复表头 | `StructureAwareSplitter` |

**上下文增强的进阶选项（需评估成本后再开）**：Anthropic Contextual Retrieval（LLM 为每块生成 1–2 句定位描述，配合 BM25 可降 49% 检索失败，再叠加 rerank 降 67%）。建议先用 2.2 的确定性前缀跑 A/B，提升不足再引入 LLM 生成，避免索引成本失控。

---

## 6. Sprint 3（P1/P2）：召回、权限、观测、持久化

| 步骤 | 动作 |
|---|---|
| 3.1 | 在 `DocumentChunkRepository.searchByKeyword` 之上加 BM25/ES 通道，与向量结果做 **RRF**（`score = Σ 1/(60+rank)`）融合，替换 `KnowledgeDocumentService.hybridSearch` 中按全文 `LIKE` 打分的实现 |
| 3.2 | 检索期按 `tenant_id / acl_tag / active / chunk_version` 用 `metadataKey(...).isEqualTo(...)` 构造 `Filter`（0.29.1 已确认 `InMemoryEmbeddingStore.search()` 支持 Filter），实现权限继承 |
| 3.3 | 观测：记录块长分布、截断率、重复块率、`recall@k`、rerank 前后分数，输出到日志与 `rag-eval` CSV |
| 3.4 | 打开 `pom.xml` 里预留的 `langchain4j-milvus` 注释并按 `EmbeddingStoreConfig` 示例切换；此时实现 `ChunkIndex.removeByDocId` 的物理删除分支，替掉墓碑机制 |
| 3.5 | 增量更新：按 `content_hash` 只重嵌变化块；索引版本号 + 灰度重建 |

---

## 7. 评估闭环（A/B，不能凭感觉调参）

`rag-eval/evaluate_rag.py` 已能跑 RAGAS 四指标并在无 `reference` 时自动跳过 context 类指标；`rag-eval/sample_questions.json` 已有 3 条带 ground truth 的样本。S2 前先扩充到 ≥30 条覆盖多章节、跨节、表格、精确数字的问题集。

```bash
cd /d/proj/rag-demo/rag-eval

# A 组：baseline（structure-aware=false, context-prefix=false）
python -u evaluate_rag.py --questions sample_questions.json \
  --metrics faithfulness,answer_relevancy,context_precision,context_recall \
  --max-results 5 -o baseline.csv

# 改 application.yml 的 chunk.* 开关，重启后端

# B 组：结构感知 + 上下文前缀
python -u evaluate_rag.py --questions sample_questions.json \
  --metrics faithfulness,answer_relevancy,context_precision,context_recall \
  --max-results 5 -o structure_aware.csv
```

新增 `rag-eval/chunk_metrics.py`（方案交付后实现）计算分块专属指标：

| 指标 | 定义 |
|---|---|
| chunk recall@k | 标准答案所在段落是否出现在 top-k 检索片段中 |
| 上下文完备率 | 命中片段是否包含完整条款（非半句） |
| 截断率 | `token_count > maxTokens * 1.2` 的块占比 |
| 重复块率 | top-k 中 `content_hash` 重复占比 |
| 幽灵率 | 已删除文档内容在 top-k 中出现次数 |

**判定规则**：B 组 `context_recall` 不低于 A 组，`faithfulness` 不下降，且重复块率/幽灵率为 0，才允许合入；否则回退开关。 judge 模型建议用与生成模型不同的供应商，避免自我偏好偏差。

---

## 8. 上游 / 下游影响面

| 方向 | 受影响点 | 兼容性措施 |
|---|---|---|
| 上游 | `KnowledgeDocumentController` 的 upload / update / delete | 路径与 DTO 不变 |
| 上游 | `DocumentChunkController` 的 regenerate / update / delete / reorder / strategies | 路径与参数不变；`strategy` 做映射兼容 |
| 上游 | `@PostConstruct` 启动重建 | 改为读 `document_chunks`，不再全文重嵌 |
| 下游 | `EmbeddingService.searchSimilar` → `RagService` | 返回 `SearchResult` 保留旧构造，新增字段用重载 |
| 下游 | `ChatController.ragChat` → `RagChatResponse.CitationDTO` | 只加字段，前端 `RagChat.vue` 无需改 |
| 下游 | `KnowledgeDocumentService.semanticSearch` / `hybridSearch` | 仍按 `doc_id` 合并分数，metadata 新增键不影响 |
| 下游 | `ReactKnowledgeBaseService` / `GraphRagService` | 前者复用 `EmbeddingService`；后者走全文、不消费分块，无需改 |
| 安全 | `prompt-injection-tests` 回归套件 | 新前缀属不可信数据，必须重跑全部用例 |
| 评估 | `rag-eval` 取 `citations[].content` | 字段不变，仅新增列 |
| 前端 | `KnowledgeBase.vue` 分块弹窗 | 上传后直接有分块，修掉「暂无分块」提示 |

---

## 9. 风险、开关与回滚

| 风险 | 缓解 |
|---|---|
| 0.29.1 无删除 API，墓碑导致内存增长 | S1 接受；重启清零；S3 换 Milvus 后物理删除 |
| `HeuristicTokenizer` 与 Qwen 嵌入真实 token 有偏差 | 保守上取整 + `hard-max-chars` 兜底；上线后按截断率校准；若需精确再引 `OpenAiTokenizer`（已在 classpath，但未知模型名可能抛异常） |
| 上下文前缀影响注入防护 | 前缀一律过 `sanitizeLabel`；重跑注入回归 |
| DDL 变更影响存量数据 | 先 `UPDATE ... SET metadata=NULL` 再改列类型；存量标 `legacy/active=0` 由启动重建 |
| 全量重建期间检索结果不完整 | `chunk.active-version` 双版本并存，重建完成再切流量 |
| 离线仓库缺新依赖 | **S1/S2 零新增 jar**；S3 的 Milvus 需联网预拉依赖并先在非离线环境验证 |
| 参数改动引发质量回退 | 全部由 `chunk.*` 开关控制，改配置即可回滚；每个 Sprint 一个 git commit |

**回滚**：`git revert <sprint-commit>` + 执行 `sql/20261113_chunk_pipeline_rollback.sql`（删新增列、恢复 `metadata` 为 VARCHAR(1000)），再 `mvn -o -q -DskipTests compile` 验证。

---

## 10. 执行 checklist

**S0**
- [ ] `mvn -o -q -DskipTests compile` EXIT=0（已验证）
- [ ] 基线测试绿、分支已建
- [ ] `javap` 探针结果符合 0.2 节

**S1**
- [ ] 执行 `sql/20261113_chunk_pipeline.sql` + `_init.sql`
- [ ] 新增 6 个类 + 实体字段 + `chunk.*` 配置
- [ ] 11 处改动点全部按 4.5 表落地，且全局搜索确认**再无 `embeddingStore.add(` 出现在 `EmbeddingService` / `DocumentChunkService` 之外**
- [ ] `mvn -o test -Dtest=ChunkPipelineTest,PromptInjectionRegressionTest,RerankServiceTest` 通过
- [ ] `mvn -o -q -DskipTests package` BUILD SUCCESS
- [ ] API 冒烟：上传→分块表有记录→问答→删除→不再召回

**S2**
- [ ] `section_path` / 上下文前缀 / 父子索引 / 引用扩展
- [ ] `SEMANTIC` 伪实现删除
- [ ] RAGAS A/B 达标

**S3**
- [ ] BM25+RRF、权限过滤、指标观测、Milvus 物理删除
- [ ] 文档与代码参数对齐（`KNOWLEDGE_BASE_PHASE2.md`）

---

## 11. 需要确认的 3 个决策点

1. **向量库路线**：S1 用「InMemory + 墓碑软删除」（零新依赖、离线可编译，推荐先走）还是直接切 Milvus（需联网拉 `langchain4j-milvus` 及传递依赖，离线环境有失败风险）？
2. **`ChunkStrategy` 对外语义**：是否保留前端下拉的三种策略名并做映射（推荐），还是改造成「策略 + 参数」两段式配置？
3. **执行范围**：是否按 S1 → S2 → S3 逐 Sprint 提交，每 Sprint 做一次 compile-verify + 改动点摘要表？

> **提醒 note**：本文件为方案，未修改任何源码、未执行 DDL、未执行构建（仅做过只读的 `mvn -o compile` 基线探测与 `javap` API 核对）。所有 Java 片段按 LangChain4j 0.29.1 实测签名书写，但**落地时仍以 S0 的 `mvn -o` 编译结果为唯一准绳**；尤其不要假设 `EmbeddingStore` 有 `remove`/`removeAll`——该版本没有。确认第 11 节的 3 个决策点后再开始编码。

---

## 12. 落地状态（2026-11-13 已执行）

按第 11 节的推荐项执行：**InMemory + 墓碑软删除**、**保留 `ChunkStrategy` 映射兼容**、
**S1→S2→S3 一次性落地并逐阶段 compile-verify**。

### 12.1 已完成

| 项 | 落地内容 |
|---|---|
| DDL | `sql/20261113_chunk_pipeline.sql` + `_init.sql` + `_rollback.sql`（双文件约定 + 回滚） |
| 新增类 | `ChunkProperties`、`Chunk`、`ChunkPolicy`、`ChunkHit`、`HeuristicTokenizer`、`StructureAwareSplitter`、`ChunkPipeline`、`ChunkIndex`、`ChunkIndexingService`、`Bm25Scorer` |
| 实体/DTO | `DocumentChunk` 10 个可追溯字段；`DocumentChunkDTO`、`RagChatResponse.CitationDTO`、`RagService.Citation`、`EmbeddingService.SearchResult` 纯增量字段 |
| 写路径收敛 | 上传 / 编辑文档 / 编辑分块 / 删除分块 / 排序 / 重新生成 / 删除文档 / 启动重建，全部收口到 `ChunkIndexingService`；全局仅 `ChunkIndex` 调 `embeddingStore.add` |
| S2 | 结构感知（标题路径 + Markdown 表格整块保留）、上下文前缀、父子/相邻块回溯、引用溯源、`SEMANTIC`/`PARENT_CHILD` 策略映射、删除伪 Jaccard 语义切分 |
| S3 | BM25 + RRF 混合召回、按 chunk_hash 去重 + 单文档限流、metadata 级租户/ACL 过滤开关、块质量与检索指标日志、hash 幂等短路、`rag-eval/chunk_metrics.py` 分块专属指标 |
| 测试 | `ChunkPipelineTest` 11 例；`mvn -o clean test -Dtest=ChunkPipelineTest,PromptInjectionRegressionTest,RerankServiceTest` → **28/28 BUILD SUCCESS** |

### 12.2 相对方案的 6 处修正（落地时发现的真实问题）

1. **新增 `ChunkIndexingService`**：方案把 `indexDocument` 写成 `KnowledgeDocumentService` 的私有方法，但 `DocumentChunkService` 的 regenerate/update/delete 跨 Service 调不到。改为共享编排器，才是真正的「唯一写路径」。
2. **索引放事务提交后**：`indexAfterCommit` 用 `TransactionSynchronization.afterCommit`，保住「向量化失败不影响文档保存」，同时避免 catch JPA 异常导致事务 rollback-only。
3. **保留 `KnowledgeDocumentService` 三参构造器**：`PromptInjectionRegressionTest` 直接 `new` 它；新增四参构造标 `@Autowired`，三参链路优雅降级。符合非侵入式要求。
4. **token 预算硬兜底 + 标题正则限长**：实测 0.29.1 在「无换行中文长段落 + 结构感知」下，英文 OpenNLP 句子模型识别不了中文句读，会产出 ~3 倍超预算块；且标题正则 `.*` 会把 4810 字整段误判为一个标题塞进 `section_path`。已在 `ChunkPipeline.enforceTokenBudget` 与 `StructureAwareSplitter` 中修复并有测试覆盖。
5. **保留 `source=doc_<id>` 元数据键**：`hybridSearch` 靠它解析 docId，方案里的 metadata 清单漏了这一项。
6. **分块编辑保留覆盖语义**：改为从 `document_chunks` 表内行重建送嵌文本再统一索引，而不是按全文重切（否则用户编辑会被静默丢弃）。

### 12.3 未执行 / 需你侧配合

- **DDL 未执行**：三个 SQL 脚本已生成，需按变更单在目标库执行；`ddl-auto: update` 只会自动加列，不会改 `metadata` 列类型。
- **API 冒烟未跑**：需要 MySQL、`OPENAI_API_KEY*` 等环境变量就绪后启动后端，按 4.6 节 curl 断言「删除后不再召回」。
- **RAGAS A/B 未跑**：`sample_questions.json` 仅 3 条，需按真实语料扩充到 ≥30 条；`chunk_metrics.py` 已实现分块专属指标，`--deleted-markers` 用于幽灵率检测。
- **Milvus 物理删除**：离线仓库缺 `langchain4j-milvus` 及传递依赖，S3 只落地逻辑删除与预留扩展点；切换时在 `ChunkIndex.removeByDocId` 增加物理删除分支即可。
- **多租户完整鉴权**：只预留字段与检索期过滤开关（`chunk.tenant-filter-enabled=false`），未接真实身份上下文。

> **提醒 note**：默认配置已打开 `structure-aware` / `context-prefix` / `bm25-enabled`；跑 A/B 对照组时把三者改回 `false` 即可，无需改代码或重新编译。`parent-child` 默认关闭，前端选择 `PARENT_CHILD` 策略时按次开启。所有 Java 代码以 `mvn -o clean test` 为唯一准绳；曾遇到 Maven 增量编译未重编 main 类导致「本地已改但测试跑旧字节码」的现象，验收请一律用 `clean`。
