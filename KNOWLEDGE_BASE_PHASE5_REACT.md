# 阶段 5：ReAct 智能知识库问答实现说明

## 一、阶段 5 概述

本阶段实现了**ReAct (Reasoning + Acting) 模式**的知识库问答功能，相比传统 RAG，ReAct 具备以下优势：

### ReAct vs 传统 RAG

| 特性 | 传统 RAG | ReAct 模式 |
|------|---------|-----------|
| **检索策略** | 单次检索 | 多轮迭代检索 |
| **推理能力** | 无显式推理 | 显式思考过程 |
| **工具使用** | 仅知识库 | 多种工具组合 |
| **问题拆解** | 不支持 | 支持复杂问题拆解 |
| **可解释性** | 低 | 高（展示推理链） |

### 核心特性

1. **思考 - 行动 - 观察循环**：AI 先思考需要什么信息，然后调用工具获取，再根据结果决定下一步
2. **多工具协同**：可同时使用知识库检索、天气查询、订单查询等多种工具
3. **透明推理过程**：完整展示 AI 的思考路径和决策依据
4. **防无限循环**：设置最大迭代次数，避免死循环

## 二、架构设计

### ReAct 工作流程

```
用户提问
   ↓
┌─────────────────────────────────────┐
│ 第 1 轮：Thought → Action → Observation │
│ Thought: 我需要查找公司请假政策...     │
│ Action: knowledge_search            │
│ Action Input: {"query": "请假流程"}  │
│ Observation: [知识库返回相关内容]      │
└─────────────────────────────────────┘
   ↓
┌─────────────────────────────────────┐
│ 第 2 轮：Thought → Action → Observation │
│ Thought: 还需要确认病假特殊要求...     │
│ Action: knowledge_search            │
│ Action Input: {"query": "病假证明"}  │
│ Observation: [知识库返回相关内容]      │
└─────────────────────────────────────┘
   ↓
┌─────────────────────────────────────┐
│ 第 3 轮：Thought → Final Answer         │
│ Thought: 现在我有足够信息回答问题了   │
│ Final Answer: [综合所有信息的完整答案] │
└─────────────────────────────────────┘
   ↓
返回答案 + 完整推理过程
```

### 可用工具列表

1. **knowledge_search** - 知识库检索
   - 用途：搜索公司政策、规章制度、流程说明
   - 参数：`query` (搜索关键词)

2. **weather_query** - 天气查询
   - 用途：查询城市天气信息
   - 参数：`city` (城市名称)

3. **order_query** - 订单查询
   - 用途：查询订单状态、详情
   - 参数：`orderId` (订单号) 或 `customerName` (客户姓名)

## 三、已实现的功能

### 后端实现

#### 1. ReactKnowledgeBaseService 服务

**文件位置**: `/service/ReactKnowledgeBaseService.java`

**核心方法**:
- `answerWithReact(String question, int maxResults)` - ReAct 问答主方法
- `buildToolSpecifications()` - 构建工具规范列表
- `executeTool()` - 执行工具调用
- `executeKnowledgeSearch()` - 执行知识库搜索
- `executeWeatherQuery()` - 执行天气查询
- `executeOrderQuery()` - 执行订单查询

**内部类**:
- `ReactResponse` - ReAct 问答响应对象
- `ReactStep` - 推理步骤
- `StepType` - 步骤类型枚举 (THOUGHT, ACTION, OBSERVATION, FINAL_ANSWER)

#### 2. ChatController 扩展

**新增 API 接口**:
```
POST /api/chat/react
```

**请求示例**:
```json
{
  "question": "公司的请假流程是什么？如果需要请病假有什么特殊要求？",
  "maxResults": 3,
  "showReasoning": true
}
```

**响应示例**:
```json
{
  "success": true,
  "answer": "根据公司规定，员工请假需提前 3 天申请...",
  "originalQuestion": "公司的请假流程是什么？如果需要请病假有什么特殊要求？",
  "reasoningSteps": [
    {
      "type": "THOUGHT",
      "content": "我需要查找公司请假相关政策"
    },
    {
      "type": "ACTION",
      "actionName": "knowledge_search",
      "arguments": {"query": "请假流程"},
      "result": "找到 3 条相关内容..."
    },
    {
      "type": "THOUGHT",
      "content": "还需要确认病假的特殊要求"
    },
    {
      "type": "ACTION",
      "actionName": "knowledge_search",
      "arguments": {"query": "病假证明"},
      "result": "找到 2 条相关内容..."
    },
    {
      "type": "FINAL_ANSWER",
      "content": "根据公司规定，员工请假需提前 3 天申请..."
    }
  ],
  "formattedReasoning": "=== 推理过程 ===\n\n💭 思考：我需要查找..."
}
```

### 前端实现建议

#### 1. ReactChat 组件 (新建)

**文件位置**: `/ai-chat-frontend/src/components/ReactChat.vue`

**功能特性**:
- 💬 对话式问答界面
- 🧠 推理过程可视化展示（可折叠/展开）
- 🔧 工具调用记录展示
- 👁️ 观察结果详情查看
- ⚙️ 参数配置（最大检索数、是否显示推理过程）
- 💡 示例问题快速提问

**界面组成**:
1. **顶部标题区**: 显示"🧠 ReAct 智能问答"
2. **设置区**: 
   - 最大检索结果数滑块 (1-5)
   - 显示推理过程开关
3. **聊天对话区**:
   - 用户问题气泡
   - AI 回答气泡
   - 推理过程折叠面板（包含思考、行动、观察步骤）
4. **输入区**: 问题输入框 + 发送按钮
5. **空状态引导区**: 展示示例问题

#### 2. App.vue 导航更新

新增导航标签:
- AI 聊天（原有普通聊天）
- 知识库（文档管理）
- 📚 AI 问答（RAG 问答）
- 🧠 ReAct 问答（新增）

## 四、技术实现细节

### ReAct 系统提示词设计

```
你是一个智能的企业知识库助手，使用 ReAct (Reasoning + Acting) 模式来回答问题。

你可以使用以下工具：
1. knowledge_search: 搜索知识库中的相关文档内容
   - 参数：query (搜索查询文本)
   - 用途：当你需要查找公司政策、规章制度、流程说明等知识时使用
   
2. weather_query: 查询天气信息
   - 参数：city (城市名称)
   - 用途：当问题涉及天气时使用
   
3. order_query: 查询订单信息
   - 参数：orderId (订单号) 或 customerName (客户姓名)
   - 用途：当问题涉及订单状态、详情时使用

请按照以下格式进行思考和行动：

Thought: 我需要回答用户的问题，首先我需要...
Action: knowledge_search
Action Input: {"query": "用户问题的关键词"}
Observation: [工具返回的结果]
Thought: 根据观察结果，我还需要...
Action: ... (如果需要更多工具调用)
...
Thought: 现在我已经有足够的信息来回答问题了
Final Answer: [综合所有信息给出的完整答案]

重要规则：
1. 每次只执行一个行动 (Action)
2. 必须等待 Observation 结果后才能进行下一步
3. 如果知识库中没有相关信息，要明确告知用户
4. 在最终答案中要标注信息来源
5. 最多进行 5 次工具调用，超过后必须给出当前最佳回答
```

### 迭代控制机制

```java
private static final int MAX_ITERATIONS = 5;

int iteration = 0;
while (iteration < MAX_ITERATIONS) {
    iteration++;
    
    // 1. LLM 推理
    Response<AiMessage> response = chatLanguageModel.generate(messages, toolSpecifications);
    
    // 2. 检查是否有工具调用
    if (aiMessage.hasToolExecutionRequests()) {
        // 执行工具并继续下一轮
        continue;
    }
    
    // 3. 提取最终答案
    if (answerText.contains("Final Answer:")) {
        break;
    }
}

// 强制生成最终回答
if (finalAnswer == null) {
    messages.add(new SystemMessage("已达到最大推理次数，请根据已有信息给出最佳回答。"));
    Response<AiMessage> finalResponse = chatLanguageModel.generate(messages);
    finalAnswer = finalResponse.content().text();
}
```

## 五、使用场景对比

### 适合使用传统 RAG 的场景

- ✅ 简单直接的事实性问题
- ✅ 单一主题的信息查询
- ✅ 对响应速度要求高的场景
- ✅ 不需要复杂推理的问题

**示例**:
- "公司的年假有多少天？"
- "报销的截止时间是多久？"

### 适合使用 ReAct 的场景

- ✅ 复杂的多步骤问题
- ✅ 需要跨多个知识源的信息整合
- ✅ 需要逻辑推理和分析的问题
- ✅ 需要展示决策过程的场景
- ✅ 涉及多种工具协同的任务

**示例**:
- "我想请一周病假，需要准备什么材料？流程是怎样的？会影响年终奖吗？"
- "帮我查一下张三最近的订单状态，再看看他所在城市的天气如何？"
- "比较一下我们公司的请假政策和行业平均水平"

## 六、API 接口文档

### POST /api/chat/react

**描述**: 基于 ReAct 模式的智能问答

**请求参数**:
| 参数 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| question | String | 是 | - | 用户问题 |
| maxResults | Integer | 否 | 3 | 每次检索的最大结果数 (1-5) |
| showReasoning | Boolean | 否 | true | 是否返回推理过程 |

**响应字段**:
| 字段 | 类型 | 说明 |
|------|------|------|
| success | Boolean | 是否成功 |
| answer | String | AI 生成的最终答案 |
| originalQuestion | String | 原始问题 |
| reasoningSteps | Array | 推理步骤列表（可选） |
| reasoningSteps[].type | String | 步骤类型 (THOUGHT/ACTION/OBSERVATION/FINAL_ANSWER) |
| reasoningSteps[].content | String | 思考内容或最终答案 |
| reasoningSteps[].actionName | String | 行动名称（工具名） |
| reasoningSteps[].arguments | Object | 工具调用参数 |
| reasoningSteps[].result | String | 工具执行结果/观察内容 |
| formattedReasoning | String | 格式化后的完整推理过程 |

**错误处理**:
- 400: 问题为空
- 500: ReAct 处理失败

## 七、性能与优化

### 响应时间分析

| 阶段 | 耗时估算 | 说明 |
|------|---------|------|
| 第 1 轮推理 | 1-2 秒 | LLM 思考 + 工具调用 |
| 第 2 轮推理 | 1-2 秒 | LLM 思考 + 工具调用 |
| 第 3 轮推理 | 1-2 秒 | LLM 思考 + 工具调用 |
| 最终答案生成 | 0.5-1 秒 | LLM 综合总结 |
| **总计** | **3.5-7 秒** | 取决于迭代轮数 |

### 优化建议

1. **调整最大迭代次数**: 根据问题复杂度动态调整
2. **缓存常见推理路径**: 对高频问题缓存推理过程
3. **并行工具调用**: 如果多个工具调用无依赖关系，可并行执行
4. **流式输出**: 实时展示推理过程，提升用户体验
5. **早期终止**: 当 LLM 表示已找到答案时立即终止

## 八、测试用例

### 测试场景 1: 简单知识库查询

**输入**:
```json
{
  "question": "公司的年假政策是什么？",
  "maxResults": 3,
  "showReasoning": true
}
```

**预期行为**:
- 1-2 轮推理
- 调用 knowledge_search 工具
- 返回清晰的年假政策说明

### 测试场景 2: 复杂多步骤问题

**输入**:
```json
{
  "question": "我想请一周病假，需要准备什么？这会影响我的考勤和年终奖吗？",
  "maxResults": 3,
  "showReasoning": true
}
```

**预期行为**:
- 3-5 轮推理
- 多次调用 knowledge_search 查询不同方面
- 综合整理病假流程、材料要求、影响说明

### 测试场景 3: 跨工具协同

**输入**:
```json
{
  "question": "查一下订单 ORD001 的状态，再看看北京的天气怎么样？",
  "maxResults": 3,
  "showReasoning": true
}
```

**预期行为**:
- 调用 order_query 查询订单
- 调用 weather_query 查询天气
- 综合两个工具的结果给出回答

### 测试场景 4: 知识库无相关信息

**输入**:
```json
{
  "question": "公司的股票期权计划详情是什么？",
  "maxResults": 3,
  "showReasoning": true
}
```

**预期行为**:
- 调用 knowledge_search 但未找到相关内容
- 明确告知用户知识库中没有相关信息
- 不编造信息

## 九、文件清单

### 新增文件
- `/ai-chat-backend/src/main/java/com/example/aichat/service/ReactKnowledgeBaseService.java`
- `/ai-chat-frontend/src/components/ReactChat.vue` (待创建)

### 修改文件
- `/ai-chat-backend/src/main/java/com/example/aichat/controller/ChatController.java`
- `/ai-chat-frontend/src/App.vue` (待修改)

## 十、后续优化方向

### 短期优化
1. ✨ 添加更多企业专用工具（如 HR 系统、财务系统接口）
2. 🎯 优化系统提示词，提高推理效率
3. 📊 添加推理过程的质量评估
4. 💾 缓存常见问题的推理路径

### 中期优化
1. 🔄 支持多轮对话上下文记忆
2. 📈 基于反馈的推理策略优化
3. 🛠️ 工具使用的自学习机制
4. 🔍 混合检索增强（关键词 + 语义）

### 长期优化
1. 🤖 自主发现新工具的能力
2. 📚 从历史对话中学习推理模式
3. 🌐 跨知识库的联邦检索
4. 🎓 领域特定的推理模板

## 十一、总结

ReAct 模式为核心价值：

✅ **更强的推理能力**: 通过显式思考过程处理复杂问题  
✅ **更高的透明度**: 完整展示决策依据和推理链  
✅ **更好的可扩展性**: 轻松集成新工具  
✅ **更优的用户体验**: 让用户理解 AI 的"思考过程"  

这是企业知识库从"被动检索"到"主动推理"的重要升级，为构建真正的智能助手奠定基础。
