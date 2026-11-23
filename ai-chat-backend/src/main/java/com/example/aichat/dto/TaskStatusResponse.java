package com.example.aichat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 上传任务进度查询响应 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskStatusResponse {
    private String taskId;
    private String status;      // PENDING / PROCESSING / SUCCESS / FAILED
    private int progress;       // 0-100
    private String stage;       // 当前阶段
    private Long docId;         // 成功后文档 ID
    private String errorMessage;
    private int retryCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
