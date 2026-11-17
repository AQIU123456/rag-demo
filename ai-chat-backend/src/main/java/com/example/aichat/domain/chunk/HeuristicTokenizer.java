package com.example.aichat.domain.chunk;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.Tokenizer;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 零依赖 Tokenizer：中文 1 字 ≈ 1 token、ASCII 4 字符 ≈ 1 token，保守上取整。
 *
 * <p>不引入 jtokkit / OpenAiTokenizer，避免离线仓库缺 jar 或未知模型名导致启动失败。
 * 估算偏保守（宁可多算），配合 hard-max-chars 兜底，可度量截断率后再校准。</p>
 */
@Component
@Primary
public class HeuristicTokenizer implements Tokenizer {

    /**
     * 估算送嵌文本的 token 数：ASCII 记 0.25、其余（含中文）记 1，最后向上取整。
     * 与 OpenAI 真实 BPE 有偏差，但方向保守（宁可多算），可保证切分不越过模型上限。
     */
    @Override
    public int estimateTokenCountInText(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        double tokens = 0d;
        for (int i = 0; i < text.length(); i++) {
            tokens += text.charAt(i) < 0x80 ? 0.25d : 1.0d;
        }
        return (int) Math.ceil(tokens);
    }

    /** 消息级估算：项目内未做精确计数，退化为整条消息文本估算 */
    @Override
    public int estimateTokenCountInMessage(ChatMessage message) {
        return message == null ? 0 : estimateTokenCountInText(String.valueOf(message));
    }

    /** 多条消息累计 */
    @Override
    public int estimateTokenCountInMessages(Iterable<ChatMessage> messages) {
        if (messages == null) {
            return 0;
        }
        int total = 0;
        for (ChatMessage m : messages) {
            total += estimateTokenCountInMessage(m);
        }
        return total;
    }

    /** 工具定义估算（仅满足接口契约，切分链路不使用） */
    @Override
    public int estimateTokenCountInToolSpecifications(Iterable<ToolSpecification> specs) {
        if (specs == null) {
            return 0;
        }
        int total = 0;
        for (ToolSpecification s : specs) {
            total += estimateTokenCountInText(String.valueOf(s));
        }
        return total;
    }

    /** 工具调用请求估算（仅满足接口契约，切分链路不使用） */
    @Override
    public int estimateTokenCountInToolExecutionRequests(Iterable<ToolExecutionRequest> requests) {
        if (requests == null) {
            return 0;
        }
        int total = 0;
        for (ToolExecutionRequest r : requests) {
            total += estimateTokenCountInText(String.valueOf(r));
        }
        return total;
    }
}
