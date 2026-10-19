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
#    优先级：EVAL_* 环境变量 > 后端 application.yml 的 langchain4j 配置 > 默认值
#    即：默认直接复用项目自己的 chat-model 当 judge。
# ---------------------------------------------------------------------------
DEFAULT_YML = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                           "..", "ai-chat-backend", "src", "main",
                           "resources", "application.yml")


def _resolve_env_refs(value: str) -> str:
    """把 ${VAR} / ${VAR:default} 占位符替换为当前环境变量。"""
    import re

    def repl(m):
        expr = m.group(1)
        if ":" in expr:
            name, default = expr.split(":", 1)
        else:
            name, default = expr, ""
        return os.environ.get(name.strip(), default).strip()

    return re.sub(r"\$\{([^}]+)\}", repl, value) if value else value


def load_backend_config(yml_path: str) -> dict:
    """读取后端 application.yml 中 langchain4j 的 chat/embedding 模型配置。"""
    cfg = {}
    try:
        import yaml
        with open(yml_path, "r", encoding="utf-8") as f:
            doc = yaml.safe_load(f)
        oa = (doc.get("langchain4j", {}) or {}).get("open-ai", {}) or {}
        chat = oa.get("chat-model", {}) or {}
        emb = oa.get("embedding-model", {}) or {}
        cfg = {
            "chat": {"api_key": _resolve_env_refs(chat.get("api-key", "")),
                     "base_url": _resolve_env_refs(chat.get("base-url", "")),
                     "model": chat.get("model-name", "")},
            "emb": {"api_key": _resolve_env_refs(emb.get("api-key", "")),
                    "base_url": _resolve_env_refs(emb.get("base-url", "")),
                    "model": emb.get("model-name", "")},
        }
    except FileNotFoundError:
        pass
    except Exception as e:
        console.print(f"[yellow]解析后端配置失败({yml_path}): {e}[/yellow]")
    return cfg


def build_llms(backend_yml: str):
    from langchain_openai import ChatOpenAI, OpenAIEmbeddings
    from ragas.llms import LangchainLLMWrapper
    from ragas.embeddings import LangchainEmbeddingsWrapper

    be = load_backend_config(backend_yml)
    be_chat, be_emb = be.get("chat", {}), be.get("emb", {})

    # judge LLM：EVAL_* 优先，否则复用后端 chat-model
    api_key = os.environ.get("EVAL_API_KEY") or be_chat.get("api_key") \
        or os.environ.get("OPENAI_API_KEY")
    base_url = os.environ.get("EVAL_API_BASE") or be_chat.get("base_url") \
        or "https://api.openai.com/v1"
    llm_model = os.environ.get("EVAL_LLM_MODEL") or be_chat.get("model") or "gpt-4o-mini"

    # embedding：EMBED_*/EVAL_* 优先，否则复用后端 embedding-model
    emb_api_key = os.environ.get("EMBED_API_KEY") or os.environ.get("EVAL_EMBED_API_KEY") \
        or be_emb.get("api_key") or api_key
    emb_base_url = os.environ.get("EMBED_API_BASE") or be_emb.get("base_url") or base_url
    emb_model = os.environ.get("EVAL_EMBEDDING_MODEL") or be_emb.get("model") \
        or "text-embedding-3-small"

    if not api_key:
        console.print("[red]错误: 未找到 judge 模型的 API Key。"
                      "请设置 EVAL_API_KEY，或确保后端 application.yml 的 "
                      "chat-model api-key 对应的环境变量（如 OPENAI_API_KEY）已导出。[/red]")
        sys.exit(1)

    evaluator_llm = LangchainLLMWrapper(ChatOpenAI(
        model=llm_model, api_key=api_key, base_url=base_url,
        temperature=0, max_retries=2,
    ))
    evaluator_emb = LangchainEmbeddingsWrapper(OpenAIEmbeddings(
        model=emb_model, api_key=emb_api_key, base_url=emb_base_url, max_retries=2,
    ))
    src = "后端 chat-model" if (be_chat.get("model") == llm_model and
                               not os.environ.get("EVAL_LLM_MODEL")) else "独立配置"
    console.print(f"[green]judge LLM=[/green]{llm_model} ({src})  "
                  f"[green]base=[/green]{base_url}")
    console.print(f"[green]embedding=[/green]{emb_model}  [green]base=[/green]{emb_base_url}")
    return evaluator_llm, evaluator_emb


# ---------------------------------------------------------------------------
# 3. 运行 RAGAS 评估并输出结果
# ---------------------------------------------------------------------------
def run_evaluation(samples: List[dict], metric_names: List[str], backend_yml: str):
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

    evaluator_llm, evaluator_emb = build_llms(backend_yml)

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
    parser.add_argument("--backend-yml", default=DEFAULT_YML,
                        help="后端 application.yml 路径，用于复用 chat-model/embedding-model "
                             "作为 judge（默认 %(default)s）")
    args = parser.parse_args()

    samples = collect_samples(args.backend, args.questions, args.limit,
                              args.max_results, args.timeout)
    if not samples:
        console.print("[red]未采集到任何样本。请确认后端已启动、接口可访问。[/red]")
        sys.exit(1)
    console.print(f"[green]共采集 {len(samples)} 条样本，开始 RAGAS 评估...[/green]")

    metric_names = [m.strip() for m in args.metrics.split(",") if m.strip()]
    result, used = run_evaluation(samples, metric_names, args.backend_yml)
    print_report(result, used, args.output)


if __name__ == "__main__":
    main()
