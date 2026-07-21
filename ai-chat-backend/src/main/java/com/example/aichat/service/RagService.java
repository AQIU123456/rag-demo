package com.example.aichat.service;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * RAG (Retrieval-Augmented Generation) 服务
 * 实现基于知识库的检索增强生成问答
 */
@Service
public class RagService {

    private final ChatLanguageModel chatLanguageModel;
    private final EmbeddingService embeddingService;

    public RagService(ChatLanguageModel chatLanguageModel, 
                     EmbeddingService embeddingService) {
        this.chatLanguageModel = chatLanguageModel;
        this.embeddingService = embeddingService;
    }

    /**
     * 基于知识库的 RAG 问答
     * @param question 用户问题
     * @param maxResults 最大检索结果数
     * @return RAG 问答结果
     */
    public RagResponse answerWithRag(String question, int maxResults) {
        // 1. 从知识库中检索相关文档片段
        List<EmbeddingService.SearchResult> searchResults = 
            embeddingService.searchSimilar(question, maxResults);
        
        // 2. 构建上下文内容
        String context = buildContext(searchResults);
        
        // 3. 构建系统提示词
        String systemPrompt = buildSystemPrompt(context);
        
        // 4. 调用 LLM 生成答案
        String answer = generateAnswer(systemPrompt, question);
        
        // 5. 提取引用来源
        List<Citation> citations = extractCitations(searchResults);
        
        return new RagResponse(answer, context, searchResults, citations);
    }

    /**
     * 构建上下文内容
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
            contextBuilder.append(result.getContent());
            contextBuilder.append(String.format(" (相似度：%.2f)\n\n", result.getScore()));
        }
        
        return contextBuilder.toString();
    }

    /**
     * 构建系统提示词
     */
    private String buildSystemPrompt(String context) {
        return "你是一个专业的企业知识库助手。请根据以下提供的知识库内容来回答用户的问题。\n" +
               "要求：\n" +
               "1. 只基于提供的知识库内容进行回答，不要编造信息\n" +
               "2. 如果知识库中没有相关信息，请明确告知用户\n" +
               "3. 回答要准确、简洁、专业\n" +
               "4. 在回答中引用相关内容的编号，例如 [1]、[2]\n" +
               "5. 如果有多条相关信息，请综合整理后回答\n" +
               "\n" +
               context +
               "\n" +
               "请根据以上知识库内容回答用户的问题。";
    }

    /**
     * 调用 LLM 生成答案
     */
    private String generateAnswer(String systemPrompt, String question) {
        try {
            Response<AiMessage> response = chatLanguageModel.generate(
                SystemMessage.from(systemPrompt),
                UserMessage.from(question)
            );
            return response.content().text();
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
                result.getContent(),
                result.getScore(),
                result.getSource()
            ))
            .collect(Collectors.toList());
    }

    /**
     * RAG 问答响应
     */
    public static class RagResponse {
        private final String answer;
        private final String context;
        private final List<EmbeddingService.SearchResult> searchResults;
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
        private final String content;
        private final double score;
        private final String source;

        public Citation(String content, double score, String source) {
            this.content = content;
            this.score = score;
            this.source = source;
        }

        public String getContent() {
            return content;
        }

        public double getScore() {
            return score;
        }

        public String getSource() {
            return source;
        }
    }
}
