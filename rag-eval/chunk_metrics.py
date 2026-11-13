#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
分块专属质量指标（配合 evaluate_rag.py 的 RAGAS 四指标一起做 A/B）

背景：RAGAS 只评估端到端答案，无法区分「检索没召回」和「生成没用好」；
本脚本直接分析 /api/chat/rag 返回的 citations，度量分块与索引本身的质量。

指标：
    chunk_recall@k        标准答案的字符 bigram 被 top-k 检索片段覆盖的比例
    context_completeness  命中片段以完整句/表格结尾的比例（非半句截断）
    duplicate_rate        top-k 中重复块（chunkId 或 content）占比
    section_coverage      带 sectionPath 的引用占比（结构感知是否生效）
    traceability          带 chunkId + documentId 的引用占比（引用可定位）
    ghost_rate            命中「已删除文档特征串」的引用占比（幽灵向量）
    truncation_rate       分块表中被标记超长的块占比（需 --chunks-base）
    avg/max_tokens        块长分布（需 --chunks-base）

用法：
    python chunk_metrics.py --questions sample_questions.json \
        --backend http://localhost:8080/api/chat/rag \
        --deleted-markers deleted_markers.txt \
        --chunks-base http://localhost:8080/api/knowledge/chunks \
        --doc-ids 1,2,3 \
        -o chunk_metrics.csv

    deleted_markers.txt：每行一个「已删除文档中的独特句子」，用于检测幽灵向量。
    仅使用 Python 标准库 + requests（requirements.txt 已含），不需要 RAGAS / judge 模型。
"""

import argparse
import csv
import json
import os
import re
import sys
from typing import Dict, List

import requests

SENTENCE_END = tuple("。！？!?；;）)】」』.〕")
CJK = re.compile(r"[\u4e00-\u9fff]")


def bigrams(text: str) -> set:
    """中文按相邻双字、英文按词，混合提取；用于粗粒度覆盖度计算。"""
    s = (text or "").lower()
    s = re.sub(r"\s+", "", s)
    grams = set()
    for i in range(len(s) - 1):
        grams.add(s[i:i + 2])
    for word in re.findall(r"[a-z0-9]{2,}", (text or "").lower()):
        grams.add(word)
    return grams


def recall_at_k(reference: str, contexts: List[str]) -> float:
    ref = bigrams(reference)
    if not ref:
        return 0.0
    hit = set()
    for ctx in contexts:
        hit |= bigrams(ctx)
    return len(ref & hit) / len(ref)


def completeness(contexts: List[str]) -> float:
    """命中片段是否以句末标点或表格行结尾，反映是否把条款切成半句。"""
    if not contexts:
        return 0.0
    complete = 0
    for ctx in contexts:
        t = (ctx or "").rstrip()
        if not t:
            continue
        if t.endswith(SENTENCE_END) or t.endswith("|") or t.endswith("\n"):
            complete += 1
    return complete / len(contexts)


def duplicate_rate(citations: List[dict]) -> float:
    if not citations:
        return 0.0
    keys = [c.get("chunkId") or c.get("content", "") for c in citations]
    return 1.0 - len(set(keys)) / len(keys)


def chunk_stats(base_url: str, doc_ids: List[int], max_tokens: int,
                timeout: int) -> Dict[str, float]:
    """从 /api/knowledge/chunks 拉分块 DTO，统计截断率与块长分布。"""
    sizes, truncated, hashes = [], 0, []
    for doc_id in doc_ids:
        try:
            resp = requests.get(base_url, params={"documentId": doc_id}, timeout=timeout)
            resp.raise_for_status()
            rows = resp.json()
        except Exception as e:
            print(f"[warn] 拉取分块失败 documentId={doc_id}: {e}", file=sys.stderr)
            continue
        for row in rows:
            n = row.get("tokenCount")
            if isinstance(n, (int, float)):
                sizes.append(n)
                if n > max_tokens * 1.2:
                    truncated += 1
            if row.get("contentHash"):
                hashes.append(row["contentHash"])
    result = {
        "chunks_observed": len(sizes),
        "truncation_rate": (truncated / len(sizes)) if sizes else 0.0,
        "avg_tokens": (sum(sizes) / len(sizes)) if sizes else 0.0,
        "max_tokens": max(sizes) if sizes else 0.0,
        "duplicate_chunk_rate": (1.0 - len(set(hashes)) / len(hashes)) if hashes else 0.0,
    }
    return result


def main():
    p = argparse.ArgumentParser(description="分块专属质量指标评估")
    p.add_argument("--backend", default="http://localhost:8080/api/chat/rag")
    p.add_argument("--questions", default="sample_questions.json")
    p.add_argument("--max-results", type=int, default=5)
    p.add_argument("--timeout", type=int, default=60)
    p.add_argument("--limit", type=int, default=0)
    p.add_argument("--deleted-markers", default="",
                    help="已删除文档特征串文件，每行一句，用于幽灵向量检测")
    p.add_argument("--chunks-base", default="",
                    help="分块查询接口基址，如 http://localhost:8080/api/knowledge/chunks")
    p.add_argument("--doc-ids", default="", help="逗号分隔的文档 ID，配合 --chunks-base")
    p.add_argument("--max-tokens", type=int, default=300,
                   help="与 application.yml 的 chunk.max-tokens 保持一致")
    p.add_argument("-o", "--output", default="chunk_metrics.csv")
    args = p.parse_args()

    with open(args.questions, "r", encoding="utf-8") as f:
        items = json.load(f)
    if args.limit > 0:
        items = items[:args.limit]
    if len(items) < 30:
        print(f"[warn] 当前仅 {len(items)} 条问题，A/B 建议扩充到 >=30 条覆盖多章节/表格/精确数字",
              file=sys.stderr)

    markers = []
    if args.deleted_markers and os.path.exists(args.deleted_markers):
        with open(args.deleted_markers, "r", encoding="utf-8") as f:
            markers = [line.strip() for line in f if line.strip()]

    rows = []
    for i, item in enumerate(items):
        question = item["question"]
        try:
            r = requests.post(args.backend,
                              json={"question": question, "maxResults": args.max_results},
                              timeout=args.timeout)
            r.raise_for_status()
            data = r.json()
        except Exception as e:
            print(f"[{i + 1}/{len(items)}] 请求失败: {e}", file=sys.stderr)
            continue
        citations = data.get("citations") or []
        contexts = [c.get("content", "") for c in citations if c.get("content")]
        reference = item.get("reference", "")
        ghosts = sum(1 for c in contexts for m in markers if m and m in c)
        row = {
            "question": question,
            "k": len(contexts),
            "chunk_recall@k": round(recall_at_k(reference, contexts), 4) if reference else "",
            "context_completeness": round(completeness(contexts), 4),
            "duplicate_rate": round(duplicate_rate(citations), 4),
            "section_coverage": round(
                sum(1 for c in citations if c.get("sectionPath")) / len(citations), 4)
                if citations else 0.0,
            "traceability": round(
                sum(1 for c in citations if c.get("chunkId") and c.get("documentId"))
                / len(citations), 4) if citations else 0.0,
            "ghost_hits": ghosts,
            "ghost_rate": round(ghosts / len(contexts), 4) if contexts else 0.0,
        }
        rows.append(row)
        print(f"[{i + 1}/{len(items)}] {question[:30]} ... recall={row['chunk_recall@k']} "
              f"dup={row['duplicate_rate']} ghost={row['ghost_rate']}")

    if not rows:
        print("[error] 未采集到任何样本，检查后端是否已启动", file=sys.stderr)
        sys.exit(1)

    def avg(key):
        vals = [r[key] for r in rows if isinstance(r[key], (int, float))]
        return round(sum(vals) / len(vals), 4) if vals else 0.0

    summary = {f"avg_{k}": avg(k) for k in
               ("chunk_recall@k", "context_completeness", "duplicate_rate",
                "section_coverage", "traceability", "ghost_rate")}
    if args.chunks_base and args.doc_ids:
        ids = [int(x) for x in args.doc_ids.split(",") if x.strip()]
        summary["chunk_table"] = chunk_stats(args.chunks_base, ids,
                                             args.max_tokens, args.timeout)

    fieldnames = list(rows[0].keys())
    with open(args.output, "w", encoding="utf-8-sig", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)
    with open(os.path.splitext(args.output)[0] + ".summary.json", "w",
              encoding="utf-8") as f:
        json.dump(summary, f, ensure_ascii=False, indent=2)

    print("\n=== 分块质量汇总 ===")
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    print(f"\n明细: {args.output}")
    print("判定建议: context_recall 不低于 baseline、重复块率/幽灵率为 0 才允许合入；"
          "否则把 application.yml 的 chunk.* 开关回退。")


if __name__ == "__main__":
    main()
