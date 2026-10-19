# RAGAS 评估工具（rag-eval）

使用 [RAGAS](https://docs.ragas.io/) 对本项目 `ai-chat-backend` 的 RAG 接口
（`POST /api/chat/rag`，见 `RagService.java` / KNOWLEDGE_BASE_PHASE4.md）做自动化质量评估。

## 目录结构

```
rag-eval/
├── evaluate_rag.py        # 评估主脚本
├── requirements.txt       # Python 依赖
├── sample_questions.json  # 示例问题集（question + reference）
└── README.md              # 本文件
```

## 评估指标（RAGAS 经典四指标）

| 指标 | 含义 | 是否需要 reference |
|---|---|---|
| faithfulness | 回答是否忠实于检索到的上下文（防幻觉） | 否 |
| answer_relevancy | 回答与问题的相关度 | 否 |
| context_precision | 检索片段中相关内容的排序质量 | 是 |
| context_recall | 标准答案所需知识点被上下文覆盖的比例 | 是 |

数据映射关系：

- `question` → RAGAS `user_input`
- 接口返回 `answer` → `response`
- 接口返回 `citations[].content`（向量检索命中的片段）→ `retrieved_contexts`
- 问题集中的 `reference` → ground truth

## 快速开始

1. **安装依赖**（Python ≥ 3.10，已完成安装可跳过）：

   ```bash
   pip install -r requirements.txt
   ```

2. **启动 Spring Boot 后端**（需要 MySQL/Milvus、`OPENAI_API_KEY` 等环境变量）：

   ```bash
   cd ../ai-chat-backend && mvn spring-boot:run
   ```

3. **配置评估用 judge 模型**（任意 OpenAI 兼容 API；建议与被测模型不同以减少偏差）：

   ```bash
   export EVAL_API_KEY="sk-xxx"
   export EVAL_API_BASE="https://api.deepseek.com/v1"  # 可选，默认 https://api.openai.com/v1
   export EVAL_LLM_MODEL="deepseek-chat"               # 可选，默认 gpt-4o-mini
   export EVAL_EMBEDDING_MODEL="bge-m3"                # 可选，默认 text-embedding-3-small
   ```

4. **准备问题集**：编辑 `sample_questions.json`，格式为
   `[{"question": "...", "reference": "..."}]`。`reference` 可省略，
   省略时自动跳过 context_precision / context_recall。

5. **运行评估**：

   ```bash
   python evaluate_rag.py --questions sample_questions.json
   # 常用参数
   #   --backend http://localhost:8080/api/chat/rag   后端地址
   #   --metrics faithfulness,answer_relevancy        指定指标
   #   --max-results 5                               检索 topK
   #   --limit 10                                      只跑前 10 题
   #   -o results.csv                                  输出明细 CSV
   ```

   终端会打印汇总表与逐条明细，并把 DataFrame 存为 `ragas_results.csv`。

## 结果解读建议

- **faithfulness < 0.7**：存在幻觉风险，检查系统提示词约束与 chunk 质量。
- **answer_relevancy 低**：生成端问题，优化 prompt 或温度。
- **context_recall 低**：检索没找到该找的内容 → 调整分块策略（`ChunkStrategy`）、topK、嵌入模型。
- **context_precision 低**：检索回来很多无关片段 → 提高相似度阈值 / rerank。

## 注意

- 本环境当前无法直接跑通端到端评估：后端未启动且缺少 `OPENAI_API_KEY` 环境变量。
  在后端和密钥就绪后执行上述步骤即可得到真实分数。
