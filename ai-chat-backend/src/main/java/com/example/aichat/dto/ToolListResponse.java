package com.example.aichat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 可用工具列表响应 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolListResponse {

    /**
     * 是否成功
     */
    private boolean success;

    /**
     * 工具列表
     */
    private List<ToolInfoDTO> tools;

    /**
     * 工具信息 DTO
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToolInfoDTO {
        /**
         * 工具名称
         */
        private String name;

        /**
         * 工具标题
         */
        private String title;

        /**
         * 工具描述
         */
        private String description;

        /**
         * 参数说明
         */
        private String parameters;
    }
}
