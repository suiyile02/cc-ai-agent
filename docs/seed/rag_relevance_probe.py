# -*- coding: utf-8 -*-
"""
RAG 相关性诊断探针 —— 用数据钉死"未找到"到底是哪种根因。

背景: 用户反馈"员工手册""员工手册的全部内容"输入后返回"知识库未找到",
但项目里"泛查询够不到语义阈值"与"入库只进了标题没进正文"两种根因表现相同,
必须实测区分。本脚本走 /api/ai/rag/search(与对话完全同一条链), 对每个查询
打印出口判据使用的原始余弦分, 并列出知识库文档的 chunk_count 辅助判断。

它回答两个问题:
  1) 泛查询为什么失败: 对比"员工手册"(扩展开/关) 与"员工手册的全部内容"的
     semanticMaxScore —— 看它离阈值(0.45)有多远, 以及短查询扩展把分救没救回来。
  2) 是不是入库缺正文: 跑若干"聚焦到具体条款"的问题(年假/报销/加班/会议室),
     若它们也过不了阈值、snippet 只有光秃秃标题 → 正文没入库(去看文档 chunk_count);
     若它们能命中且 snippet 含"5 天带薪年假"这类正文 → 入库没问题, 纯粹是提问太泛。

前置:
  应用已启动(localhost:9090), MySQL/Qdrant 可用, 员工手册已入库(status=2)。
  管理员账号经环境变量注入(仓库不自带口令, 严禁把密钥写进文件):
    PowerShell:  $env:E2E_ADMIN_USER="admin"; $env:E2E_ADMIN_PASSWORD="<你的口令>"
    CMD:         set E2E_ADMIN_USER=admin & set E2E_ADMIN_PASSWORD=<你的口令>
运行:
  python docs/seed/rag_relevance_probe.py
"""
import http.client
import json
import os
import sys

HOST, PORT = os.environ.get('RAG_PROBE_HOST', 'localhost'), int(os.environ.get('RAG_PROBE_PORT', '9090'))

# (标签, 查询, 是否允许短查询扩展) —— 扩展开关用于对比"裸词本身分" vs "被救回的分"
QUERIES = [
    ('泛·短(扩展开=对话口径)', '员工手册', True),
    ('泛·短(扩展关=看裸词)', '员工手册', False),
    ('泛·长(整册当查询)', '员工手册的全部内容', True),
    ('聚焦·年假', '年假有几天？', True),
    ('聚焦·差旅报销', '差旅报销住宿标准是多少？', True),
    ('聚焦·加班工资', '工作日加班怎么算工资？', True),
    ('聚焦·会议室预订', '会议室能预定多长时间？', True),
]


def req(method, path, payload=None, token=None, timeout=120):
    conn = http.client.HTTPConnection(HOST, PORT, timeout=timeout)
    headers = {'Content-Type': 'application/json; charset=utf-8'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    body = json.dumps(payload, ensure_ascii=False).encode('utf-8') if payload is not None else None
    conn.request(method, path, body=body, headers=headers)
    resp = conn.getresponse()
    raw = resp.read().decode('utf-8', errors='replace')
    conn.close()
    try:
        return resp.status, json.loads(raw)
    except json.JSONDecodeError:
        return resp.status, {'raw': raw}


def one_line(text, limit=90):
    """把多行 snippet 压成一行, 便于对齐阅读。"""
    if not text:
        return ''
    return ' '.join(str(text).split())[:limit]


def rag_search(token, question, expand_short):
    st, body = req('POST', '/api/ai/rag/search', {
        'question': question,
        'topK': None,                    # 跟随 app.rag.top-k(默认 5)
        'similarityThreshold': None,     # 跟随 app.rag.similarity-threshold(默认 0.45)
        'expandShortQuery': expand_short,
    }, token=token)
    if st != 200 or body.get('code') != 200:
        return None, body
    return body.get('data', {}), None


def probe(token):
    print('\n===== 逐查询检索判据(与对话同一条链) =====')
    for label, q, expand in QUERIES:
        data, err = rag_search(token, q, expand)
        print('\n【%s】输入: %r' % (label, q))
        if err is not None or data is None:
            print('   调用失败: %s' % json.dumps(err, ensure_ascii=False)[:200])
            continue
        if data.get('degraded'):
            print('   ⚠ 本轮检索超时/异常降级(executed=%s), 零命中不代表库里没有, 请重试'
                  % data.get('executed'))
        thr = data.get('similarityThreshold')
        sms = data.get('semanticMaxScore')
        passed = (sms is not None and thr is not None and sms >= thr)
        print('   实际检索句 retrievalQuery : %r' % data.get('retrievalQuery'))
        print('   是否短查询扩展 expanded     : %s' % data.get('expanded'))
        print('   语义最大余弦 semanticMaxScore: %s  (阈值 %.2f → %s)'
              % (('%.4f' % sms) if sms is not None else 'null',
                 thr if thr is not None else 0.0,
                 '过阈值 ✓' if passed else '未过阈值 ✗'))
        print('   语义过阈条数 semanticCount  : %s | 关键词命中 keywordCount: %s | 最终注入 finalHits: %s'
              % (data.get('semanticCount'), data.get('keywordCount'), data.get('finalHits')))
        print('   对话出口 answerOutcome      : %s   (kbOnly=%s)'
              % (data.get('answerOutcome'), data.get('kbOnly')))
        sources = data.get('sources') or []
        if not sources:
            print('   命中来源: (空)')
        for i, s in enumerate(sources[:3]):
            sem = s.get('semanticScore')
            print('     #%d [sem=%s kw=%s] %s :: %s'
                  % (i + 1,
                     ('%.4f' % sem) if sem is not None else 'null(纯词面命中)',
                     ('%.3f' % s.get('keywordScore')) if s.get('keywordScore') is not None else '-',
                     one_line(s.get('fileName'), 24),
                     one_line(s.get('snippet'), 80)))


def list_documents(token):
    print('\n===== 知识库文档清单(判断正文是否真入库) =====')
    print('   chunk_count 若只有个位数且都是"章节标题", 说明正文没进切分; 正常中文手册按章节/段落切应有数十块。')
    st, body = req('GET', '/api/knowledge/documents?pageNum=1&pageSize=100', token=token)
    if st != 200 or body.get('code') != 200:
        print('   拉取文档列表失败: %s' % json.dumps(body, ensure_ascii=False)[:200])
        return
    records = (body.get('data', {}) or {}).get('records', []) or []
    if not records:
        print('   知识库为空 —— 难怪"未找到", 需要先上传并等待入库(status=2)。')
        return
    status_map = {0: '待处理', 1: '处理中', 2: '已完成', 3: '失败'}
    for d in records:
        print('   id=%-4s status=%-6s chunk=%-4s enabled=%-5s %s%s'
              % (d.get('id'), status_map.get(d.get('status'), d.get('status')),
                 d.get('chunkCount'), d.get('enabled'), d.get('fileName'),
                 ('  | err=' + one_line(d.get('errorMessage'), 40)) if d.get('errorMessage') else ''))


def interpret():
    print('\n===== 如何读上面的结果 =====')
    print('  规则A(入库没问题, 是提问太泛): 聚焦类(年假/报销/加班/会议室)大多"过阈值 ✓"且')
    print('        snippet 含具体正文(如"5 天带薪年假"), 而泛类(员工手册/全部内容)"未过阈值 ✗"。')
    print('        → 结论: 正文在库里, 请用具体问题提问; 短查询扩展救不了"整册主题词"。')
    print('  规则B(入库缺正文/只进了标题): 聚焦类也"未过阈值 ✗", 或命中的 snippet 只有章节标题、')
    print('        且文档清单里对应员工手册 chunk_count 异常偏低 → 需重新处理该文档(检查解析/切分)。')
    print('  对比"扩展开 vs 扩展关"两行"员工手册": retrievalQuery 展示扩展把它补成了什么句,')
    print('        semanticMaxScore 差值即扩展的实际效果(是否把 0.3x 抬到过阈值)。')


def main():
    admin_user = os.environ.get('E2E_ADMIN_USER')
    admin_pass = os.environ.get('E2E_ADMIN_PASSWORD')
    if not admin_user or not admin_pass:
        print('缺少环境变量 E2E_ADMIN_USER / E2E_ADMIN_PASSWORD, 请先注入管理员口令(勿写进文件)。')
        sys.exit(1)

    st, body = req('POST', '/api/auth/login', {'username': admin_user, 'password': admin_pass})
    if st != 200 or body.get('code') != 200:
        print('管理员登录失败(HTTP %s): %s' % (st, json.dumps(body, ensure_ascii=False)[:200]))
        sys.exit(1)
    token = body['data']['token']
    print('管理员登录成功, 目标 %s:%d' % (HOST, PORT))

    list_documents(token)
    probe(token)
    interpret()
    print('\n完成。把上面整段输出发回来, 即可据此判定根因(泛查询 vs 入库缺正文)。')


if __name__ == '__main__':
    main()
