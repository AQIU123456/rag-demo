package com.example.aichat.domain.chunk;

import lombok.Builder;
import lombok.Data;

/**
 * 分块流水线中间模型。
 *
 * <p>关键约束：{@code content} 是原文（落 document_chunks.content，供展示与编辑），
 * {@code embeddedText} 是带上下文前缀的送嵌文本（只进向量库，不落正文列）。
 * 两者必须分开，否则启动重建时无法还原送嵌文本，hash 与向量会错位。</p>
 */
@Data
@Builder
public class Chunk {

    /** 所属文档 ID */
    private Long documentId;
    /** 文档标题（已消毒），用于上下文前缀与引用展示 */
    private String documentTitle;
    /** 在文档内的顺序，从 0 开始，对应 document_chunks.chunk_index */
    private int index;

    /** 稳定业务键：doc_&lt;id&gt;_&lt;index&gt;_&lt;hash8&gt;，不依赖数据库自增主键 */
    private String chunkKey;
    /** 标题 / 条款路径，如 "第二章 报销 > 2.1 差旅" */
    private String sectionPath;

    /** 原文：落 document_chunks.content，供前端展示与手工编辑 */
    private String content;
    /** 送嵌文本：原文 + 上下文前缀，只进向量库，不落正文列 */
    private String embeddedText;
    /** sha256(embeddedText)，幂等判定与重复块检测的依据 */
    private String contentHash;

    /** 父块的 chunkKey（父子索引模式使用） */
    private String parentKey;
    /** 前一个块的 chunkKey，用于相邻窗口扩展 */
    private String prevKey;
    /** 后一个块的 chunkKey，用于相邻窗口扩展 */
    private String nextKey;

    /** 送嵌文本的估算 token 数（HeuristicTokenizer 口径） */
    private int tokenCount;
    /** 是否超出 maxTokens 一定比例，供块质量观测 */
    private boolean truncated;
}
