package com.example.aichat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * RAG 智能问答响应 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagChatResponse {

    /**
     * 是否成功
     */
    private boolean success;

    /**
     * 提示信息（失败时返回错误原因）
     */
    private String message;

    /**
     * AI 回答
     */
    private String answer;

    /**
     * 检索到的上下文
     */
    private String context;

    /**
     * 引用来源列表
     */
    private List<CitationDTO> citations;

    /**
     * 引用来源 DTO
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CitationDTO {
        /**
         * 引用的分块内容
         */
        private String content;

        /**
         * 相似度得分
         */
        private double score;

        /**
         * 来源文档标题
         */
        private String source;

        // ===== 引用溯源扩展（纯增量字段，旧前端忽略即可） =====

        /** 命中的分块业务键 doc_&lt;id&gt;_&lt;index&gt;_&lt;hash8&gt; */
        private String chunkId;

        /** 所属文档 ID */
        private Long documentId;

        /** 所属文档标题 */
        private String documentTitle;

        /** 标题 / 条款路径 */
        private String sectionPath;
    }
}
