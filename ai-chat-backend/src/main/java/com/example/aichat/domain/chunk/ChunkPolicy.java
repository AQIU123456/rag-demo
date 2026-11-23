package com.example.aichat.domain.chunk;

import com.example.aichat.enums.ChunkStrategy;
import lombok.Builder;
import lombok.Data;

/**
 * 单次切分的策略覆盖。字段为 null 时回落到策略自带配置或 {@code ChunkProperties} 全局配置。
 *
 * <p>{@link #of(ChunkStrategy)} 把前端选择的具体策略展开为本次切分的完整配置：
 * 既指定 {@link #strategy}（决定底层用哪个切分器），也把策略的增强开关写入各字段，
 * 保证「用户选了什么策略」与「实际切分行为」严格一致，不受 application.yml 全局开关影响。</p>
 */
@Data
@Builder
public class ChunkPolicy {

    /**
     * 本次使用的切分策略。非空时由它决定底层 DocumentSplitter；
     * 为空（上传/编辑/启动重建等无显式选择的路径）时沿用全局配置。
     */
    private ChunkStrategy strategy;

    /** 子块最大 token 数，null 表示用全局 chunk.max-tokens */
    private Integer maxTokens;
    /** 相邻子块重叠 token 数，null 表示用全局 chunk.overlap-tokens */
    private Integer overlapTokens;
    /** 是否启用标题/条款结构感知，null 表示用策略自带配置或全局 chunk.structure-aware */
    private Boolean structureAware;
    /** 是否给送嵌文本加 [标题 > 章节] 前缀，null 表示用策略自带配置或全局 chunk.context-prefix */
    private Boolean contextPrefix;
    /** 是否启用父子索引，null 表示用策略自带配置或全局 chunk.parent-child */
    private Boolean parentChild;

    /** 全字段为空：完全按全局配置切分 */
    public static ChunkPolicy defaults() {
        return ChunkPolicy.builder().build();
    }

    /**
     * 把前端策略展开为本次切分配置。
     *
     * <p>三个增强开关一律按策略自带值写入（而非 null 回落），因此每个策略产生的
     * 结果是确定的：RECURSIVE 只做递归切分；STRUCTURE_AWARE 只解析章节；
     * SEMANTIC 再加上下文前缀；PARENT_CHILD 再加父子索引。</p>
     *
     * @param strategy 前端选择的策略，null 时按全局配置
     */
    public static ChunkPolicy of(ChunkStrategy strategy) {
        if (strategy == null) {
            return defaults();
        }
        return ChunkPolicy.builder()
                .strategy(strategy)
                .structureAware(strategy.isStructureAware())
                .contextPrefix(strategy.isContextPrefix())
                .parentChild(strategy.isParentChild())
                .build();
    }
}
