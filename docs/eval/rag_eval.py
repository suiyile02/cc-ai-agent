#!/usr/bin/env python3
"""RAG 检索链路离线评测（P3-6 定标集的可重复脚本）。

用途：改切块策略 / 嵌入模型 / 阈值 / 重排模式后，用同一套问题与同一套指标复跑，
用数据判断改动是提升还是回退——替代"改完凭感觉看几个问题"的循环。

链路与生产逐字对齐（见 src/main/java/com/ai/rag/service/）：
  语义路(Qdrant, flash 嵌入) + BM25 路(KeywordIndex 同款分词) → RRF(k=60)
  → 重排(score: ScoreFusionReranker / api: DashScopeReranker) → 证据分层取数
  → 阈值过滤 → Top-K

用法：
  export DASHSCOPE_API_KEY=sk-xxx          # 必需；密钥只从环境变量读，禁止写进任何文件
  python docs/eval/rag_eval.py                      # 默认 score 模式
  python docs/eval/rag_eval.py --rerank api         # 对比 api 重排
  python docs/eval/rag_eval.py --rerank score --rerank api --out /tmp/report.md

前置：Qdrant(6333) 可访问、目标集合已入库、MySQL 可读（取禁用文档集；不可读时按无禁用处理）。
"""
from __future__ import annotations

import argparse
import json
import math
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request

QDRANT = os.environ.get("QDRANT_URL", "http://127.0.0.1:6333")
COLLECTION = os.environ.get("RAG_COLLECTION", "rag_knowledge_base")
EMBED_MODEL = os.environ.get("EVAL_EMBED_MODEL", "qwen3.7-text-embedding-flash")
RERANK_MODEL = os.environ.get("EVAL_RERANK_MODEL", "qwen3.7-text-rerank")
RERANK_URL = "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank"
EMBED_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings"

# 与 RagProps 默认值对齐（改这里必须同步 props 类）
TOP_K = 5
THRESHOLD = 0.43
RERANK_CANDIDATES = 10
NON_EVIDENCE_FILL_CAP = 2
MAX_DOC_CHARS = 2000
RRF_K = 60
SEMANTIC_WEIGHT = 0.6

# ---------------------------------------------------------------- 评测集
# 来自 docs/sample 领域文档对应的《RAG 检索测试问题清单》(doc 69, 已禁用不参与检索)。
# 每条: (问题, 预期命中 doc_id, 说明)。doc_id 取自 knowledge_document.id。
PRECISE = [
    ("试用期一共几个月", 75, "HR 2.1 条 3 个月"),
    ("转正答辩需要多少分才能通过", 75, "HR 2.2 条 ≥80 分"),
    ("年假一年有几天，最多能结转几天", 75, "HR 3.1 条 5/10/15 天"),
    ("一线城市 P7 职级出差住宿上限是多少", 70, "差旅 2.2 表 800 元"),
    ("报销单据要在几天内提交", 70, "差旅 1 章 30 自然日"),
    ("单笔五万元的费用找谁审批", 70, "差旅 4 章 审批链"),
    ("P0 告警的响应时限是多少", 73, "运维 2.1 表 5 分钟"),
    ("数据库全量备份保留多久", 73, "运维 3.1 条 30 天"),
    ("核心交易的 RTO 恢复目标", 73, "运维 3.2 条 ≤30 分钟"),
    ("专业版每席位每月多少钱", 71, "产品 1 章 99 元"),
    ("年付订单几天内可无理由退款", 71, "产品 4 章 7 天"),
    ("钻石客户的年合同额门槛", 71, "产品 2 章 100 万"),
    ("L4 机密数据包括哪些", 74, "安全 1.1 条"),
    ("生产数据库谁能访问", 74, "安全 2.2 矩阵"),
    ("L4 访问审计日志保留几年", 74, "安全 3.2 条 ≥3 年"),
    ("服务器租赁费的科目编码", 72, "费用表 6604-01 行"),
    ("业务招待餐费单次上限和税率", 72, "费用表 6602-01 行"),
    ("市场推广广告投放归属哪个部门", 72, "费用表 6606-01 行"),
    ("告警去重的收敛窗口是多久", 68, "代码 DEDUP_WINDOW 5 分钟"),
    ("无人认领多久升级到运维组长", 68, "代码 escalateTarget 10 分钟"),
]
DISTRACTOR = [
    ("试用期费用上限是多少", 70, "跨域: HR 也有'试用期'"),
    ("试用期是几个月", 75, "跨域: 财务/产品也含'试用'"),
    ("产品试用期限多少天", 71, "跨域: 不被 HR/财务抢答"),
]
# 无关问题: 期望"未过阈值"(不得判 grounded), 用于检查改动没把拒答能力弄丢。
IRRELEVANT = [
    "今天天气怎么样", "帮我写一首诗", "明天股市会涨吗",
    "推荐几家附近的餐厅", "怎么用 Python 读 Excel", "讲个笑话",
    "北京到上海怎么走", "如何养一只猫", "世界杯谁夺冠了", "帮我翻译这段话",
]
ANSWERABLE = PRECISE + DISTRACTOR


# ---------------------------------------------------------------- 基础设施
def _post(url: str, body: dict, headers: dict | None = None, timeout: int = 30):
    req = urllib.request.Request(url, data=json.dumps(body).encode("utf-8"),
                                headers={"Content-Type": "application/json", **(headers or {})})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.load(r)


def qdrant_points() -> list[dict]:
    """滚动读回全部向量点 payload（与 KeywordIndexRebuilder 同源）。"""
    out, offset = [], None
    while True:
        body = {"limit": 500, "with_payload": True, "with_vector": False}
        if offset is not None:
            body["offset"] = offset
        res = _post(f"{QDRANT}/collections/{COLLECTION}/points/scroll", body)["result"]
        out.extend(res["points"])
        offset = res.get("next_page_offset")
        if offset is None:
            return out


def qdrant_search(vec: list[float], limit: int) -> list[dict]:
    res = _post(f"{QDRANT}/collections/{COLLECTION}/points/search",
                {"vector": vec, "limit": limit, "with_payload": True})["result"]
    return res


def disabled_doc_ids() -> set[str]:
    """禁用文档集（DocumentVisibility 同口径）。MySQL 不可读时按空集处理并告警。

    口令只从环境变量 MYSQL_PASSWORD 读，仓库内不落任何默认凭据（与 db/upgrade 脚本同约定）。
    """
    pwd = os.environ.get("MYSQL_PASSWORD")
    cmd = ["mysql", "-uroot"] + ([f"-p{pwd}"] if pwd else []) + [
        "-N", "-B", "-D", os.environ.get("MYSQL_DB", "ai_agent_db"),
        "-e", "SELECT id FROM knowledge_document WHERE enabled=0"]
    try:
        out = subprocess.run(cmd, capture_output=True, text=True, timeout=15)
        if out.returncode != 0:
            raise RuntimeError(out.stderr.strip()[:120])
        return {line.strip() for line in out.stdout.splitlines() if line.strip()}
    except Exception as e:  # noqa: BLE001 - 本地评测工具, 降级要显式告知
        print(f"[warn] 读取禁用文档失败({e})——按无禁用处理；设 MYSQL_PASSWORD 可修", file=sys.stderr)
        return set()


def embed(texts: list[str]) -> list[list[float]]:
    """分批嵌入（DashScope 对单批条数有上限，按 10 条一批；与 CountBatchingStrategy 同理）。"""
    key = os.environ.get("DASHSCOPE_API_KEY", "")
    if not key:
        sys.exit("缺少 DASHSCOPE_API_KEY 环境变量")
    out: list[list[float]] = []
    for i in range(0, len(texts), 10):
        chunk = texts[i:i + 10]
        data = _post(EMBED_URL, {"model": EMBED_MODEL, "input": chunk},
                     {"Authorization": f"Bearer {key}"}, timeout=120)
        out.extend(d["embedding"] for d in data["data"])
    return out


# ---------------------------------------------------------------- BM25（复刻 KeywordIndex）
_LATIN = re.compile(r"[a-z0-9_]+")


def _is_cjk(c: str) -> bool:
    return "\u4e00" <= c <= "\u9fff" or "\u3400" <= c <= "\u4dbf" or "\uf900" <= c <= "\ufaff"


def terms_of(text: str) -> list[str]:
    """英文/数字词元 + 中文单字 + 中文二元组（KeywordIndex.termsOf 逐字对应）。"""
    low = text.lower()
    out = [m.group() for m in _LATIN.finditer(low)]
    for i, c in enumerate(low):
        if _is_cjk(c):
            out.append(c)
            if i + 1 < len(low) and _is_cjk(low[i + 1]):
                out.append(low[i:i + 2])
    return out


class Bm25:
    """BM25(k1=1.5, b=0.75)，分词与 tf 计数与 Java 实现一致。"""

    def __init__(self, corpus: list[dict]):
        self.docs, self.post = [], {}
        for p in corpus:
            text = p.get("content", "") or ""
            key = f'{p.get("doc_id")}:{p.get("chunk_index")}'
            self.docs.append((key, len(text), p))
            for t in terms_of(text):
                self.post.setdefault(t, {}).setdefault(key, 0)
                self.post[t][key] += 1
        self.n = len(self.docs)
        self.avg = sum(d[1] for d in self.docs) / self.n if self.n else 1.0
        self.meta = {d[0]: d[2] for d in self.docs}

    def search(self, query: str, topk: int) -> list[dict]:
        scores: dict[str, float] = {}
        for t in terms_of(query):
            tfm = self.post.get(t)
            if not tfm:
                continue
            df = len(tfm)
            idf = math.log(1.0 + (self.n - df + 0.5) / (df + 0.5))
            for key, tf in tfm.items():
                ln = next(d[1] for d in self.docs if d[0] == key)
                denom = tf + 1.5 * (1 - 0.75 + 0.75 * ln / max(self.avg, 1))
                scores[key] = scores.get(key, 0.0) + idf * tf * 2.5 / denom
        top = sorted(scores.items(), key=lambda kv: -kv[1])[:topk]
        return [{"key": k, "kw": v, "p": self.meta[k]} for k, v in top]


# ---------------------------------------------------------------- 融合 / 重排 / 取数
def fuse(semantic: list[dict], keyword: list[dict]) -> list[dict]:
    """RrfFuser.fuse：按 doc_id:chunk_index 去重 + RRF(k=60) 名次融合。"""
    merged: dict[str, dict] = {}
    for i, s in enumerate(semantic):
        merged[s["key"]] = {"sem": s["sem"], "kw": None, "p": s["p"], "sr": i + 1, "kr": None}
    for i, k in enumerate(keyword):
        cur = merged.get(k["key"])
        if cur is None:
            merged[k["key"]] = {"sem": None, "kw": k["kw"], "p": k["p"], "sr": None, "kr": i + 1}
        else:
            cur["kw"], cur["kr"] = k["kw"], i + 1
    for v in merged.values():
        v["rrf"] = (1.0 / (RRF_K + v["sr"]) if v["sr"] else 0.0) \
                 + (1.0 / (RRF_K + v["kr"]) if v["kr"] else 0.0)
    return sorted(merged.values(), key=lambda v: -v["rrf"])


def rerank_score(cands: list[dict]) -> list[dict]:
    """ScoreFusionReranker：语义 0.6 + BM25 批内归一 0.4；单路直取该路分。"""
    kws = [c["kw"] for c in cands if c["kw"] is not None]
    denom = max(max(kws, default=1.0), 1e-9)
    for c in cands:
        if c["sem"] is not None and c["kw"] is not None:
            c["fused"] = SEMANTIC_WEIGHT * max(0.0, min(1.0, c["sem"])) + (1 - SEMANTIC_WEIGHT) * (c["kw"] / denom)
        elif c["sem"] is not None:
            c["fused"] = c["sem"]
        else:
            c["fused"] = c["kw"] / denom
    return sorted(cands, key=lambda c: -c["fused"])


def rerank_api(cands: list[dict], query: str) -> list[dict]:
    """DashScopeReranker：送前 rerankCandidates 条，按 relevance_score 降序，未送出者接后。"""
    key = os.environ.get("DASHSCOPE_API_KEY", "")
    limit = min(len(cands), RERANK_CANDIDATES)
    sent = cands[:limit]
    docs = [((c["p"].get("content") or "")[:MAX_DOC_CHARS]) for c in sent]
    try:
        resp = _post(RERANK_URL,
                     {"model": RERANK_MODEL,
                      "input": {"query": query, "documents": docs},
                      "parameters": {"top_n": limit, "return_documents": False}},
                     {"Authorization": f"Bearer {key}"}, timeout=30)
        results = resp.get("output", {}).get("results")
        if not isinstance(results, list):
            raise RuntimeError("响应无 output.results")
        ordered = []
        for node in results:
            idx, score = node.get("index", -1), node.get("relevance_score")
            if 0 <= idx < limit and score is not None:
                sent[idx]["fused"] = float(score)
                ordered.append(sent[idx])
        if not ordered:
            raise RuntimeError("无可用结果")
        ordered.sort(key=lambda c: -c["fused"])
        return ordered + cands[limit:]
    except Exception as e:  # noqa: BLE001 - 与生产一致: 失败回退 score
        print(f"[warn] 重排失败({e}) → 回退 score", file=sys.stderr)
        return rerank_score(cands)


def select_by_evidence(ordered: list[dict], topk: int) -> list[dict]:
    """RagRetrievalService.selectByEvidenceLevel：证据优先 + 非证据补位封顶。"""
    ev = [c for c in ordered if c["sem"] is not None and c["sem"] >= THRESHOLD]
    rest = [c for c in ordered if not (c["sem"] is not None and c["sem"] >= THRESHOLD)]
    picked = ev[:topk]
    cap = topk if not ev else min(topk, len(picked) + NON_EVIDENCE_FILL_CAP)
    for c in rest:
        if len(picked) >= cap:
            break
        picked.append(c)
    return picked


def filter_by_threshold(picked: list[dict]) -> list[dict]:
    """RagRetrievalService.filterByThreshold：无语义分保留，低于阈值裁掉。"""
    return [c for c in picked if c["sem"] is None or c["sem"] >= THRESHOLD]


def run_one(query: str, vec: list[float], bm25: Bm25, disabled: set[str], mode: str) -> dict:
    sem_raw = qdrant_search(vec, max(TOP_K * 3, 15))
    semantic = [{"key": f'{p["payload"].get("doc_id")}:{p["payload"].get("chunk_index")}',
                 "sem": p["score"], "p": p["payload"]}
                for p in sem_raw if str(p["payload"].get("doc_id")) not in disabled]
    keyword = [k for k in bm25.search(query, max(TOP_K * 2, 10))
               if str(k["p"].get("doc_id")) not in disabled]
    cands = fuse(semantic, keyword)
    ordered = rerank_score(cands) if mode == "score" else rerank_api(cands, query)
    final = filter_by_threshold(select_by_evidence(ordered, TOP_K))
    max_score = max([s["sem"] for s in semantic], default=0.0)
    return {"docs": [str(c["p"].get("doc_id")) for c in final],
            "max_score": max_score,
            "grounded": bool(final) and max_score >= THRESHOLD}


# ---------------------------------------------------------------- 主流程
def evaluate(mode: str, bm25: Bm25, disabled: set[str]) -> dict:
    answerable = [q for q, _, _ in ANSWERABLE]
    irrelevant = list(IRRELEVANT)
    vecs = embed(answerable + irrelevant)
    qv = dict(zip(answerable, vecs[:len(answerable)]))
    iv = dict(zip(irrelevant, vecs[len(answerable):]))

    rows, top1 = [], {"hit": 0, "n": 0}
    for (q, expected, note) in ANSWERABLE:
        r = run_one(q, qv[q], bm25, disabled, mode)
        rank = (r["docs"].index(str(expected)) + 1) if str(expected) in r["docs"] else None
        top1["n"] += 1
        top1["hit"] += 1 if rank == 1 else 0
        rows.append({"q": q, "expected": expected, "rank": rank, "docs": r["docs"],
                     "max_score": r["max_score"], "grounded": r["grounded"], "note": note})

    false_grounded = []
    for q in irrelevant:
        r = run_one(q, iv[q], bm25, disabled, mode)
        if r["grounded"]:
            false_grounded.append((q, round(r["max_score"], 4)))

    def hit_at(k: int) -> int:
        return sum(1 for r in rows if r["rank"] is not None and r["rank"] <= k)

    return {"mode": mode, "rows": rows, "n": len(rows),
            "top1": hit_at(1), "top3": hit_at(3), "top5": hit_at(5),
            "recall": sum(1 for r in rows if r["rank"] is not None),
            "false_grounded": false_grounded, "n_irrelevant": len(irrelevant)}


def report(result: dict, out_path: str | None) -> str:
    lines = []
    lines.append(f"# RAG 检索评测报告（rerank-mode={result['mode']}）\n")
    n = result["n"]
    lines.append(f"- 可答问题 **{n}** 条：Top-1 命中 **{result['top1']}/{n}**"
                 f"、Top-3 **{result['top3']}/{n}**、Top-5 **{result['top5']}/{n}**"
                 f"、召回 **{result['recall']}/{n}**")
    fg = result["false_grounded"]
    lines.append(f"- 无关问题 **{result['n_irrelevant']}** 条：误判 grounded **{len(fg)}** 条"
                 + (f"（{fg}）" if fg else ""))
    lines.append("\n| # | 问题 | 预期doc | 命中位次 | 语义最高分 | grounded | 注入顺序 |")
    lines.append("|---|---|---|---|---|---|---|")
    for i, r in enumerate(result["rows"], 1):
        rank = r["rank"] if r["rank"] else "**未召回**"
        lines.append(f"| {i} | {r['q']} | {r['expected']} | {rank} | "
                     f"{r['max_score']:.4f} | {'是' if r['grounded'] else '否'} | {','.join(r['docs'])} |")
    text = "\n".join(lines) + "\n"
    if out_path:
        with open(out_path, "w", encoding="utf-8") as f:
            f.write(text)
    return text


def main() -> None:
    ap = argparse.ArgumentParser(description="RAG 检索链路离线评测")
    ap.add_argument("--rerank", nargs="+", default=["score"], choices=["score", "api"],
                    help="要评测的重排模式（可多个，逐个出报告）")
    ap.add_argument("--out", help="报告输出路径（单模式时写文件）")
    args = ap.parse_args()

    points = qdrant_points()
    disabled = disabled_doc_ids()
    corpus = [p["payload"] for p in points if str(p["payload"].get("doc_id")) not in disabled]
    print(f"[info] 语料 {len(corpus)} 块 / 全库 {len(points)} 点, 禁用文档 {sorted(disabled)}")
    bm25 = Bm25(corpus)

    results = [evaluate(m, bm25, disabled) for m in args.rerank]
    for r in results:
        text = report(r, args.out if len(results) == 1 else None)
        print("\n" + text)
    if len(results) > 1:
        print("=== 对比 ===")
        for r in results:
            print(f"  {r['mode']:>5}: Top-1 {r['top1']}/{r['n']}, Top-3 {r['top3']}/{r['n']}, "
                  f"Top-5 {r['top5']}/{r['n']}, 误判 grounded {len(r['false_grounded'])}")


if __name__ == "__main__":
    main()
