# RAGAS 评估工具（rag-eval）

使用 [RAGAS](https://docs.ragas.io/) 对本项目 `ai-chat-backend` 的 RAG 接口
（`POST /api/chat/rag`，见 `RagService.java` / KNOWLEDGE_BASE_PHASE4.md）做自动化质量评估。

## 目录结构

```
rag-eval/
├── evaluate_rag.py              # 评估主脚本
├── chunk_metrics.py             # 分块专属指标脚本
├── requirements.txt             # Python 依赖
├── sample_questions.json        # 3 条冒烟样例（历史保留）
├── testset_v1_corpus.md         # 测试集专用固定语料（7 篇文档，唯一事实来源）
├── testset_v1.json              # 主测试集：40 条，question + reference + 元数据
├── testset_v1_adversarial.json  # 补充集：10 条负例/注入/歧义/错误前提
└── README.md                    # 本文件
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

## 分块专属指标（chunk_metrics.py）

`evaluate_rag.py` 的 RAGAS 四指标只评估端到端答案，分不出「检索没召回」和「生成没用好」。
`chunk_metrics.py` 直接分析 `/api/chat/rag` 返回的 `citations`，度量分块与索引本身：

| 指标 | 含义 |
|---|---|
| `chunk_recall@k` | 标准答案字符 bigram 被 top-k 片段覆盖的比例 |
| `context_completeness` | 命中片段以完整句/表格行结尾的比例（是否切成半句） |
| `duplicate_rate` / `section_coverage` / `traceability` | 重复块率、章节路径覆盖率、引用可定位率 |
| `ghost_rate` | 命中已删除文档特征串的占比（幽灵向量） |
| `truncation_rate` / `avg|max_tokens` | 分块表块长分布（需 `--chunks-base`） |

```bash
python -u chunk_metrics.py --questions sample_questions.json \
  --backend http://localhost:8080/api/chat/rag \
  --deleted-markers deleted_markers.txt \
  --chunks-base http://localhost:8080/api/knowledge/chunks --doc-ids 1,2,3 \
  -o chunk_metrics.csv
```

`deleted_markers.txt` 每行写一句「已删除文档中的独特句子」。脚本只用 `requests` + 标准库，
**不需要 RAGAS 或 judge 模型**，因此可在 A/B 每轮快速跑一遍。

A/B 口径：对照组把 `application.yml` 的 `chunk.structure-aware / context-prefix / bm25-enabled`
全置 `false`，实验组置 `true`，固定同一问题集跑两组；**判定规则**是实验组 `context_recall`
不低于对照组、`faithfulness` 不下降，且重复块率/幽灵率为 0。正式 A/B 请用下方
`testset_v1.json`（40 条，覆盖多章节、跨节、表格、精确数字），`sample_questions.json`
仅保留作 3 条冒烟样例。

## 测试集 v1（可直接跑）

三份文件配套使用，`reference` 全部能在 `testset_v1_corpus.md` 中逐条定位：

| 文件 | 内容 | 用途 |
|---|---|---|
| `testset_v1_corpus.md` | 7 篇虚构《星海科技员工手册》：考勤休假、差旅报销、入职离职、薪酬福利、信息安全、IT 支持、培训发展；含 6 张表格与大量精确数字 | 上传知识库，作为唯一 ground truth |
| `testset_v1.json` | 40 条主测试题，每条带 `id / category / difficulty / source_docs` | RAGAS 四指标 + chunk 指标 |
| `testset_v1_adversarial.json` | 10 条补充题：4 条知识库未覆盖、3 条注入/分隔符逃逸、2 条歧义、1 条错误前提 | 单独评估拒答率、忠实度、抗注入 |

> Q17（8 小时 × 3 = 24 小时调休）、Q27（600×2=1200 元、80×2=160 元）的 reference
> 含**由原文数字推导**的结果，语料中没有字面值，这是刻意设计来检验计算型问答；
> 用字符 bigram 口径的 `chunk_recall` 会略偏低，属正常现象，以 RAGAS 语义指标为准。

主测试集分布：

| 类别 | 条数 | 考察点 |
|---|---|---|
| `single_hop` | 12 | 单条款直接问答 |
| `table_numeric` | 8 | 表格行定位、职级/城市条件、精确数字与简单计算 |
| `paraphrase` | 6 | 口语化同义改写，无原文关键词 |
| `multi_hop` | 8 | 跨章节组合（如年假：额度在文档一、结转在文档四） |
| `conditional` | 6 | 时间窗口、审批层级、阈值边界 |

使用步骤：

```bash
# 1. 把 testset_v1_corpus.md 的「文档一」～「文档七」分别上传到知识库
#    （标题取一级标题；这样 citations[].source 可定位到具体文档）
# 2. 主测试集跑 RAGAS 四指标
python -u evaluate_rag.py --questions testset_v1.json -o ragas_v1.csv
# 3. 同题跑分块指标（A/B 时两组各跑一次，问题集必须一致）
python -u chunk_metrics.py --questions testset_v1.json -o chunk_v1.csv
# 4. 补充集单独跑：建议只看 faithfulness / answer_relevancy 与人工判定，
#    不纳入 context_recall（未覆盖类问题的 reference 是「应拒答」）
python -u evaluate_rag.py --questions testset_v1_adversarial.json \
  --metrics faithfulness,answer_relevancy -o ragas_v1_adversarial.csv
```

注意：补充集里带 `expect=refuse / clarify / correct` 字段，评估脚本会忽略这些字段
（只读取 `question` 与 `reference`），需按 `reference` 人工或另写断言判断行为是否正确。

## 注意

- 本环境当前无法直接跑通端到端评估：后端未启动且缺少 `OPENAI_API_KEY` 环境变量。
  在后端和密钥就绪后执行上述步骤即可得到真实分数。
