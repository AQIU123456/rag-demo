package com.example.aichat.service;

import com.example.aichat.config.RerankConfig;
import com.example.aichat.security.PromptInjectionGuard;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * RAG (Retrieval-Augmented Generation) 服务
 * 实现基于知识库的检索增强生成问答
 */
@Service
public class RagService {

    /** 生成模型（只负责按上下文作答，检索与切分在别处） */
    private final ChatLanguageModel chatLanguageModel;
    /** 检索门面：内部已完成混合召回、过滤与精排 */
    private final EmbeddingService embeddingService;
    /** 相似度过滤：最高分不够格时直接拒答，省掉一次生成开销并防止硬编 */
    private final RelevanceFilter relevanceFilter;

    /** 兼容单测与遗留调用：使用默认配置，过滤开关不生效，行为与改造前一致 */
    public RagService(ChatLanguageModel chatLanguageModel,
                     EmbeddingService embeddingService) {
        this(chatLanguageModel, embeddingService, new RelevanceFilter(new RerankConfig()));
    }

    @Autowired
    public RagService(ChatLanguageModel chatLanguageModel,
                     EmbeddingService embeddingService,
                     RelevanceFilter relevanceFilter) {
        this.chatLanguageModel = chatLanguageModel;
        this.embeddingService = embeddingService;
        this.relevanceFilter = relevanceFilter;
    }

    /**
     * 基于知识库的 RAG 问答
     * @param question 用户问题
     * @param maxResults 最大检索结果数
     * @return RAG 问答结果
     */
    public RagResponse answerWithRag(String question, int maxResults) {
        // 1. 从知识库中检索相关文档片段（内部已完成混合召回、相似度过滤与精排）
        List<EmbeddingService.SearchResult> searchResults = 
            embeddingService.searchSimilar(
                PromptInjectionGuard.sanitizeUserInput(question), maxResults);

        // 1.1 过滤后最高分仍不达标 → 跳过 LLM 直接拒答：
        //     既省一次生成开销，也避免模型面对无关上下文硬编造成幻觉
        if (relevanceFilter.shouldReject(searchResults)) {
            return new RagResponse(
                "知识库中没有找到与该问题相关的内容，建议换个说法或补充相关文档后再试。",
                "未找到相关知识库内容。",
                searchResults,
                List.of());
        }

        // 2. 构建上下文内容
        String context = buildContext(searchResults);
        
        // 3. 构建系统提示词
        String systemPrompt = buildSystemPrompt(context);
        
        // 4. 调用 LLM 生成答案
        String answer = generateAnswer(systemPrompt, context, question);
        
        // 5. 提取引用来源
        List<Citation> citations = extractCitations(searchResults);
        
        return new RagResponse(answer, context, searchResults, citations);
    }

    /**
     * 拼装送 LLM 的上下文。注意这里用的是 contextText 而非 content：
     * 检索单元是子块（精准命中），生成单元是父块/邻域（语义完整）。
     */
    private String buildContext(List<EmbeddingService.SearchResult> searchResults) {
        if (searchResults.isEmpty()) {
            return "未找到相关知识库内容。";
        }
        
        StringBuilder contextBuilder = new StringBuilder();
        contextBuilder.append("以下是从知识库中检索到的相关信息：\n\n");
        
        for (int i = 0; i < searchResults.size(); i++) {
            EmbeddingService.SearchResult result = searchResults.get(i);
            contextBuilder.append(String.format("[%d] ", i + 1));
            // 检索单元 ≠ 生成单元：父子 / 相邻块扩展后的上下文送 LLM，引用仍展示命中块原文
            String forLlm = result.getContextText() != null
                    ? result.getContextText() : result.getContent();
            contextBuilder.append(PromptInjectionGuard.sanitizeDocument(forLlm));
            // getScore() 返回最终相关性分数（启用重排时为重排精排分数）
            contextBuilder.append(String.format(" (相关度：%.2f)\n\n", result.getScore()));
        }
        
        return contextBuilder.toString();
    }

    /**
     * 构建系统提示词
     */
    private String buildSystemPrompt(String context) {
        // 安全修复：不可信的检索上下文不再拼接进 SystemMessage。
        // context 参数保留以兼容既有调用链，实际由 generateAnswer 放进独立 UserMessage 定界块。
        return PromptInjectionGuard.systemInstructions(
               "1. 只基于 <UNTRUSTED_DATA> 块中提供的知识库内容回答，不要编造信息\n" +
               "2. 如果知识库中没有相关信息，请明确告知用户\n" +
               "3. 回答要准确、简洁、专业\n" +
               "4. 在回答中引用相关内容的编号，例如 [1]、[2]\n" +
               "5. 如果有多条相关信息，请综合整理后回答");
    }

    /**
     * 调用 LLM 生成答案
     */
    private String generateAnswer(String systemPrompt, String question) {
        return generateAnswer(systemPrompt, "", question);
    }

    /**
     * 指令/数据分层后的生成入口：SystemMessage 只放固定指令；
     * 检索上下文与用户问题分别封装为独立的 UserMessage 定界块。
     */
    private String generateAnswer(String systemPrompt, String context, String question) {
        try {
            List<ChatMessage> messages = List.of(
                SystemMessage.from(systemPrompt),
                UserMessage.from(PromptInjectionGuard.wrapAsData(context, "知识库检索结果")),
                UserMessage.from(PromptInjectionGuard.wrapAsData(
                    PromptInjectionGuard.sanitizeUserInput(question), "用户问题"))
            );
            Response<AiMessage> response = chatLanguageModel.generate(messages);
            return PromptInjectionGuard.guardOutput(response.content().text(), systemPrompt);
        } catch (Exception e) {
            return "抱歉，生成答案时出现错误：" + e.getMessage();
        }
    }

    /**
     * 提取引用来源
     */
    private List<Citation> extractCitations(List<EmbeddingService.SearchResult> searchResults) {
        return searchResults.stream()
            .map(result -> new Citation(
                PromptInjectionGuard.sanitizeDocument(result.getContent()),
                result.getScore(),
                PromptInjectionGuard.sanitizeLabel(result.getSource()),
                result.getChunkId(),
                result.getDocumentId(),
                result.getDocumentTitle(),
                result.getSectionPath()
            ))
            .collect(Collectors.toList());
    }

    /**
     * RAG 问答响应
     */
    public static class RagResponse {
        /** LLM 生成的答案 */
        private final String answer;
        /** 实际送 LLM 的上下文（便于前端调试与评估对齐） */
        private final String context;
        /** 原始检索结果，含向量分/融合分/溯源字段 */
        private final List<EmbeddingService.SearchResult> searchResults;
        /** 引用列表（已消毒，可直接展示） */
        private final List<Citation> citations;

        public RagResponse(String answer, String context, 
                          List<EmbeddingService.SearchResult> searchResults,
                          List<Citation> citations) {
            this.answer = answer;
            this.context = context;
            this.searchResults = searchResults;
            this.citations = citations;
        }

        public String getAnswer() {
            return answer;
        }

        public String getContext() {
            return context;
        }

        public List<EmbeddingService.SearchResult> getSearchResults() {
            return searchResults;
        }

        public List<Citation> getCitations() {
            return citations;
        }
    }

    /**
     * 引用来源信息
     */
    public static class Citation {
        /** 命中块原文（展示用；与送 LLM 的上下文不同） */
        private final String content;
        /** 最终相关性分 */
        private final double score;
        /** 来源标签 doc_&lt;id&gt; */
        private final String source;
        // 引用溯源（纯增量）
        private final String chunkId;
        private final Long documentId;
        private final String documentTitle;
        private final String sectionPath;

        /** 保留旧构造签名，避免破坏既有调用方 */
        public Citation(String content, double score, String source) {
            this(content, score, source, null, null, null, null);
        }

        public Citation(String content, double score, String source,
                        String chunkId, Long documentId, String documentTitle, String sectionPath) {
            this.content = content;
            this.score = score;
            this.source = source;
            this.chunkId = chunkId;
            this.documentId = documentId;
            this.documentTitle = documentTitle;
            this.sectionPath = sectionPath;
        }

        public String getContent() { return content; }
        public double getScore() { return score; }
        public String getSource() { return source; }
        public String getChunkId() { return chunkId; }
        public Long getDocumentId() { return documentId; }
        public String getDocumentTitle() { return documentTitle; }
        public String getSectionPath() { return sectionPath; }
    }
}
