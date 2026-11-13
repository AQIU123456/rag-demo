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

    private Long documentId;
    private String documentTitle;
    private int index;

    /** 稳定业务键：doc_&lt;id&gt;_&lt;index&gt;_&lt;hash8&gt;，不依赖数据库自增主键 */
    private String chunkKey;
    private String sectionPath;

    private String content;
    private String embeddedText;
    private String contentHash;

    /** 父块的 chunkKey（父子索引模式使用） */
    private String parentKey;
    private String prevKey;
    private String nextKey;

    private int tokenCount;
    private boolean truncated;
}
