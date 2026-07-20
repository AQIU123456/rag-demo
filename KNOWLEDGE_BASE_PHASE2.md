# 阶段 2：文档向量化与语义搜索实现指南

## 一、阶段二的作用

阶段二实现了**文档向量化**和**语义搜索**能力，让系统能够：

1. **理解语义**：不仅匹配关键词，还能理解"请假"和"休假"这类相似概念
2. **智能分块**：将长文档切分为合适的文本块，为 RAG 问答做准备
3. **相似度搜索**：基于向量相似度返回最相关的文档片段

### 核心流程
```
文档上传 → 文本分块 → 向量嵌入 → 存储到向量库 → 语义搜索
```

## 二、已实现的功能

### 1. 新增组件

#### (1) DocumentChunk 实体
- 存储文档分块信息
- 关联原文档 ID
- 记录分块内容和索引位置

#### (2) EmbeddingService 服务
- `embedAndStore()`: 将文本分块并生成向量嵌入
- `searchSimilar()`: 基于语义相似度搜索相关文本块
- 使用 LangChain4j 的递归分块器（500 字符/块，重叠 50 字符）

#### (3) EmbeddingStoreConfig 配置
- 当前使用 `InMemoryEmbeddingStore`（内存向量存储）
- 预留 Milvus 切换接口

### 2. 新增 API 接口

#### 语义搜索接口
```
GET /api/knowledge/semantic-search?query=请假流程&maxResults=5
```

**响应示例：**
```json
[
  {
    "content": "员工请假需提前 3 天申请...",
    "score": 0.89,
    "source": "doc_1"
  },
  {
    "content": "休假期间工资按基本工资的 80% 发放...",
    "score": 0.76,
    "source": "doc_1"
  }
]
```

### 3. 自动向量化处理
- 文档上传时自动触发向量化
- 向量化失败不影响文档保存（降级处理）

## 三、从 InMemory 切换到 Milvus 的步骤

### 步骤 1：修改 pom.xml

取消 Milvus 依赖的注释：

```xml
<!-- 取消以下依赖的注释 -->
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-milvus</artifactId>
    <version>${langchain4j.version}</version>
</dependency>
```

### 步骤 2：配置 Milvus 连接

在 `application.yml` 中取消并完善 Milvus 配置：

```yaml
milvus:
  host: localhost
  port: 19530
  collection-name: knowledge_base
  username: # 可选
  password: # 可选
```

### 步骤 3：修改 EmbeddingStoreConfig

```java
@Configuration
public class EmbeddingStoreConfig {

    @Value("${milvus.host:localhost}")
    private String milvusHost;

    @Value("${milvus.port:19530}")
    private int milvusPort;

    @Value("${milvus.collection-name:knowledge_base}")
    private String collectionName;

    @Bean
    public EmbeddingStore<TextSegment> embeddingStore(EmbeddingModel embeddingModel) {
        return MilvusEmbeddingStore.builder()
            .host(milvusHost)
            .port(milvusPort)
            .collectionName(collectionName)
            .embeddingModel(embeddingModel)
            .build();
    }
}
```

### 步骤 4：启动 Milvus 服务

使用 Docker 快速启动 Milvus：

```bash
docker run -d \
  --name milvus-standalone \
  -p 19530:19530 \
  -p 9091:9091 \
  milvusdb/milvus:v2.3.0
```

### 步骤 5：测试验证

```bash
# 测试语义搜索
curl "http://localhost:8080/api/knowledge/semantic-search?query=请假流程&maxResults=5"
```

## 四、注意事项

### 1. InMemory 存储的限制
- ⚠️ **重启丢失**：内存向量存储在应用重启后会丢失
- ⚠️ **单机限制**：不支持分布式部署
- ✅ **适用场景**：开发测试、小规模验证

### 2. Milvus 生产配置建议
- 启用持久化存储
- 配置合适的索引类型（IVF_FLAT 等）
- 设置合理的分片数和副本数
- 监控内存和磁盘使用

### 3. 嵌入模型选择
- 当前配置：`text-embedding-ada-002`（OpenAI）
- 可选替代：
  - `bge-large-zh`（中文优化）
  - `m3e-base`（开源免费）
  - Azure OpenAI Embeddings

## 五、后续扩展方向

### 阶段 3：混合搜索
- 结合关键词搜索和语义搜索
- 实现加权排序

### 阶段 4：RAG 问答
- 基于检索结果生成答案
- 添加引用来源标注

### 高级功能
- 多向量索引支持
- 增量更新向量化
- 向量缓存优化
