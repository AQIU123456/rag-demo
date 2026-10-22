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

3. **配置评估用 judge 模型**（两种选择）：

   **方案 A：直接复用后端 chat-model（零配置）**。脚本会自动读取
   `../ai-chat-backend/src/main/resources/application.yml` 中 langchain4j 的
   `chat-model`（deepseek-v4-flash）与 `embedding-model`（Qwen3-VL-Embedding），
   只要运行前导出后端同款环境变量即可，无需再设 EVAL_*：

   ```bash
   export OPENAI_API_KEY="sk-xxx"          # chat-model 的 key
   export OPENAI_API_HOME_URL="https://..." # chat-model 的 base_url
   export OPENAI_API_KEY_ST="sk-yyy"        # embedding-model 的 key
   export OPENAI_API_HOME_URL_ST="https://..."
   ```

   > 注意：用生成答案的同一个模型当裁判会有一定自我偏好偏差，快速回归可用；
   > 正式评估建议按方案 B 换一个不同的 judge 模型。

   **方案 B：指定独立 judge 模型**（任意 OpenAI 兼容 API，EVAL_* 优先级高于方案 A）：

   ```bash
   export EVAL_API_KEY="sk-xxx"
   export EVAL_API_BASE="https://api.deepseek.com/v1"  # 可选，默认 https://api.openai.com/v1
   export EVAL_LLM_MODEL="deepseek-chat"               # 可选，默认取后端 chat-model
   export EVAL_EMBEDDING_MODEL="bge-m3"                # 可选，默认取后端 embedding-model
   # 嵌入模型可走独立服务（如本地 Ollama），judge LLM 与 embedding 用不同供应商：
   # export EMBED_API_BASE="http://localhost:11434/v1"
   # export EMBED_API_KEY="ollama"
   ```

   也可用 `--backend-yml /path/to/application.yml` 指定其他配置文件位置。

   **judge 模型选型建议**：faithfulness / context_precision / context_recall 由 judge LLM 打分，
   answer_relevancy 还需要一个 embedding 模型。国内 OpenAI 兼容接口均可用，例如：

   | 厂商 | base_url | 推荐 judge 模型 | embedding |
   |---|---|---|---|
   | DeepSeek | `https://api.deepseek.com/v1` | `deepseek-chat` | 不提供（配合本地 Ollama bge-m3） |
   | 阿里云百炼 | `https://dashscope.aliyuncs.com/compatible-mode/v1` | `qwen-plus` | `text-embedding-v3` |
   | 智谱 GLM | `https://open.bigmodel.cn/api/paas/v4` | `glm-4-flash`（免费） | `embedding-3` |
   | 硅基流动 SiliconFlow | `https://api.siliconflow.cn/v1` | Qwen/DeepSeek 蒸馏版 | `BAAI/bge-m3` |
   | Moonshot | `https://api.moonshot.cn/v1` | `moonshot-v1-8k` | 不提供 |
   | 本地 Ollama | `http://localhost:11434/v1` | `qwen2.5:14b` 等 | `bge-m3`（免费） |

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

## 常见问题排查

**运行后"什么也没有输出"？** 现在脚本一启动就会先打印一行
`RAGAS 评估启动 后端: ... 问题集: ...`，如果连这行都没有，通常是：

1. **Python 输出被缓冲（Windows 常见）**：用 `python -u evaluate_rag.py ...`
   运行禁用缓冲；或在 PowerShell 中设置 `$env:PYTHONUNBUFFERED="1"`。
2. **装错了解释器/虚拟环境**：确认 `python` 与 `pip install -r requirements.txt`
   用的是同一个环境（`where python` / `which python` 检查）。
3. **不在 rag-eval 目录下运行**：找不到 `sample_questions.json` 时脚本会直接报错提示。

若启动行有了但长时间停住：多半是后端未启动、每个请求在等超时。脚本现在会：
- 启动时做 3 秒健康检查，连不上立即报错退出（可用 `--no-health-check` 跳过）；
- 每条请求实时打印 `[i/N] 请求...`、耗时与成功/失败原因。

## 注意

- 本环境当前无法直接跑通端到端评估：后端未启动且缺少 `OPENAI_API_KEY` 环境变量。
  在后端和密钥就绪后执行上述步骤即可得到真实分数。
