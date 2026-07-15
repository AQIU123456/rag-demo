package com.example.aichat.service;

import com.example.aichat.dto.ChatRequest;
import com.example.aichat.dto.ChatResponse;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.springframework.stereotype.Service;

@Service
public class ChatService {
    
    private final ChatLanguageModel chatLanguageModel;
    
    public ChatService(ChatLanguageModel chatLanguageModel) {
        this.chatLanguageModel = chatLanguageModel;
    }
    
    public String generateResponse(String userMessage) {
        return chatLanguageModel.generate(userMessage);
    }
}
