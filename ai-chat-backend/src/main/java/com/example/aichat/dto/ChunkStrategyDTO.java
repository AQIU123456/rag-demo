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
     * 策略描述
     */
    private String description;
}
