package com.example.aichat.controller;

import com.example.aichat.dto.ChatRequest;
import com.example.aichat.dto.ChatResponse;
import com.example.aichat.entity.ChatMessage;
import com.example.aichat.repository.ChatMessageRepository;
import com.example.aichat.service.ToolCallingChatService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tool Calling 聊天控制器
 * 提供支持工具调用的聊天接口
 */
@RestController
@RequestMapping("/api/chat/tools")
@CrossOrigin(origins = "*")
public class ToolCallingController {

    private final ToolCallingChatService toolCallingChatService;
    private final ChatMessageRepository chatMessageRepository;

    public ToolCallingController(ToolCallingChatService toolCallingChatService,
                                 ChatMessageRepository chatMessageRepository) {
        this.toolCallingChatService = toolCallingChatService;
        this.chatMessageRepository = chatMessageRepository;
    }

    /**
     * 支持 Tool Calling 的聊天接口
     * POST /api/chat/tools
     * 
     * 示例请求：
     * {
     *   "message": "北京今天天气怎么样？",
     *   "sessionId": "optional-session-id"
     * }
     * 
     * 示例请求：
     * {
     *   "message": "帮我查一下订单 ORD001 的状态",
     *   "sessionId": "optional-session-id"
     * }
     */
    @PostMapping
    public ResponseEntity<ChatResponse> chatWithTools(@RequestBody ChatRequest request) {
        String sessionId = request.getSessionId();
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = UUID.randomUUID().toString();
        }

        // 保存用户消息
        ChatMessage userMessage = ChatMessage.builder()
                .sessionId(sessionId)
                .content(request.getMessage())
                .role(ChatMessage.MessageRole.USER)
                .build();
        chatMessageRepository.save(userMessage);

        // 调用支持 Tool Calling 的聊天服务（获取详细工具调用信息）
        ToolCallingChatService.ChatResponseData responseData = 
            toolCallingChatService.chatWithToolsDetailed(request.getMessage());

        // 保存 AI 响应
        ChatMessage assistantMessage = ChatMessage.builder()
                .sessionId(sessionId)
                .content(responseData.getMessage())
                .role(ChatMessage.MessageRole.ASSISTANT)
                .build();
        chatMessageRepository.save(assistantMessage);

        ChatResponse response = ChatResponse.builder()
                .sessionId(sessionId)
                .message(responseData.getMessage())
                .timestamp(LocalDateTime.now())
                .toolCalls(responseData.getToolCalls())
                .build();

        return ResponseEntity.ok(response);
    }

    /**
     * 获取可用的工具列表
     * GET /api/chat/tools/list
     */
    @GetMapping("/list")
    public ResponseEntity<Map<String, Object>> listTools() {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        
        List<Map<String, String>> tools = List.of(
            createToolInfo("getWeather", "天气查询", "查询指定城市的天气信息", "city: 城市名称"),
            createToolInfo("getOrderInfo", "订单查询", "根据订单号查询订单详细信息", "orderId: 订单号"),
            createToolInfo("getOrdersByCustomer", "客户订单查询", "根据客户姓名查询其所有订单", "customerName: 客户姓名")
        );
        
        response.put("tools", tools);
        return ResponseEntity.ok(response);
    }

    private Map<String, String> createToolInfo(String name, String title, 
                                                String description, String parameters) {
        Map<String, String> tool = new HashMap<>();
        tool.put("name", name);
        tool.put("title", title);
        tool.put("description", description);
        tool.put("parameters", parameters);
        return tool;
    }
}
