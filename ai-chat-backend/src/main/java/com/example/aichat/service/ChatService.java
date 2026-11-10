package com.example.aichat.service;

import com.example.aichat.security.PromptInjectionGuard;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ChatService {
    
    private final ChatLanguageModel chatLanguageModel;
    
    public ChatService(ChatLanguageModel chatLanguageModel) {
        this.chatLanguageModel = chatLanguageModel;
    }
    
    public String generateResponse(String userMessage) {
        // 安全修复 1：用户输入消毒（直接注入面：忽略指令 / 提示词套取 / Base64 混淆 / 角色劫持）
        String safeInput = PromptInjectionGuard.sanitizeUserInput(userMessage);

        // 安全修复 2：补齐 SystemMessage，显式声明定界块内为数据、禁止泄露内部指令
        String systemPrompt = PromptInjectionGuard.systemInstructions(
                "请正常、简洁、专业地回答用户的问题。");

        // 安全修复 3：指令/数据分层 —— SystemMessage 只放指令，不可信输入放独立 UserMessage 定界块
        List<ChatMessage> messages = List.of(
                SystemMessage.from(systemPrompt),
                UserMessage.from(PromptInjectionGuard.wrapAsData(safeInput, "用户输入"))
        );

        Response<AiMessage> response = chatLanguageModel.generate(messages);

        // 安全修复 4：输出校验，拦截系统提示词泄露
        return PromptInjectionGuard.guardOutput(response.content().text(), systemPrompt);
    }
}
