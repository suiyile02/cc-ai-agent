# -*- coding: utf-8 -*-
"""
端到端业务流程测试：覆盖注册/登录/会话/多轮对话(RAG+工具+记忆+流式)/知识库/系统日志授权/异常路径。
前置：应用已启动(http://localhost:9090), MySQL/Qdrant 可用, DASHSCOPE_API_KEY 已配置。
运行：python docs/seed/e2e_test.py
输出：逐项 PASS/FAIL/WARN 与汇总, 有 FAIL 时退出码为 1。
"""
import http.client
import json
import time
from urllib.parse import quote

HOST, PORT = 'localhost', 9090
results = []


def check(name, ok, detail=''):
    results.append((name, ok, detail))
    print(('PASS' if ok else 'FAIL') + ('  ' + name + ('  | ' + detail if detail else '')))
    return ok


def warn(name, detail=''):
    results.append((name, True, detail))
    print('WARN  ' + name + '  | ' + detail)


def req(method, path, payload=None, token=None, timeout=90):
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


def upload(token, filename, content):
    boundary = '----e2eboundary'
    body = (f'--{boundary}\r\n'.encode('ascii')
            + f'Content-Disposition: form-data; name="file"; filename="{filename}"\r\n'.encode('utf-8')
            + b'Content-Type: text/markdown\r\n\r\n'
            + content.encode('utf-8')
            + ('\r\n--%s--\r\n' % boundary).encode('ascii'))
    conn = http.client.HTTPConnection(HOST, PORT, timeout=90)
    conn.request('POST', '/api/knowledge/upload', body=body,
                 headers={'Content-Type': 'multipart/form-data; boundary=' + boundary,
                          'Authorization': 'Bearer ' + token})
    resp = conn.getresponse()
    return resp.status, json.loads(resp.read().decode('utf-8'))


def qdrant_count():
    conn = http.client.HTTPConnection('localhost', 6333, timeout=10)
    conn.request('POST', '/collections/rag_knowledge_base/points/count',
                 body='{"exact": true}', headers={'Content-Type': 'application/json'})
    n = json.loads(conn.getresponse().read())['result']['count']
    conn.close()
    return n


def login(username, password):
    st, r = req('POST', '/api/auth/login', {'username': username, 'password': password})
    if st == 200 and r.get('data'):
        return r['data']['token'], r['data']['user']
    return None, r


print('========== 1. 健康检查与认证 ==========')
st, r = req('GET', '/actuator/health')
check('应用健康检查', st == 200)

st, r = req('GET', '/api/sessions')
check('未登录访问返回 401/6005', st == 401 and r.get('code') == 6005, f'http={st} code={r.get("code")}')

st, r = req('GET', '/api/sessions', token='invalid.token.xxx')
check('无效令牌返回 401/6005', st == 401 and r.get('code') == 6005, f'http={st}')

# 注册(e2e_user 已存在则直接登录, 保证脚本可重复执行)
st, r = req('POST', '/api/auth/register',
            {'username': 'e2e_user', 'password': 'e2e123456', 'nickname': 'E2E测试员'})
if st == 200 and r.get('data'):
    check('用户注册', True, 'e2e_user 新注册')
else:
    check('用户注册', r.get('code') == 6002, f'code={r.get("code")} msg={r.get("message")}')
st, r = req('POST', '/api/auth/login', {'username': 'e2e_user', 'password': 'e2e123456'})
check('登录成功', st == 200 and r['data']['token'], f"user={r['data']['user']['username'] if r.get('data') else '-'}")
E2E_TOKEN = r['data']['token']
E2E_ID = r['data']['user']['id']

st, r = req('POST', '/api/auth/login', {'username': 'e2e_user', 'password': 'wrong-password'})
check('错误密码登录返回 6003', st == 401 and r.get('code') == 6003, f'http={st} code={r.get("code")}')

st, r = req('POST', '/api/auth/register', {'username': 'bad!', 'password': 'x'})
check('注册参数校验返回 400', st == 400 and r.get('code') == 4001, f'http={st}')

st, r = req('GET', '/api/auth/me', token=E2E_TOKEN)
check('GET /api/auth/me', st == 200 and r['data']['username'] == 'e2e_user', f'role={r["data"].get("role")}')

ADMIN_TOKEN, ADMIN_USER = login('admin', 'admin123')
check('admin 登录且角色为 ADMIN', ADMIN_TOKEN and ADMIN_USER.get('role') == 'ADMIN',
      f'role={ADMIN_USER.get("role") if ADMIN_USER else "-"}')
TESTER_TOKEN, _ = login('tester', 'test123')
check('tester 登录(种子用户)', bool(TESTER_TOKEN))

# 清空语义缓存, 保证后续断言(流式增量/上下文装配日志)不被上一轮的缓存命中污染
st, r = req('DELETE', '/api/system/semantic-cache', token=E2E_TOKEN)
check('普通用户清空语义缓存被拒绝(403/5002)', st == 403 and r.get('code') == 5002,
      f'http={st} code={r.get("code")}')
st, r = req('DELETE', '/api/system/semantic-cache', token=ADMIN_TOKEN)
check('管理员清空语义缓存(冷缓存基线)', st == 200 and isinstance(r.get('data'), int) and r['data'] >= 0,
      f"kbVersion={r.get('data')}")

print('========== 2. 会话管理 ==========')
st, r = req('POST', '/api/sessions', {'title': '【E2E】混合会话', 'sessionType': 'HYBRID'}, token=E2E_TOKEN)
SESSION_A = r['data']['sessionId']
A_PK = r['data']['id']
check('创建 HYBRID 会话', st == 200 and SESSION_A, f'sessionId={SESSION_A}')

st, r = req('POST', '/api/sessions', {'title': '【E2E】管理员会话'}, token=ADMIN_TOKEN)
B_PK = r['data']['id']

st, r = req('GET', '/api/sessions', token=E2E_TOKEN)
check('会话列表按本人过滤', st == 200 and all(v['userId'] == E2E_ID for v in r['data']['records']),
      f"total={r['data']['total']}")

st, r = req('PUT', f'/api/sessions/{B_PK}/archive', token=E2E_TOKEN)
check('越权归档他人会话返回 403/5002', st == 403 and r.get('code') == 5002, f'http={st} code={r.get("code")}')

st, r = req('DELETE', f'/api/sessions/{B_PK}', token=E2E_TOKEN)
check('越权删除他人会话返回 403/5002', st == 403 and r.get('code') == 5002, f'http={st}')

print('========== 3. 多轮对话(RAG 检索 + 查询改写 + 工具 + 记忆) ==========')
# 冷缓存基线(段 1 已清空语义缓存), 故本轮全部为"未命中"路径
st, r = req('POST', '/api/ai/chat', {'sessionId': SESSION_A, 'message': '入职满两年的员工有多少天年假？'},
            token=E2E_TOKEN, timeout=120)
q1_ok = st == 200 and bool(r['data']['content'])
Q1_QUESTION = '入职满两年的员工有多少天年假？'
Q1_CONTENT = r['data']['content'] if q1_ok else ''
# 来源不再返回前端(只落库 chat_log.sources), 此处只校验回答内容
check('多轮#1 知识库问答回答正常', q1_ok,
      f"content={r['data']['content'][:40]}" if q1_ok else f'code={r.get("code")} {r.get("message")}')

st, r = req('POST', '/api/ai/chat', {'sessionId': SESSION_A, 'message': '那病假呢？'}, token=E2E_TOKEN, timeout=120)
check('多轮#2 追问(依赖查询改写)', st == 200 and r['data']['content'], f"content={r['data']['content'][:40] if r.get('data') else r.get('message')}")

st, r = req('POST', '/api/ai/chat', {'sessionId': SESSION_A, 'message': '张三在哪个部门？'},
            token=E2E_TOKEN, timeout=120)
q3 = st == 200 and r['data']['content']
check('多轮#3 工具问答(张三部门)', q3, f"content={r['data']['content'][:40] if r.get('data') else r.get('message')}")

st, r = req('POST', '/api/ai/chat', {'sessionId': SESSION_A, 'message': '他的邮箱是什么？'},
            token=E2E_TOKEN, timeout=120)
content4 = r['data']['content'] if st == 200 and r.get('data') else ''
check('多轮#4 指代记忆(他的邮箱)', bool(content4), f"content={content4[:40] if content4 else r.get('message')}")
if content4:
    if 'zhangsan' in content4 or '@demo.com' in content4:
        check('多轮#4 邮箱内容校验', True, '命中 zhangsan@demo.com')
    else:
        warn('多轮#4 邮箱内容校验', f'回答未直接给出邮箱: {content4[:60]}')

st, r = req('POST', '/api/ai/chat', {'sessionId': SESSION_A, 'message': '今天天气怎么样？'},
            token=E2E_TOKEN, timeout=120)
check('多轮#5 闲聊路由(不检索)', st == 200 and r['data']['content'], f"content={r['data']['content'][:40] if r.get('data') else r.get('message')}")

print('========== 4. SSE 流式问答 ==========')
conn = http.client.HTTPConnection(HOST, PORT, timeout=120)
conn.request('POST', '/api/ai/chat/stream',
             body=json.dumps({'sessionId': SESSION_A, 'message': '简单介绍一下差旅住宿标准'}, ensure_ascii=False).encode('utf-8'),
             headers={'Content-Type': 'application/json; charset=utf-8', 'Authorization': 'Bearer ' + E2E_TOKEN})
resp = conn.getresponse()
stream_body = resp.read().decode('utf-8', errors='replace')
conn.close()
stage_events, content_chunks = [], []
for block in stream_body.split('\n\n'):
    ev, datas = None, []
    for ln in block.splitlines():
        if ln.startswith('event:'):
            ev = ln[6:].strip()
        elif ln.startswith('data:'):
            datas.append(ln[5:])
    if not datas:
        continue
    data = ''.join(datas)
    if ev == 'stage':
        stage_events.append(data)
    elif ev == 'content':
        content_chunks.append(data)
check('SSE 无过时 stage 事件(契约已移除)', resp.status == 200 and len(stage_events) == 0,
      f'stage events={len(stage_events)}')
# 语义缓存命中时整段回答只发 1 个 content 事件; 本轮已在段 1 清空缓存且该问题首次出现,
# 故必然走未命中路径, 应看到逐 token 的多个增量
check('SSE 正文增量输出(content>=2, 未命中路径)', len(content_chunks) >= 2,
      f'chunks={len(content_chunks)} total={sum(len(c) for c in content_chunks)} 字')
check('SSE 以 [DONE] 结束', resp.status == 200 and stream_body.rstrip().endswith('[DONE]'))

print('========== 5. 系统日志与管理员或本人授权 ==========')
st, r = req('GET', f'/api/system/chat-logs?sessionId={SESSION_A}', token=E2E_TOKEN)
check('对话日志可查(本人会话)', st == 200 and r['data']['total'] >= 5, f"total={r['data']['total']}")
check('对话日志归属本人', all(v['userId'] == E2E_ID for v in r['data']['records']))

st, r = req('GET', '/api/system/chat-logs?userId=1', token=E2E_TOKEN)
check('普通用户传他人 userId 被强制改写', st == 200 and r['data']['total'] > 0
      and all(v['userId'] == E2E_ID for v in r['data']['records']),
      f"total={r['data']['total']} all-self={all(v['userId'] == E2E_ID for v in r['data']['records'])}")

st, r = req('GET', '/api/system/chat-logs?userId=1', token=ADMIN_TOKEN)
check('管理员可查他人日志(全量)', st == 200 and r['data']['total'] > 0
      and all(v['userId'] == 1 for v in r['data']['records']),
      f"total={r['data']['total']}")

st, r = req('GET', f'/api/system/tool-call-logs?sessionId={SESSION_A}', token=ADMIN_TOKEN)
rows = r['data']['records'] if st == 200 else []
check('工具日志回填 session_id/user_id(ToolContext 修复)',
      st == 200 and len(rows) >= 1 and all(v['sessionId'] == SESSION_A and v['userId'] == E2E_ID for v in rows),
      f"rows={len(rows)} first={rows[0]['toolName'] if rows else '-'}")

st, r = req('GET', f'/api/system/rag-decisions?sessionId={SESSION_A}', token=E2E_TOKEN)
rows = r['data']['records'] if st == 200 else []
general_rows = [v for v in rows if v['ragMode'] == 'GENERAL']
check('RAG 决策日志落库', st == 200 and r['data']['total'] >= 5, f"total={r['data']['total']}")
# P3-7 起 GENERAL 也一律检索(出口由检索事实算出), 故此处断言反转: 闲聊轮必须"检索过但无据可依"。
check('闲聊轮已执行检索且出口为无据拒答(P3-7)', len(general_rows) >= 1
      and all(v['retrievalExecuted'] for v in general_rows)
      and all(v.get('answerOutcome') in ('REFUSED_NO_EVIDENCE', 'ANSWERED_OPEN') for v in general_rows),
      f"GENERAL rows={[(v.get('answerOutcome'), v['retrievalExecuted']) for v in general_rows]}")

st, r = req('GET', f'/api/system/context-logs?sessionId={SESSION_A}', token=E2E_TOKEN)
rows = r['data']['records'] if st == 200 else []
rewritten = [v for v in rows if v['rewrittenQuery']]
# 冷缓存基线保证 5 轮问答都真实走了装配(缓存命中会跳过装配, 不产生 context_log)
check('上下文装配日志落库(Token 预算)', st == 200 and r['data']['total'] >= 5, f"total={r['data']['total']}")
if rewritten:
    check('多轮查询改写生效(rewritten_query)', True, f"示例: {rewritten[0]['userMessage'][:14]} -> {rewritten[0]['rewrittenQuery'][:24]}")
else:
    warn('多轮查询改写生效(rewritten_query)', '改写器返回了原问题(LLM 行为), 建议查看 context-logs')

print('========== 6. 语义缓存(命中短路 + 知识库变更联动失效) ==========')
time.sleep(2)   # 审计为异步落库, 先等内容稳定再计数
_, r = req('GET', f'/api/system/context-logs?sessionId={SESSION_A}', token=E2E_TOKEN)
CTX_BEFORE_HIT = r['data']['total']
# 重问段 3 已问过的同一问题(独立问题, 未改写, KB 命中 → 上轮已写缓存)
st, r = req('POST', '/api/ai/chat', {'sessionId': SESSION_A, 'message': Q1_QUESTION}, token=E2E_TOKEN, timeout=120)
CACHED_CONTENT = r['data']['content'] if st == 200 and r.get('data') else ''
check('语义缓存命中(回答与首次一致)', st == 200 and CACHED_CONTENT == Q1_CONTENT and bool(Q1_CONTENT),
      f'len={len(CACHED_CONTENT)} 与首轮一致={CACHED_CONTENT == Q1_CONTENT}')
st, r = req('GET', f'/api/system/context-logs?sessionId={SESSION_A}', token=E2E_TOKEN)
CTX_AFTER_HIT = r['data']['total']
check('缓存命中跳过上下文装配(无新增 context_log)', st == 200 and CTX_AFTER_HIT == CTX_BEFORE_HIT,
      f'{CTX_BEFORE_HIT} -> {CTX_AFTER_HIT}')
time.sleep(1.5)   # 决策日志同为异步落库
st, r = req('GET', f'/api/system/rag-decisions?sessionId={SESSION_A}&ragMode=KB', token=E2E_TOKEN)
hit_rows = [v for v in (r['data']['records'] if st == 200 else []) if not v['retrievalExecuted']]
check('缓存命中在决策日志留痕(KB 且未检索)', st == 200 and len(hit_rows) >= 1,
      f'KB未检索行={len(hit_rows)} (最新: finalHits={hit_rows[0]["finalHits"] if hit_rows else "-"})')

print('========== 7. 知识库管理 ==========')
# 空文件上传应被友好拒绝(1005), 不产生文档记录
st, r = upload(E2E_TOKEN, '空文件测试.txt', '')
check('空文件上传被拒(1005)', st == 400 and r.get('code') == 1005, f'http={st} code={r.get("code")}')
# 清理历史运行的残留文档(同名项目管理规范), 保证基线干净
lst_st, lst = req('GET', '/api/knowledge/documents?pageNum=1&pageSize=50&fileName=' + quote('项目管理规范'), token=ADMIN_TOKEN)
for v in ((lst.get('data') or {}).get('records') or []):
    req('DELETE', f"/api/knowledge/documents/{v['id']}", token=ADMIN_TOKEN)
time.sleep(2)
before = qdrant_count()
st, r = upload(E2E_TOKEN, '测试-项目管理规范.md',
               '# 项目管理规范\n\n## 代码评审\n所有合并请求必须至少一名同事评审通过。\n\n## 发布流程\n发布窗口为每周三与周五, 需提前创建发布单。\n')
DOC_E2E = r['data']['docId'] if st == 200 and r.get('data') else None
check('上传知识文档', st == 200 and DOC_E2E, f'docId={DOC_E2E}')
for _ in range(20):
    st, r = req('GET', '/api/knowledge/documents?pageNum=1&pageSize=20&fileName=' + quote('项目管理规范'), token=E2E_TOKEN)
    rec = next((v for v in r['data']['records'] if v['id'] == DOC_E2E), None) if st == 200 else None
    if rec and rec['status'] == 2:
        break
    time.sleep(3)
check('异步入库完成(status=2)', rec is not None and rec['status'] == 2, f"chunks={rec['chunkCount'] if rec else '-'}")
after_upload = qdrant_count()
check('Qdrant 向量点数与分块一致', after_upload == before + (rec['chunkCount'] if rec else 0),
      f'{before} -> {after_upload}')

st, r = req('POST', f'/api/knowledge/documents/{DOC_E2E}/reprocess', token=E2E_TOKEN)
check('普通用户 reprocess 被授权拒绝(403/5002)', st == 403 and r.get('code') == 5002,
      f'http={st} code={r.get("code")}')
time.sleep(8)
st, r = req('GET', '/api/knowledge/documents?pageNum=1&pageSize=20&fileName=' + quote('项目管理规范'), token=E2E_TOKEN)
rec = next((v for v in r['data']['records'] if v['id'] == DOC_E2E), None) if st == 200 else None
check('重新入库(reprocess)', rec is not None and rec['status'] == 2, f"status={rec['status'] if rec else '-'}")

st, r = req('DELETE', f'/api/knowledge/documents/{DOC_E2E}', token=E2E_TOKEN)
check('普通用户删除文档被授权拒绝(403/5002)', st == 403 and r.get('code') == 5002,
      f'http={st} code={r.get("code")}')
st, r = req('DELETE', f'/api/knowledge/documents/{DOC_E2E}', token=ADMIN_TOKEN)
after_delete = qdrant_count()
for _ in range(5):                       # Qdrant 清理为异步生效, 轮询等待
    if after_delete == before:
        break
    time.sleep(2)
    after_delete = qdrant_count()
st2, r2 = req('GET', '/api/knowledge/documents?pageNum=1&pageSize=20&fileName=' + quote('项目管理规范') + '', token=E2E_TOKEN)
check('管理员删除文档且向量同步清理', st == 200 and after_delete == before
      and r2['data']['total'] == 0, f'qdrant {after_upload}->{after_delete} (基线{before})')

st, r = req('GET', '/api/knowledge/documents?pageNum=1&pageSize=20', token=ADMIN_TOKEN)
check('管理员查看全部文档(含 tester 上传)', st == 200
      and any(v['fileName'] == '考勤与假期制度.md' for v in r['data']['records']),
      f"total={r['data']['total']}")

st, r = req('POST', '/api/ai/rag/search', {'question': '年假有多少天', 'topK': 3, 'similarityThreshold': 0.3},
            token=E2E_TOKEN)
check('RAG 检索调试接口', st == 200 and r['data'], f"hits={len(r['data'] or [])}")

# 知识库变更(上传/删除)已使语义缓存失效: 重问段 6 命中过缓存的问题应重新装配并再落 context_log
time.sleep(2)   # 同上: 等异步审计落库
_, r = req('GET', f'/api/system/context-logs?sessionId={SESSION_A}', token=E2E_TOKEN)
CTX_BEFORE_KBCHANGE = r['data']['total']
st, r = req('POST', '/api/ai/chat', {'sessionId': SESSION_A, 'message': Q1_QUESTION}, token=E2E_TOKEN, timeout=120)
_, r2 = req('GET', f'/api/system/context-logs?sessionId={SESSION_A}', token=E2E_TOKEN)
check('知识库变更后语义缓存联动失效(重新装配)', st == 200 and bool(r['data']['content'])
      and r2['data']['total'] > CTX_BEFORE_KBCHANGE,
      f"context_log {CTX_BEFORE_KBCHANGE} -> {r2['data']['total']}")

print('========== 8. 会话生命周期与异常路径 ==========')
st, r = req('POST', '/api/sessions', {'title': '【E2E】待归档', 'sessionType': 'RAG'}, token=E2E_TOKEN)
C_PK = r['data']['id']
C_SID = r['data']['sessionId']
st, r = req('PUT', f'/api/sessions/{C_PK}/archive', token=E2E_TOKEN)
check('归档会话', st == 200)
st, r = req('POST', '/api/ai/chat', {'sessionId': C_SID, 'message': '年假政策'}, token=E2E_TOKEN, timeout=60)
check('归档会话继续对话返回 3001', st == 404 and r.get('code') == 3001, f'http={st} code={r.get("code")}')

st, r = req('POST', '/api/sessions', {'title': '【E2E】待删除'}, token=E2E_TOKEN)
D_PK = r['data']['id']
D_SID = r['data']['sessionId']
st, r = req('DELETE', f'/api/sessions/{D_PK}', token=E2E_TOKEN)
st2, r2 = req('POST', '/api/ai/chat', {'sessionId': D_SID, 'message': '在吗'}, token=E2E_TOKEN, timeout=60)
st3, r3 = req('GET', '/api/sessions', token=E2E_TOKEN)
gone = all(v['id'] != D_PK for v in r3['data']['records'])
check('删除会话(软删)后不可再对话且列表消失', st == 200 and st2 == 404 and gone, f'chat http={st2}, gone={gone}')

st, r = req('PUT', '/api/sessions/999999/archive', token=E2E_TOKEN)
check('会话不存在返回 404/3001', st == 404 and r.get('code') == 3001, f'http={st}')

conn = http.client.HTTPConnection(HOST, PORT, timeout=10)
conn.request('POST', '/api/auth/login', body=b'{bad json', headers={'Content-Type': 'application/json'})
resp = conn.getresponse()
bad = json.loads(resp.read().decode('utf-8'))
conn.close()
check('非法请求体返回 400/4001', resp.status == 400 and bad.get('code') == 4001, f'http={resp.status}')

print('\n========== 汇总 ==========')
passed = sum(1 for _, ok, _ in results if ok)
warned = sum(1 for n, _, _ in results if n.startswith('多轮查询改写'))
failed = [n for n, ok, _ in results if not ok]
print(f'共 {len(results)} 项: PASS {passed - 0} / FAIL {len(failed)} (含 WARN {warned})')
if failed:
    print('失败项:')
    for n in failed:
        print('  -', n)
exit(1 if failed else 0)
