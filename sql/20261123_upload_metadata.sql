-- =============================================================================
-- DDL：knowledge_documents 扩展企业级上传元数据列
-- 适用：MySQL 8.x
-- 约定：本文件只含 DDL，配套初始化数据见 20261123_upload_metadata_init.sql
-- 执行顺序：先本文件，再 _init.sql；回滚见 20261123_upload_metadata_rollback.sql
-- 说明：所有新增列均可空且有默认值，存量数据自动兼容，不影响原快速录入链路
-- =============================================================================

ALTER TABLE knowledge_documents
  ADD COLUMN original_filename VARCHAR(512) NULL COMMENT '原始文件名' AFTER word_count,
  ADD COLUMN mime_type         VARCHAR(128) NULL COMMENT '真实 MIME（魔数嗅探）' AFTER original_filename,
  ADD COLUMN file_hash         CHAR(64)     NULL COMMENT '文件 sha256（秒传去重）' AFTER mime_type,
  ADD COLUMN storage_path      VARCHAR(1024) NULL COMMENT '原文件相对存储路径' AFTER file_hash,
  ADD COLUMN version           VARCHAR(32)  NOT NULL DEFAULT 'v1' COMMENT '文档版本' AFTER storage_path,
  ADD COLUMN source            VARCHAR(32)  NOT NULL DEFAULT 'quick-entry' COMMENT '来源：file-upload/quick-entry/api' AFTER version,
  ADD COLUMN uploaded_by       VARCHAR(128) NOT NULL DEFAULT 'anonymous' COMMENT '上传者' AFTER source,
  ADD COLUMN tenant_id         VARCHAR(64)  NOT NULL DEFAULT 'default' COMMENT '租户 ID' AFTER uploaded_by,
  ADD COLUMN parse_status      VARCHAR(32)  NOT NULL DEFAULT 'TEXT_ONLY' COMMENT '解析状态：SUCCESS/FAILED/TEXT_ONLY' AFTER tenant_id,
  ADD INDEX idx_doc_file_hash (file_hash),
  ADD INDEX idx_doc_tenant (tenant_id),
  ADD INDEX idx_doc_original_name (original_filename(191));

-- 上传任务表：异步上传状态机 + 进度 + 重试
CREATE TABLE IF NOT EXISTS upload_task (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  task_id       VARCHAR(64)  NOT NULL COMMENT '对外暴露的任务 ID（UUID）',
  file_hash     CHAR(64)     NULL COMMENT '文件 sha256',
  filename      VARCHAR(512) NULL COMMENT '原始文件名',
  file_size     BIGINT       NOT NULL DEFAULT 0,
  status        VARCHAR(32)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PROCESSING/SUCCESS/FAILED',
  progress      INT          NOT NULL DEFAULT 0 COMMENT '0-100',
  stage         VARCHAR(64)  NULL COMMENT '当前阶段描述',
  doc_id        BIGINT       NULL COMMENT '成功后关联的文档 ID',
  storage_path  VARCHAR(1024) NULL COMMENT '原文件相对存储路径（重试定位用）',
  error_message VARCHAR(1024) NULL COMMENT '失败原因',
  retry_count   INT          NOT NULL DEFAULT 0,
  uploaded_by   VARCHAR(128) NOT NULL DEFAULT 'anonymous',
  tenant_id     VARCHAR(64)  NOT NULL DEFAULT 'default',
  created_at    DATETIME     NOT NULL,
  updated_at    DATETIME     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_task_id (task_id),
  KEY idx_task_status (status),
  KEY idx_task_hash (file_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文件上传异步任务';
