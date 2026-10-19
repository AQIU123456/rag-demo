#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
RAGAS 评估脚本 —— 针对 ai-chat-backend 的 RAG 接口 (POST /api/chat/rag)

用法示例：
    # 1. 先启动 Spring Boot 后端（默认 http://localhost:8080）
    # 2. 配置评估用的 LLM / Embedding（judge 模型，OpenAI 兼容接口均可）
    export EVAL_API_KEY="sk-xxx"
    export EVAL_API_BASE="https://api.deepseek.com/v1"   # 可选
    export EVAL_LLM_MODEL="deepseek-chat"               # 可选
    export EVAL_EMBEDDING_MODEL="bge-m3"                # 可选
    # 3. 运行评估
    python evaluate_rag.py --questions sample_questions.json
    python evaluate_rag.py --backend http://localhost:8080/api/chat/rag \
                           --metrics faithfulness,answer_relevancy \
                           --limit 10 -o results.csv

指标说明（RAGAS 经典四指标）：
    - faithfulness          回答是否忠实于检索到的上下文（防幻觉）
    - answer_relevancy      回答与问题的相关度
    - context_precision     检索上下文中相关内容排序质量（需要 reference）
    - context_recall        回答问题所需的知识点是否被上下文覆盖（需要 reference）
"""

import argparse
import json
import os
import sys
from typing import List, Dict

import requests
from rich.console import Console
from rich.table import Table

console = Console()


# ---------------------------------------------------------------------------
# 1. 调用项目后端 RAG 接口，收集 SingleTurnSample
# ---------------------------------------------------------------------------
def collect_samples(backend_url: str, questions_file: str, limit: int,
                    max_results: int, timeout: int) -> List[dict]:
    with open(questions_file, "r", encoding="utf-8") as f:
        items = json.load(f)
    if limit > 0:
        items = items[:limit]

    samples = []
    for i, item in enumerate(items):
        question = item["question"]
        console.print(f"[cyan][{i + 1}/{len(items)}][/cyan] 请求 RAG 接口: {question}")
        try:
            resp = requests.post(
                backend_url,
                json={"question": question, "maxResults": max_results},
                timeout=timeout,
            )
            resp.raise_for_status()
            data = resp.json()
        except Exception as e:
            console.print(f"[red]  请求失败，跳过该条: {e}[/red]")
            continue

        if not data.get("success"):
            console.print(f"[yellow]  接口返回 success=false，跳过: {data}[/yellow]")
            continue

        # citations[].content 即检索到的上下文片段 -> ragas 的 retrieved_contexts
        contexts = [c.get("content", "") for c in data.get("citations", []) if c.get("content")]
        samples.append({
            "user_input": question,
            "response": data.get("answer", ""),
            "retrieved_contexts": contexts,
            "reference": item.get("reference"),  # 有 ground truth 才能算 context 类指标
        })
    return samples


# ---------------------------------------------------------------------------
# 2. 构建 RAGAS 的 judge LLM / Embedding（OpenAI 兼容接口）
# ---------------------------------------------------------------------------
def build_llms():
    from langchain_openai import ChatOpenAI, OpenAIEmbeddings
    from ragas.llms import LangchainLLMWrapper
    from ragas.embeddings import LangchainEmbeddingsWrapper

    api_key = os.environ.get("EVAL_API_KEY") or os.environ.get("OPENAI_API_KEY")
    base_url = os.environ.get("EVAL_API_BASE") or os.environ.get("OPENAI_API_BASE") \
        or "https://api.openai.com/v1"
    llm_model = os.environ.get("EVAL_LLM_MODEL", "gpt-4o-mini")
    emb_model = os.environ.get("EVAL_EMBEDDING_MODEL", "text-embedding-3-small")

    if not api_key:
        console.print("[red]错误: 请设置 EVAL_API_KEY（或 OPENAI_API_KEY），"
                      "RAGAS 需要一个 LLM 作为评分裁判。[/red]")
        sys.exit(1)

    evaluator_llm = LangchainLLMWrapper(ChatOpenAI(
        model=llm_model, api_key=api_key, base_url=base_url,
        temperature=0, max_retries=2,
    ))
    evaluator_emb = LangchainEmbeddingsWrapper(OpenAIEmbeddings(
        model=emb_model, api_key=api_key, base_url=base_url, max_retries=2,
    ))
    console.print(f"[green]judge LLM=[/green]{llm_model}  "
                  f"[green]embedding=[/green]{emb_model}  [green]base=[/green]{base_url}")
    return evaluator_llm, evaluator_emb


# ---------------------------------------------------------------------------
# 3. 运行 RAGAS 评估并输出结果
# ---------------------------------------------------------------------------
def run_evaluation(samples: List[dict], metric_names: List[str]):
    from datasets import Dataset
    from ragas import evaluate
    from ragas.metrics import (
        faithfulness, answer_relevancy, context_precision, context_recall,
    )
    all_metrics = {
        "faithfulness": faithfulness,
        "answer_relevancy": answer_relevancy,
        "context_precision": context_precision,
        "context_recall": context_recall,
    }
    metrics = [all_metrics[m] for m in metric_names]

    # 没有 reference 时剔除依赖 ground truth 的指标
    has_ref = any(s.get("reference") for s in samples)
    if not has_ref:
        dropped = [m for m in metric_names if m in ("context_precision", "context_recall")]
        if dropped:
            console.print(f"[yellow]样本缺少 reference，自动跳过指标: {dropped}[/yellow]")
        metrics = [m for m in metrics if m.name not in ("context_precision", "context_recall")]
        metric_names = [m.name for m in metrics]

    evaluator_llm, evaluator_emb = build_llms()

    dataset = Dataset.from_list([{k: v for k, v in s.items() if v is not None}
                                 for s in samples])
    result = evaluate(dataset, metrics=metrics, llm=evaluator_llm,
                      embeddings=evaluator_emb)
    return result, metric_names


def print_report(result, metric_names: List[str], out_csv: str):
    df = result.to_pandas()

    table = Table(title="RAGAS 评估结果")
    table.add_column("指标", style="cyan")
    table.add_column("得分 (0~1)", style="green")
    table.add_column("说明")
    desc = {
        "faithfulness": "回答与检索上下文的一致性（防幻觉）",
        "answer_relevancy": "回答与问题的相关性",
        "context_precision": "检索内容中相关片段的排序精度",
        "context_recall": "标准答案被检索上下文覆盖的比例",
    }
    for m in metric_names:
        if m in df.columns:
            table.add_row(m, f"{df[m].mean():.4f}", desc.get(m, ""))
    console.print(table)

    detail = Table(title="逐条明细")
    for col in ["user_input", *metric_names]:
        if col in df.columns:
            detail.add_column(col, overflow="fold")
    for _, row in df.iterrows():
        cells = []
        for col in ["user_input", *metric_names]:
            if col in df.columns:
                v = row[col]
                cells.append(f"{v:.3f}" if isinstance(v, float) else str(v)[:60])
        detail.add_row(*cells)
    console.print(detail)

    if out_csv:
        df.to_csv(out_csv, index=False, encoding="utf-8-sig")
        console.print(f"[green]明细已保存: {out_csv}[/green]")


# ---------------------------------------------------------------------------
def main():
    parser = argparse.ArgumentParser(description="用 RAGAS 评估本项目 RAG 接口")
    parser.add_argument("--backend", default="http://localhost:8080/api/chat/rag",
                        help="RAG 接口地址 (默认 %(default)s)")
    parser.add_argument("--questions", default="sample_questions.json",
                        help="问题集 JSON: [{question, reference?}, ...]")
    parser.add_argument("--metrics", default="faithfulness,answer_relevancy,"
                                             "context_precision,context_recall",
                        help="逗号分隔的指标列表")
    parser.add_argument("--max-results", type=int, default=5, help="检索 topK")
    parser.add_argument("--limit", type=int, default=0, help="只评估前 N 个问题")
    parser.add_argument("--timeout", type=int, default=60, help="单请求超时秒数")
    parser.add_argument("-o", "--output", default="ragas_results.csv", help="结果 CSV")
    args = parser.parse_args()

    samples = collect_samples(args.backend, args.questions, args.limit,
                              args.max_results, args.timeout)
    if not samples:
        console.print("[red]未采集到任何样本。请确认后端已启动、接口可访问。[/red]")
        sys.exit(1)
    console.print(f"[green]共采集 {len(samples)} 条样本，开始 RAGAS 评估...[/green]")

    metric_names = [m.strip() for m in args.metrics.split(",") if m.strip()]
    result, used = run_evaluation(samples, metric_names)
    print_report(result, used, args.output)


if __name__ == "__main__":
    main()
