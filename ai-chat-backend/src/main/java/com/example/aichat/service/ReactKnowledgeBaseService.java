package com.example.aichat.service;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static dev.langchain4j.agent.tool.ToolSpecifications.toolSpecificationsFrom;

/**
 * 基于 ReAct 模式的知识库问答服务
 * ReAct = Reasoning (推理) + Acting (行动)
 * 
 * 核心流程：
 * 1. Thought: 分析问题，思考需要什么信息
 * 2. Action: 决定调用什么工具（知识库检索、其他工具等）
 * 3. Observation: 观察工具返回的结果
 * 4. 循环：重复上述过程，直到能够完整回答问题
 * 5. Final Answer: 综合所有信息给出最终答案
 */
@Service
public class ReactKnowledgeBaseService {

    private final ChatLanguageModel chatLanguageModel;
    private final EmbeddingService embeddingService;
    
    // 最大迭代次数，防止无限循环
    private static final int MAX_ITERATIONS = 5;
    
    // ReAct 系统提示词模板
    private static final String REACT_SYSTEM_PROMPT = """
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
        5. 最多进行 %d 次工具调用，超过后必须给出当前最佳回答
        """;

    public ReactKnowledgeBaseService(ChatLanguageModel chatLanguageModel,
                                     EmbeddingService embeddingService) {
        this.chatLanguageModel = chatLanguageModel;
        this.embeddingService = embeddingService;
    }

    /**
     * 使用 ReAct 模式回答知识库问题
     * @param question 用户问题
     * @param maxResults 每次检索的最大结果数
     * @return ReAct 问答响应
     */
    public ReactResponse answerWithReact(String question, int maxResults) {
        List<ReactStep> steps = new ArrayList<>();
        
        // 构建工具规范列表
        List<ToolSpecification> toolSpecifications = buildToolSpecifications();
        
        // 构建系统提示词
        String systemPrompt = String.format(REACT_SYSTEM_PROMPT, MAX_ITERATIONS);
        
        // 初始化消息历史
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));
        messages.add(new UserMessage(question));
        
        int iteration = 0;
        String finalAnswer = null;
        
        while (iteration < MAX_ITERATIONS) {
            iteration++;
            
            // 调用 LLM 进行推理
            Response<AiMessage> response = chatLanguageModel.generate(messages, toolSpecifications);
            AiMessage aiMessage = response.content();
            
            // 记录思考过程
            String thought = extractThought(aiMessage.text());
            if (thought != null && !thought.isEmpty()) {
                steps.add(new ReactStep(StepType.THUGHT, thought, null, null));
            }
            
            // 检查是否需要执行工具
            if (aiMessage.hasToolExecutionRequests()) {
                List<ToolExecutionRequest> requests = aiMessage.toolExecutionRequests();
                
                for (ToolExecutionRequest request : requests) {
                    // 执行工具调用
                    String result = executeTool(request, maxResults);
                    
                    // 记录行动和观察
                    steps.add(new ReactStep(
                        StepType.ACTION,
                        request.name(),
                        parseArguments(request.arguments()),
                        result
                    ));
                    
                    // 将工具结果添加到消息历史
                    messages.add(aiMessage);
                    messages.add(ToolExecutionResultMessage.from(request, result));
                }
                
                // 继续下一轮推理
                continue;
            }
            
            // 如果没有工具调用请求，说明 AI 已经准备好给出最终答案
            String answerText = aiMessage.text();
            if (answerText != null && !answerText.isEmpty()) {
                // 提取 Final Answer
                if (answerText.contains("Final Answer:")) {
                    finalAnswer = answerText.substring(answerText.indexOf("Final Answer:") + 13).trim();
                } else {
                    finalAnswer = answerText;
                }
                steps.add(new ReactStep(StepType.FINAL_ANSWER, finalAnswer, null, null));
                break;
            }
        }
        
        // 如果达到最大迭代次数仍未得到答案，强制生成最终回答
        if (finalAnswer == null) {
            messages.add(new SystemMessage("已达到最大推理次数，请根据已有信息给出最佳回答。"));
            Response<AiMessage> finalResponse = chatLanguageModel.generate(messages);
            finalAnswer = finalResponse.content().text();
            steps.add(new ReactStep(StepType.FINAL_ANSWER, finalAnswer, null, null));
        }
        
        return new ReactResponse(finalAnswer, steps, question);
    }

    /**
     * 构建可用的工具规范列表
     */
    private List<ToolSpecification> buildToolSpecifications() {
        List<ToolSpecification> specifications = new ArrayList<>();
        
        // 添加知识库检索工具
        specifications.add(ToolSpecification.builder()
            .name("knowledge_search")
            .description("搜索企业知识库中的相关文档内容，用于回答公司政策、制度、流程等问题")
            .addParameter("query", dev.langchain4j.agent.tool.JsonSchemaProperty.STRING)
            .build());
        
        // 添加天气查询工具
        specifications.add(ToolSpecification.builder()
            .name("weather_query")
            .description("查询指定城市的天气信息")
            .addParameter("city", dev.langchain4j.agent.tool.JsonSchemaProperty.STRING)
            .build());
        
        // 添加订单查询工具
        specifications.add(ToolSpecification.builder()
            .name("order_query")
            .description("查询订单的详细信息，包括状态、金额、收货人等")
            .addParameter("orderId", dev.langchain4j.agent.tool.JsonSchemaProperty.STRING)
            .addOptionalParameter("customerName", dev.langchain4j.agent.tool.JsonSchemaProperty.STRING)
            .build());
        
        return specifications;
    }

    /**
     * 执行工具调用
     */
    private String executeTool(ToolExecutionRequest request, int maxResults) {
        switch (request.name()) {
            case "knowledge_search":
                Map<String, Object> searchArgs = parseArguments(request.arguments());
                String query = (String) searchArgs.get("query");
                if (query == null || query.isEmpty()) {
                    return "错误：搜索查询不能为空";
                }
                return executeKnowledgeSearch(query, maxResults);
                
            case "weather_query":
                Map<String, Object> weatherArgs = parseArguments(request.arguments());
                String city = (String) weatherArgs.get("city");
                if (city == null || city.isEmpty()) {
                    city = "北京";
                }
                return executeWeatherQuery(city);
                
            case "order_query":
                Map<String, Object> orderArgs = parseArguments(request.arguments());
                String orderId = (String) orderArgs.get("orderId");
                String customerName = (String) orderArgs.get("customerName");
                return executeOrderQuery(orderId, customerName);
                
            default:
                return "错误：未知的工具 " + request.name();
        }
    }

    /**
     * 执行知识库搜索
     */
    private String executeKnowledgeSearch(String query, int maxResults) {
        try {
            List<EmbeddingService.SearchResult> results = 
                embeddingService.searchSimilar(query, maxResults);
            
            if (results.isEmpty()) {
                return "未在知识库中找到相关内容。";
            }
            
            StringBuilder sb = new StringBuilder();
            sb.append("找到 ").append(results.size()).append(" 条相关内容：\n\n");
            
            for (int i = 0; i < results.size(); i++) {
                EmbeddingService.SearchResult result = results.get(i);
                sb.append(String.format("[%d] ", i + 1));
                sb.append(result.getContent());
                sb.append(String.format(" (相似度：%.2f, 来源：%s)\n\n", 
                    result.getScore(), result.getSource()));
            }
            
            return sb.toString();
        } catch (Exception e) {
            return "知识库搜索失败：" + e.getMessage();
        }
    }

    /**
     * 执行天气查询（Mock 实现）
     */
    private String executeWeatherQuery(String city) {
        String[] conditions = {"晴", "多云", "小雨", "大雨", "雷阵雨", "雪", "雾"};
        String condition = conditions[(int) (Math.random() * conditions.length)];
        int temp = (int) (Math.random() * 46) - 10;
        int humidity = 30 + (int) (Math.random() * 61);
        int wind = 1 + (int) (Math.random() * 8);
        
        return String.format(
            "【%s 天气】天气状况：%s | 温度：%d°C | 湿度：%d%% | 风力：%d级",
            city, condition, temp, humidity, wind
        );
    }

    /**
     * 执行订单查询（Mock 实现）
     */
    private String executeOrderQuery(String orderId, String customerName) {
        if (orderId != null && !orderId.isEmpty()) {
            // Mock 订单数据
            if ("ORD001".equals(orderId)) {
                return "【订单详情】订单号：ORD001 | 收货人：张三 | 状态：已发货 | 金额：¥299.00 | 日期：2024-01-15";
            } else if ("ORD002".equals(orderId)) {
                return "【订单详情】订单号：ORD002 | 收货人：李四 | 状态：配送中 | 金额：¥599.00 | 日期：2024-01-16";
            } else {
                return "未找到订单号为 " + orderId + " 的订单信息。";
            }
        } else if (customerName != null && !customerName.isEmpty()) {
            return "【客户订单】" + customerName + " 共有 2 个订单：ORD001(已发货), ORD003(已完成)";
        } else {
            return "错误：需要提供订单号或客户姓名";
        }
    }

    /**
     * 从 AI 消息中提取思考内容
     */
    private String extractThought(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        
        // 提取 Thought 部分
        if (text.contains("Thought:")) {
            int startIndex = text.indexOf("Thought:") + 8;
            int endIndex = text.indexOf("\n", startIndex);
            if (endIndex == -1) {
                endIndex = text.length();
            }
            return text.substring(startIndex, endIndex).trim();
        }
        
        return null;
    }

    /**
     * 解析 JSON 参数字符串为 Map
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArguments(String arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return new java.util.HashMap<>();
        }
        
        // 简单 JSON 解析
        String clean = arguments.trim();
        if (clean.startsWith("{") && clean.endsWith("}")) {
            clean = clean.substring(1, clean.length() - 1).trim();
        }
        
        Map<String, Object> result = new java.util.HashMap<>();
        String[] pairs = clean.split(",");
        for (String pair : pairs) {
            String[] keyValue = pair.split(":");
            if (keyValue.length == 2) {
                String key = keyValue[0].trim().replace("\"", "");
                String value = keyValue[1].trim().replace("\"", "").trim();
                result.put(key, value);
            }
        }
        
        return result;
    }

    /**
     * ReAct 响应类
     */
    public static class ReactResponse {
        private final String answer;
        private final List<ReactStep> reasoningSteps;
        private final String originalQuestion;

        public ReactResponse(String answer, List<ReactStep> reasoningSteps, String originalQuestion) {
            this.answer = answer;
            this.reasoningSteps = reasoningSteps;
            this.originalQuestion = originalQuestion;
        }

        public String getAnswer() {
            return answer;
        }

        public List<ReactStep> getReasoningSteps() {
            return reasoningSteps;
        }

        public String getOriginalQuestion() {
            return originalQuestion;
        }

        /**
         * 获取格式化的推理过程（用于展示）
         */
        public String getFormattedReasoning() {
            StringBuilder sb = new StringBuilder();
            sb.append("=== 推理过程 ===\n\n");
            
            for (ReactStep step : reasoningSteps) {
                switch (step.getType()) {
                    case THUGHT:
                        sb.append("💭 思考：").append(step.getContent()).append("\n\n");
                        break;
                    case ACTION:
                        sb.append("🔧 行动：").append(step.getActionName())
                          .append("\n   参数：").append(step.getArguments())
                          .append("\n\n");
                        break;
                    case OBSERVATION:
                        sb.append("👁️ 观察：").append(step.getResult()).append("\n\n");
                        break;
                    case FINAL_ANSWER:
                        sb.append("✅ 最终答案：").append(step.getContent()).append("\n");
                        break;
                }
            }
            
            return sb.toString();
        }
    }

    /**
     * ReAct 步骤类
     */
    public static class ReactStep {
        private final StepType type;
        private final String content;
        private final String actionName;
        private final Map<String, Object> arguments;
        private final String result;

        public ReactStep(StepType type, String content, Map<String, Object> arguments, String result) {
            this.type = type;
            this.content = content;
            this.actionName = content; // For ACTION type, content is the action name
            this.arguments = arguments;
            this.result = result;
        }

        public StepType getType() {
            return type;
        }

        public String getContent() {
            return content;
        }

        public String getActionName() {
            return actionName;
        }

        public Map<String, Object> getArguments() {
            return arguments;
        }

        public String getResult() {
            return result;
        }
    }

    /**
     * 步骤类型枚举
     */
    public enum StepType {
        THUGHT,      // 思考
        ACTION,      // 行动
        OBSERVATION, // 观察
        FINAL_ANSWER // 最终答案
    }
}
