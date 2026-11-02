package com.example.aichat.controller;

import com.example.aichat.dto.ChatRequest;
import com.example.aichat.dto.ChatResponse;
import com.example.aichat.dto.RagChatRequest;
import com.example.aichat.dto.RagChatResponse;
import com.example.aichat.dto.ReactChatRequest;
import com.example.aichat.dto.ReactChatResponse;
import com.example.aichat.entity.ChatMessage;
import com.example.aichat.repository.ChatMessageRepository;
import com.example.aichat.service.ChatService;
import com.example.aichat.service.RagService;
import com.example.aichat.service.ReactKnowledgeBaseService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

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
    public ResponseEntity<RagChatResponse> ragChat(@RequestBody RagChatRequest request) {
        String question = request.getQuestion();
        int maxResults = request.getMaxResults() != null ? request.getMaxResults() : 5;

        if (question == null || question.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(RagChatResponse.builder()
                    .success(false)
                    .message("问题不能为空")
                    .build());
        }

        try {
            // 调用 RAG 服务进行智能问答
            RagService.RagResponse ragResponse = ragService.answerWithRag(question, maxResults);

            // 添加引用来源
            List<RagChatResponse.CitationDTO> citations = ragResponse.getCitations().stream()
                    .map(citation -> RagChatResponse.CitationDTO.builder()
                            .content(citation.getContent())
                            .score(citation.getScore())
                            .source(citation.getSource())
                            .build())
                    .collect(Collectors.toList());

            // 构建响应
            RagChatResponse response = RagChatResponse.builder()
                    .success(true)
                    .answer(ragResponse.getAnswer())
                    .context(ragResponse.getContext())
                    .citations(citations)
                    .build();

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(RagChatResponse.builder()
                    .success(false)
                    .message("RAG 问答失败：" + e.getMessage())
                    .build());
        }
    }
    
    /**
     * 阶段 5: ReAct 智能问答接口
     * POST /api/chat/react
     */
    @PostMapping("/react")
    public ResponseEntity<ReactChatResponse> reactChat(@RequestBody ReactChatRequest request) {
        String question = request.getQuestion();
        int maxResults = request.getMaxResults() != null ? request.getMaxResults() : 3;
        boolean showReasoning = request.getShowReasoning() != null ? request.getShowReasoning() : true;

        if (question == null || question.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(ReactChatResponse.builder()
                    .success(false)
                    .message("问题不能为空")
                    .build());
        }

        try {
            // 调用 ReAct 服务进行智能问答
            ReactKnowledgeBaseService.ReactResponse reactResponse =
                reactKnowledgeBaseService.answerWithReact(question, maxResults);

            ReactChatResponse.ReactChatResponseBuilder builder = ReactChatResponse.builder()
                    .success(true)
                    .answer(reactResponse.getAnswer())
                    .originalQuestion(reactResponse.getOriginalQuestion());

            // 添加推理过程（可选）
            if (showReasoning) {
                List<ReactChatResponse.ReasoningStepDTO> reasoningSteps =
                    reactResponse.getReasoningSteps().stream()
                        .map(step -> ReactChatResponse.ReasoningStepDTO.builder()
                                .type(step.getType().name())
                                .content(step.getContent())
                                .actionName(step.getActionName())
                                .arguments(step.getArguments())
                                .result(step.getResult())
                                .build())
                        .collect(Collectors.toList());
                builder.reasoningSteps(reasoningSteps);
                builder.formattedReasoning(reactResponse.getFormattedReasoning());
            }

            return ResponseEntity.ok(builder.build());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(ReactChatResponse.builder()
                    .success(false)
                    .message("ReAct 问答失败：" + e.getMessage())
                    .build());
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
