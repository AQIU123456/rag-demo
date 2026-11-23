package com.example.aichat.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 异步上传任务实体（状态机 + 进度 + 重试） */
@Entity
@Table(name = "upload_task")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UploadTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false, unique = true, length = 64)
    private String taskId;

    @Column(name = "file_hash", length = 64)
    private String fileHash;

    @Column(length = 512)
    private String filename;

    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(nullable = false)
    private Integer progress;

    @Column(length = 64)
    private String stage;

    @Column(name = "doc_id")
    private Long docId;

    @Column(name = "storage_path", length = 1024)
    private String storagePath;

    @Column(name = "error_message", length = 1024)
    private String errorMessage;

    @Column(name = "retry_count", nullable = false)
    private Integer retryCount;

    @Column(name = "uploaded_by", nullable = false, length = 128)
    private String uploadedBy;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (updatedAt == null) updatedAt = LocalDateTime.now();
        if (status == null) status = "PENDING";
        if (progress == null) progress = 0;
        if (retryCount == null) retryCount = 0;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
