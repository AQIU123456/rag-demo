package com.example.aichat.domain.chunk;

import com.example.aichat.enums.ChunkStrategy;
import lombok.Builder;
import lombok.Data;

/**
 * 单次切分的策略覆盖。字段为 null 时回落到 {@code ChunkProperties} 全局配置。
 *
 * <p>用于把前端既有的三种 {@link ChunkStrategy} 下拉名映射到新流水线，
 * 保持 API 不变的同时让策略真正产生差异（原先只是换了三套字符数）。</p>
 */
@Data
@Builder
public class ChunkPolicy {

    private Integer maxTokens;
    private Integer overlapTokens;
    private Boolean structureAware;
    private Boolean contextPrefix;
    private Boolean parentChild;

    public static ChunkPolicy defaults() {
        return ChunkPolicy.builder().build();
    }

    /**
     * 兼容映射：
     * RECURSIVE     -> 全局配置（基线）
     * SEMANTIC      -> 结构感知 + 上下文前缀（替代原先中文失效的空格 Jaccard 伪语义）
     * PARENT_CHILD  -> 父子索引 + 结构感知 + 上下文前缀
     */
    public static ChunkPolicy of(ChunkStrategy strategy) {
        if (strategy == null) {
            return defaults();
        }
        switch (strategy) {
            case SEMANTIC:
                return ChunkPolicy.builder()
                        .structureAware(true)
                        .contextPrefix(true)
                        .build();
            case PARENT_CHILD:
                return ChunkPolicy.builder()
                        .structureAware(true)
                        .contextPrefix(true)
                        .parentChild(true)
                        .build();
            case RECURSIVE:
            default:
                return defaults();
        }
    }
}
