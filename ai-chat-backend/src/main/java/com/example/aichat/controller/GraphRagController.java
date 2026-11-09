package com.example.aichat.controller;

import com.example.aichat.dto.GraphBuildRequest;
import com.example.aichat.dto.GraphBuildResponse;
import com.example.aichat.dto.GraphRagChatRequest;
import com.example.aichat.dto.GraphRagChatResponse;
import com.example.aichat.dto.GraphStatsResponse;
import com.example.aichat.dto.RagChatResponse;
import com.example.aichat.service.GraphRagService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 图 RAG（GraphRAG）接口
 * 提供图谱构建、统计查询与图增强问答三个端点
 */
@RestController
@RequestMapping("/api/graph")
@CrossOrigin(origins = "*")
public class GraphRagController {

    private final GraphRagService graphRagService;

    public GraphRagController(GraphRagService graphRagService) {
        this.graphRagService = graphRagService;
    }

    /**
     * 从知识库文档构建知识图谱（LLM 抽取实体与关系三元组）
     * POST /api/graph/build
     */
    @PostMapping("/build")
    public ResponseEntity<GraphBuildResponse> buildGraph(@RequestBody(required = false) GraphBuildRequest request) {
        Long documentId = request != null ? request.getDocumentId() : null;
        boolean clearExisting = request != null && Boolean.TRUE.equals(request.getClearExisting());

        try {
            GraphRagService.BuildResult result =
                    graphRagService.buildFromKnowledgeBase(documentId, clearExisting);

            return ResponseEntity.ok(GraphBuildResponse.builder()
                    .success(true)
                    .processedDocuments(result.getProcessedDocuments())
                    .entityCount(result.getEntityCount())
                    .relationCount(result.getRelationCount())
                    .elapsedMs(result.getElapsedMs())
                    .build());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(GraphBuildResponse.builder()
                    .success(false)
                    .message("图谱构建失败：" + e.getMessage())
                    .build());
        }
    }

    /**
     * 查询知识图谱规模统计
     * GET /api/graph/stats
     */
    @GetMapping("/stats")
    public ResponseEntity<GraphStatsResponse> getStats() {
        try {
            GraphRagService.GraphStats stats = graphRagService.getStats();
            return ResponseEntity.ok(GraphStatsResponse.builder()
                    .success(true)
                    .entityCount(stats.getEntityCount())
                    .relationCount(stats.getRelationCount())
                    .averageDegree(stats.getAverageDegree())
                    .build());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(GraphStatsResponse.builder()
                    .success(false)
                    .message("查询图谱统计失败：" + e.getMessage())
                    .build());
        }
    }

    /**
     * 图 RAG 智能问答：向量检索 + 知识图谱一跳扩展融合生成
     * POST /api/graph/chat
     */
    @PostMapping("/chat")
    public ResponseEntity<GraphRagChatResponse> graphRagChat(@RequestBody GraphRagChatRequest request) {
        String question = request.getQuestion();
        int maxResults = request.getMaxResults() != null ? request.getMaxResults() : 5;
        boolean showGraphContext = request.getShowGraphContext() == null || request.getShowGraphContext();

        if (question == null || question.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(GraphRagChatResponse.builder()
                    .success(false)
                    .message("问题不能为空")
                    .build());
        }

        try {
            GraphRagService.GraphRagAnswer answer =
                    graphRagService.answerWithGraph(question.trim(), maxResults);

            GraphRagChatResponse.GraphRagChatResponseBuilder builder = GraphRagChatResponse.builder()
                    .success(true)
                    .answer(answer.getAnswer())
                    .context(answer.getVectorContext())
                    .citations(answer.getCitations())
                    .graphContext(answer.getGraphContext())
                    .seedEntities(answer.getSeedEntities());

            // 子图数据（供前端可视化）
            if (showGraphContext) {
                GraphRagService.GraphRetrievalResult gr = answer.getGraphResult();
                List<GraphRagChatResponse.GraphNodeDTO> nodes = gr.getNodes().stream()
                        .map(n -> GraphRagChatResponse.GraphNodeDTO.builder()
                                .id(n.getId())
                                .name(n.getName())
                                .type(n.getType())
                                .seed(n.isSeed())
                                .build())
                        .collect(Collectors.toList());
                List<GraphRagChatResponse.GraphEdgeDTO> edges = gr.getEdges().stream()
                        .map(e -> GraphRagChatResponse.GraphEdgeDTO.builder()
                                .sourceId(e.getSourceId())
                                .targetId(e.getTargetId())
                                .relation(e.getRelation())
                                .weight(e.getWeight())
                                .build())
                        .collect(Collectors.toList());
                builder.subgraph(GraphRagChatResponse.SubGraphDTO.builder()
                        .nodes(nodes)
                        .edges(edges)
                        .build());
            }

            return ResponseEntity.ok(builder.build());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(GraphRagChatResponse.builder()
                    .success(false)
                    .message("图 RAG 问答失败：" + e.getMessage())
                    .build());
        }
    }
}
