package com.example.aichat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 分块策略信息 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChunkStrategyDTO {

    /**
     * 策略编码
     */
    private String code;

    /**
     * 策略描述（下拉框简称）
     */
    private String description;

    /**
     * 策略详细说明（选中后展示，帮助用户理解各策略差异）
     */
    private String detail;
}
