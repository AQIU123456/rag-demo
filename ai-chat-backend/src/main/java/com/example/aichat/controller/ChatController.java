package com.example.aichat.controller;

import com.example.aichat.dto.ChatRequest;
import com.example.aichat.dto.ChatResponse;
import com.example.aichat.entity.ChatMessage;
import com.example.aichat.repository.ChatMessageRepository;
import com.example.aichat.service.ChatService;
import com.example.aichat.service.RagService;
import com.example.aichat.service.ReactKnowledgeBaseService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/chat")
@CrossOrigin(origins = "*")
public class ChatController {
    
    private final ChatService chatService;
    private final ChatMessageRepository chatMessageRepository;
    private final RagService ragService;
    private final ReactKnowledgeBaseService reactKnowledgeBaseService;
    
    public ChatController(ChatService chatService, 
                         ChatMessageRepository chatMessageRepository,
                         RagService ragService,
                         ReactKnowledgeBaseService reactKnowledgeBaseService) {
        this.chatService = chatService;
        this.chatMessageRepository = chatMessageRepository;
        this.ragService = ragService;
        this.reactKnowledgeBaseService = reactKnowledgeBaseService;
    }
    
    @PostMapping
    public ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest request) {
        String sessionId = request.getSessionId();
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = UUID.randomUUID().toString();
        }
        
        ChatMessage userMessage = ChatMessage.builder()
                .sessionId(sessionId)
                .content(request.getMessage())
                .role(ChatMessage.MessageRole.USER)
                .build();
        chatMessageRepository.save(userMessage);
        
        String aiResponse = chatService.generateResponse(request.getMessage());
        
        ChatMessage assistantMessage = ChatMessage.builder()
                .sessionId(sessionId)
                .content(aiResponse)
                .role(ChatMessage.MessageRole.ASSISTANT)
                .build();
        chatMessageRepository.save(assistantMessage);
        
        ChatResponse response = ChatResponse.builder()
                .sessionId(sessionId)
                .message(aiResponse)
                .timestamp(LocalDateTime.now())
                .build();
        
        return ResponseEntity.ok(response);
    }
    
    /**
     * 阶段 4: RAG 智能问答接口
     * POST /api/chat/rag
     */
    @PostMapping("/rag")
    public ResponseEntity<Map<String, Object>> ragChat(@RequestBody Map<String, Object> request) {
        String question = (String) request.get("question");
        int maxResults = request.get("maxResults") != null ? 
            ((Number) request.get("maxResults")).intValue() : 5;
        
        if (question == null || question.trim().isEmpty()) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "问题不能为空");
            return ResponseEntity.badRequest().body(errorResponse);
        }
        
        try {
            // 调用 RAG 服务进行智能问答
            RagService.RagResponse ragResponse = ragService.answerWithRag(question, maxResults);
            
            // 构建响应
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("answer", ragResponse.getAnswer());
            response.put("context", ragResponse.getContext());
            
            // 添加引用来源
            List<Map<String, Object>> citations = new java.util.ArrayList<>();
            for (RagService.Citation citation : ragResponse.getCitations()) {
                Map<String, Object> citationMap = new HashMap<>();
                citationMap.put("content", citation.getContent());
                citationMap.put("score", citation.getScore());
                citationMap.put("source", citation.getSource());
                citations.add(citationMap);
            }
            response.put("citations", citations);
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "RAG 问答失败：" + e.getMessage());
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }
    
    /**
     * 阶段 5: ReAct 智能问答接口
     * POST /api/chat/react
     */
    @PostMapping("/react")
    public ResponseEntity<Map<String, Object>> reactChat(@RequestBody Map<String, Object> request) {
        String question = (String) request.get("question");
        int maxResults = request.get("maxResults") != null ? 
            ((Number) request.get("maxResults")).intValue() : 3;
        boolean showReasoning = request.get("showReasoning") != null ? 
            (Boolean) request.get("showReasoning") : true;
        
        if (question == null || question.trim().isEmpty()) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "问题不能为空");
            return ResponseEntity.badRequest().body(errorResponse);
        }
        
        try {
            // 调用 ReAct 服务进行智能问答
            ReactKnowledgeBaseService.ReactResponse reactResponse = 
                reactKnowledgeBaseService.answerWithReact(question, maxResults);
            
            // 构建响应
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("answer", reactResponse.getAnswer());
            response.put("originalQuestion", reactResponse.getOriginalQuestion());
            
            // 添加推理过程（可选）
            if (showReasoning) {
                List<Map<String, Object>> reasoningSteps = new java.util.ArrayList<>();
                for (ReactKnowledgeBaseService.ReactStep step : reactResponse.getReasoningSteps()) {
                    Map<String, Object> stepMap = new HashMap<>();
                    stepMap.put("type", step.getType().name());
                    stepMap.put("content", step.getContent());
                    stepMap.put("actionName", step.getActionName());
                    stepMap.put("arguments", step.getArguments());
                    stepMap.put("result", step.getResult());
                    reasoningSteps.add(stepMap);
                }
                response.put("reasoningSteps", reasoningSteps);
                response.put("formattedReasoning", reactResponse.getFormattedReasoning());
            }
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "ReAct 问答失败：" + e.getMessage());
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }
    
    @GetMapping("/{sessionId}")
    public ResponseEntity<List<ChatMessage>> getChatHistory(@PathVariable String sessionId) {
        List<ChatMessage> messages = chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        return ResponseEntity.ok(messages);
    }
    
    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> deleteChatHistory(@PathVariable String sessionId) {
        chatMessageRepository.deleteBySessionId(sessionId);
        return ResponseEntity.noContent().build();
    }
}
