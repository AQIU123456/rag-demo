package com.example.aichat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 文档分块 DTO
 * 用于前后端传输分块数据
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentChunkDTO {
    
    private Long id;
    
    private Long documentId;
    
    private String documentTitle;
    
    private String content;
    
    private Integer chunkIndex;
    
    private String metadata;
    
    private LocalDateTime createdAt;
}
