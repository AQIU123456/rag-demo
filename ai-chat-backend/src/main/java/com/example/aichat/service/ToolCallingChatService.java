package com.example.aichat.service;

import com.example.aichat.dto.ChatResponse.ToolCallInfo;
import com.example.aichat.tool.WeatherTool;
import com.example.aichat.tool.OrderTool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static dev.langchain4j.agent.tool.ToolSpecifications.toolSpecificationsFrom;

/**
 * 支持 Tool Calling 的聊天服务
 */
@Service
public class ToolCallingChatService {

    private final ChatLanguageModel chatLanguageModel;
    private final WeatherTool weatherTool;
    private final OrderTool orderTool;

    public ToolCallingChatService(ChatLanguageModel chatLanguageModel,
                                  WeatherTool weatherTool,
                                  OrderTool orderTool) {
        this.chatLanguageModel = chatLanguageModel;
        this.weatherTool = weatherTool;
        this.orderTool = orderTool;
    }

    /**
     * 处理支持工具调用的聊天请求
     * @param userMessage 用户消息
     * @return AI 响应
     */
    public String chatWithTools(String userMessage) {
        // 构建工具规范列表
        List<ToolSpecification> toolSpecifications = new ArrayList<>();
        toolSpecifications.addAll(toolSpecificationsFrom(weatherTool));
        toolSpecifications.addAll(toolSpecificationsFrom(orderTool));

        // 创建消息历史
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new UserMessage(userMessage));

        // 第一次调用：AI 决定是否使用工具
        Response<AiMessage> response = chatLanguageModel.generate(messages, toolSpecifications);
        AiMessage aiMessage = response.content();

        // 检查是否需要执行工具
        if (aiMessage.hasToolExecutionRequests()) {
            // 执行工具调用
            List<ToolExecutionResultMessage> toolResults = executeTools(aiMessage);
            
            // 将工具结果添加到消息历史
            messages.add(aiMessage);
            messages.addAll(toolResults);

            // 第二次调用：AI 根据工具结果生成最终回复
            Response<AiMessage> finalResponse = chatLanguageModel.generate(messages);
            return finalResponse.content().text();
        }

        // 不需要工具，直接返回 AI 回复
        return aiMessage.text();
    }

    /**
     * 处理支持工具调用的聊天请求（返回详细工具调用信息）
     * @param userMessage 用户消息
     * @return 包含工具调用信息的响应对象
     */
    public ChatResponseData chatWithToolsDetailed(String userMessage) {
        // 构建工具规范列表
        List<ToolSpecification> toolSpecifications = new ArrayList<>();
        toolSpecifications.addAll(toolSpecificationsFrom(weatherTool));
        toolSpecifications.addAll(toolSpecificationsFrom(orderTool));

        // 创建消息历史
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new UserMessage(userMessage));

        // 第一次调用：AI 决定是否使用工具
        Response<AiMessage> response = chatLanguageModel.generate(messages, toolSpecifications);
        AiMessage aiMessage = response.content();

        List<ToolCallInfo> toolCalls = new ArrayList<>();

        // 检查是否需要执行工具
        if (aiMessage.hasToolExecutionRequests()) {
            // 执行工具调用并收集信息
            List<ToolExecutionResultMessage> toolResults = new ArrayList<>();
            
            for (ToolExecutionRequest request : aiMessage.toolExecutionRequests()) {
                String result;
                String description = "";
                Map<String, Object> argumentsMap = parseArguments(request.arguments());
                
                switch (request.name()) {
                    case "getWeather":
                        String city = extractArgument(request.arguments(), "city");
                        result = weatherTool.getWeather(city != null ? city : "北京");
                        description = "查询天气信息";
                        break;
                        
                    case "getOrderInfo":
                        String orderId = extractArgument(request.arguments(), "orderId");
                        result = orderTool.getOrderInfo(orderId != null ? orderId : "");
                        description = "查询订单详情";
                        break;
                        
                    case "getOrdersByCustomer":
                        String customerName = extractArgument(request.arguments(), "customerName");
                        result = orderTool.getOrdersByCustomer(customerName != null ? customerName : "");
                        description = "查询客户订单列表";
                        break;
                        
                    default:
                        result = "未知的工具：" + request.name();
                        description = "未知工具";
                }

                toolCalls.add(ToolCallInfo.builder()
                    .name(request.name())
                    .description(description)
                    .arguments(argumentsMap)
                    .result(result)
                    .build());
                    
                toolResults.add(ToolExecutionResultMessage.from(request, result));
            }
            
            // 将工具结果添加到消息历史
            messages.add(aiMessage);
            messages.addAll(toolResults);

            // 第二次调用：AI 根据工具结果生成最终回复
            Response<AiMessage> finalResponse = chatLanguageModel.generate(messages);
            String finalText = finalResponse.content().text();
            
            return new ChatResponseData(finalText, toolCalls);
        }

        // 不需要工具，直接返回 AI 回复
        return new ChatResponseData(aiMessage.text(), toolCalls);
    }

    /**
     * 执行工具调用
     * @param aiMessage 包含工具执行请求的 AI 消息
     * @return 工具执行结果列表
     */
    private List<ToolExecutionResultMessage> executeTools(AiMessage aiMessage) {
        List<ToolExecutionResultMessage> results = new ArrayList<>();

        aiMessage.toolExecutionRequests().forEach(request -> {
            String result;
            
            switch (request.name()) {
                case "getWeather":
                    String city = extractArgument(request.arguments(), "city");
                    result = weatherTool.getWeather(city != null ? city : "北京");
                    break;
                    
                case "getOrderInfo":
                    String orderId = extractArgument(request.arguments(), "orderId");
                    result = orderTool.getOrderInfo(orderId != null ? orderId : "");
                    break;
                    
                case "getOrdersByCustomer":
                    String customerName = extractArgument(request.arguments(), "customerName");
                    result = orderTool.getOrdersByCustomer(customerName != null ? customerName : "");
                    break;
                    
                default:
                    result = "未知的工具：" + request.name();
            }

            results.add(ToolExecutionResultMessage.from(request, result));
        });

        return results;
    }

    /**
     * 从参数 Map 中提取指定参数值
     * @param arguments 参数字符串（JSON 格式）
     * @param paramName 参数名
     * @return 参数值
     */
    private String extractArgument(String arguments, String paramName) {
        if (arguments == null || arguments.isEmpty()) {
            return null;
        }
        
        // 简单的 JSON 解析（针对 LangChain4j 的参数格式）
        // 格式示例：{"city": "北京"}
        String searchKey = "\"" + paramName + "\"";
        int keyIndex = arguments.indexOf(searchKey);
        
        if (keyIndex == -1) {
            return null;
        }

        // 找到冒号后的值
        int colonIndex = arguments.indexOf(":", keyIndex);
        if (colonIndex == -1) {
            return null;
        }

        // 提取值（去除引号和空格）
        int startIndex = colonIndex + 1;
        while (startIndex < arguments.length() && 
               (arguments.charAt(startIndex) == ' ' || arguments.charAt(startIndex) == '"')) {
            startIndex++;
        }

        int endIndex = arguments.indexOf('"', startIndex);
        if (endIndex == -1) {
            endIndex = arguments.indexOf('}', startIndex);
        }

        if (endIndex == -1 || endIndex <= startIndex) {
            return null;
        }

        return arguments.substring(startIndex, endIndex).trim();
    }
    
    /**
     * 解析 JSON 参数字符串为 Map
     * @param arguments JSON 参数字符串
     * @return 参数 Map
     */
    private Map<String, Object> parseArguments(String arguments) {
        Map<String, Object> result = new HashMap<>();
        if (arguments == null || arguments.isEmpty()) {
            return result;
        }
        
        // 简单解析 JSON 对象 {"key": "value"}
        String clean = arguments.trim();
        if (clean.startsWith("{") && clean.endsWith("}")) {
            clean = clean.substring(1, clean.length() - 1).trim();
        }
        
        String[] pairs = clean.split(",");
        for (String pair : pairs) {
            String[] keyValue = pair.split(":");
            if (keyValue.length == 2) {
                String key = keyValue[0].trim().replace("\"", "");
                String value = keyValue[1].trim().replace("\"", "");
                result.put(key, value);
            }
        }
        
        return result;
    }
    
    /**
     * 内部类：封装聊天响应数据
     */
    public static class ChatResponseData {
        private final String message;
        private final List<ToolCallInfo> toolCalls;
        
        public ChatResponseData(String message, List<ToolCallInfo> toolCalls) {
            this.message = message;
            this.toolCalls = toolCalls;
        }
        
        public String getMessage() {
            return message;
        }
        
        public List<ToolCallInfo> getToolCalls() {
            return toolCalls;
        }
    }
}
