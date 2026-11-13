-- =============================================================================
-- DDL：把 document_chunks 扩展为可追溯、可增量、可过滤的索引单元
-- 适用：MySQL 8.x / qifu-test 同构实例
-- 约定：本文件只含 DDL，配套初始化数据见 20261113_chunk_pipeline_init.sql
-- 执行顺序：先本文件，再 _init.sql；回滚见 20261113_chunk_pipeline_rollback.sql
-- =============================================================================

-- 历史 metadata 存的是 LangChain4j Metadata.toString()（形如 Metadata{...}），
-- 不是合法 JSON；先清洗再改列类型，避免新链路解析失败。
UPDATE document_chunks SET metadata = NULL WHERE metadata IS NOT NULL;
ALTER TABLE document_chunks MODIFY COLUMN metadata TEXT NULL COMMENT '结构化 JSON 元数据';

ALTER TABLE document_chunks
  ADD COLUMN vector_id        VARCHAR(64)  NULL COMMENT '向量库条目 id',
  ADD COLUMN parent_id        VARCHAR(64)  NULL COMMENT '父块 chunk_key',
  ADD COLUMN section_path     VARCHAR(512) NULL COMMENT '标题/条款路径',
  ADD COLUMN content_hash     CHAR(64)     NULL COMMENT 'sha256(送嵌文本)',
  ADD COLUMN chunk_version    VARCHAR(32)  NOT NULL DEFAULT 'v1' COMMENT '索引版本',
  ADD COLUMN token_count      INT          NOT NULL DEFAULT 0 COMMENT '送嵌文本估算 token 数',
  ADD COLUMN active           TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '逻辑有效标记',
  ADD COLUMN tenant_id        VARCHAR(64)  NULL COMMENT '预留：租户隔离',
  ADD COLUMN acl_tag          VARCHAR(128) NULL COMMENT '预留：ACL 标签',
  ADD COLUMN embedding_model  VARCHAR(128) NULL COMMENT '生成该块向量的嵌入模型',
  ADD INDEX idx_chunk_doc_active (document_id, active, chunk_version),
  ADD INDEX idx_chunk_hash (document_id, content_hash);
