package com.example.aichat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 文件上传响应：同步返回文档，异步返回任务 ID */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UploadResponse {
    /** 异步模式：任务 ID；同步模式：null */
    private String taskId;
    /** 是否异步处理 */
    private boolean async;
    /** 是否命中秒传去重 */
    private boolean dedupHit;
    /** 同步模式：上传成功后的文档；异步模式：null */
    private DocumentResponse document;
    /** 提示信息 */
    private String message;
}
