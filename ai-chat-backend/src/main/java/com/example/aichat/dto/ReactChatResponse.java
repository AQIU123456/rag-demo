package com.example.aichat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * ReAct 智能问答响应 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReactChatResponse {

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
     * 原始问题
     */
    private String originalQuestion;

    /**
     * 推理步骤列表（showReasoning 为 false 时为 null）
     */
    private List<ReasoningStepDTO> reasoningSteps;

    /**
     * 格式化的推理过程文本
     */
    private String formattedReasoning;

    /**
     * ReAct 推理步骤 DTO
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReasoningStepDTO {
        /**
         * 步骤类型：THOUGHT / ACTION / OBSERVATION / FINAL_ANSWER
         */
        private String type;

        /**
         * 步骤内容
         */
        private String content;

        /**
         * 行动名称（ACTION 类型）
         */
        private String actionName;

        /**
         * 行动参数（ACTION 类型）
         */
        private Object arguments;

        /**
         * 行动结果（OBSERVATION 类型）
         */
        private String result;
    }
}
