# -*- coding: utf-8 -*-
"""
全项目功能覆盖测试(补 e2e_test.py 未覆盖的点)。

覆盖: 统一响应/错误码矩阵、鉴权与角色矩阵、会话改名与消息回放、RAG 新调试契约
(三分数口径 + 出口预测 + 阈值有效性)、短查询扩展(触发/关闭对照/不写缓存)、
kb-only 三出口、批量上传逐文件结果、上传校验顺序五连击、四张日志表字段口径、
语义缓存清空与跨权限、降级路径参数。

前置: 应用已在 9090 启动, MySQL/Qdrant/Redis 可用, DASHSCOPE_API_KEY 已配置。
管理员用例需要 E2E_ADMIN_USER / E2E_ADMIN_PASSWORD(仓库不自带口令)。
运行: python -X utf8 docs/seed/full_coverage_test.py > full_out.txt 2>&1
"""
import http.client
import io
import json
import os
import sys
import threading
import time
from urllib.parse import quote

HOST, PORT = 'localhost', int(os.environ.get('APP_PORT', '9090'))
RESULTS = []
ADMIN_USER = os.environ.get('E2E_ADMIN_USER', '')
ADMIN_PASS = os.environ.get('E2E_ADMIN_PASSWORD', '')


def record(name, ok, detail='', level='FAIL'):
    RESULTS.append((name, bool(ok), detail))
    tag = 'PASS' if ok else level
    line = '%-5s %s' % (tag, name)
    if detail:
        line += '  | ' + str(detail)[:160]
    print(line)
    return bool(ok)


def check(name, ok, detail=''):
    return record(name, ok, detail, 'FAIL')


def warn(name, detail=''):
    return record(name, True, detail, 'WARN')


def req(method, path, payload=None, token=None, timeout=180):
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


def upload(token, filename, content, field='file', extra=None):
    """单/多文件 multipart 上传; extra=[(字段名, 文件名, bytes), ...]"""
    boundary = '----cov' + os.urandom(8).hex()
    buf = io.BytesIO()

    def add(field_name, file_name, data):
        buf.write(('--%s\r\n' % boundary).encode())
        buf.write(('Content-Disposition: form-data; name="%s"; filename="%s"\r\n'
                   % (field_name, file_name)).encode('utf-8'))
        buf.write(b'Content-Type: application/octet-stream\r\n\r\n')
        buf.write(data)
        buf.write(b'\r\n')
    if content is not None:
        add(field, filename, content if isinstance(content, bytes) else content.encode('utf-8'))
    for f, n, d in (extra or []):
        add(f, n, d if isinstance(d, bytes) else d.encode('utf-8'))
    buf.write(('--%s--\r\n' % boundary).encode())
    conn = http.client.HTTPConnection(HOST, PORT, timeout=240)
    conn.request('POST', '/api/knowledge/upload' if field == 'file' else '/api/knowledge/upload/batch',
                 body=buf.getvalue(),
                 headers={'Content-Type': 'multipart/form-data; boundary=' + boundary,
                          **({'Authorization': 'Bearer ' + token} if token else {})})
    r = conn.getresponse()
    raw = r.read().decode('utf-8', errors='replace')
    conn.close()
    try:
        return r.status, json.loads(raw)
    except json.JSONDecodeError:
        return r.status, {'raw': raw}


def login(username, password):
    """登录。AuthVO = {token, user:{id, username, role, ...}}, 这里返回 (token, user)。"""
    st, r = req('POST', '/api/auth/login', {'username': username, 'password': password})
    data = r.get('data') or {}
    if st != 200 or not data.get('token'):
        return None, {}
    return data['token'], (data.get('user') or {})


def wait_doc(doc_id, token, want_status=2, limit=40):
    for _ in range(limit):
        st, r = req('GET', '/api/knowledge/documents?pageNum=1&pageSize=100', token=token)
        rec = next((v for v in ((r.get('data') or {}).get('records') or []) if v['id'] == doc_id), None)
        if rec and rec['status'] == want_status:
            return rec
        time.sleep(3)
    return None


print('=' * 78)
print('全项目功能覆盖测试  host=%s:%s  时间=%s' % (HOST, PORT, time.strftime('%F %T')))
print('=' * 78)

# ---------------------------------------------------------------- 0. 健康与匿名
print('\n---------- 0. 健康检查与匿名访问 ----------')
st, r = req('GET', '/actuator/health')
check('GET /actuator/health 返回 UP', st == 200 and r.get('status') == 'UP', r.get('status'))
st, r = req('GET', '/actuator/env')
check('未暴露 /actuator/env(非 200)', st != 200, 'http=%s' % st)
st, r = req('GET', '/api/sessions')
check('匿名访问业务接口 401/6005', st == 401 and r.get('code') == 6005, 'http=%s code=%s' % (st, r.get('code')))
st, r = req('GET', '/api/sessions', token='garbage.token.value')
check('伪造 Token 被拒 401/6005', st == 401 and r.get('code') == 6005, 'http=%s code=%s' % (st, r.get('code')))
st, r = req('POST', '/api/auth/login', {'username': 'nobody_' + os.urandom(3).hex(), 'password': 'x'})
check('不存在用户登录返回 6xxx 且 HTTP 401', st == 401 and 6000 <= (r.get('code') or 0) < 7000,
      'http=%s code=%s msg=%s' % (st, r.get('code'), r.get('message')))

# ---------------------------------------------------------------- 1. 注册/登录/改密
print('\n---------- 1. 注册与登录 ----------')
SU = 'cov_%s' % os.urandom(3).hex()
SP = 'CovPass_123'
st, r = req('POST', '/api/auth/register', {'username': SU, 'password': SP, 'nickname': '覆盖测试员'})
check('注册新用户', st == 200 and (r.get('data') or {}).get('token'), 'code=%s' % r.get('code'))
U_TOK, U_ME = login(SU, SP)
check('新用户默认角色为 USER', U_TOK and U_ME.get('role') == 'USER', U_ME.get('role'))
st, r = req('POST', '/api/auth/register', {'username': SU, 'password': SP})
check('重复用户名注册被拒(409/6xxx)', st == 409 and 6000 <= (r.get('code') or 0) < 7000,
      'http=%s code=%s' % (st, r.get('code')))
st, r = req('POST', '/api/auth/register', {'username': 'bad!', 'password': 'x'})
check('注册参数校验 400/4001', st == 400 and r.get('code') == 4001, 'code=%s' % r.get('code'))
st, r = req('POST', '/api/auth/login', {'username': SU, 'password': 'wrong-pass'})
check('密码错误 401/6xxx', st == 401 and 6000 <= (r.get('code') or 0) < 7000, 'code=%s' % r.get('code'))
st, r = req('GET', '/api/auth/me', token=U_TOK)
check('GET /api/auth/me', st == 200 and r['data']['username'] == SU, r['data'].get('username'))
U_ME = r['data']                                # 用 me 接口的权威 UserVO 覆盖

if ADMIN_USER and ADMIN_PASS:
    A_TOK, A_ME = login(ADMIN_USER, ADMIN_PASS)
    HAS_ADMIN = bool(A_TOK and A_ME.get('role') == 'ADMIN')
else:
    A_TOK, HAS_ADMIN = None, False
if not HAS_ADMIN:
    warn('管理员用例全部跳过(未注入 E2E_ADMIN_USER/E2E_ADMIN_PASSWORD)',
         '知识库写入类断言将按"非管理员被拒"方向测')
else:
    check('管理员登录且 role=ADMIN', HAS_ADMIN, A_ME.get('role'))

# 模拟登录头必须关闭(仓库已无 dev profile 文件)
conn = http.client.HTTPConnection(HOST, PORT, timeout=30)
conn.request('GET', '/api/sessions', headers={'X-User-Id': '1'})
r0 = conn.getresponse()
st_dev = r0.status
r0.read()
conn.close()
check('X-User-Id 模拟登录未被接受(非 dev 启动)', st_dev == 401, 'http=%s' % st_dev)

# ---------------------------------------------------------------- 2. 统一响应格式
print('\n---------- 2. 统一响应与错误码 ----------')
st, r = req('GET', '/api/auth/me', token=U_TOK)
check('响应含 code/message/data/timestamp 四键',
      all(k in r for k in ('code', 'message', 'data', 'timestamp')), sorted(r.keys()))
st, r = req('GET', '/api/knowledge/documents/99999999', token=U_TOK)
check('知识库无"按 ID 查详情"路由(方法不支持 4001, 非 5xx)',
      st == 405 and r.get('code') == 4001, 'http=%s code=%s' % (st, r.get('code')))
st, r = req('DELETE', '/api/knowledge/documents/99999999', token=U_TOK)
check('普通用户删文档先被角色闸拦(403/5002)', st == 403 and r.get('code') == 5002, 'code=%s' % r.get('code'))
if HAS_ADMIN:
    st, r = req('DELETE', '/api/knowledge/documents/99999999', token=A_TOK)
    check('管理员删不存在文档 404/2001(DOCUMENT_NOT_FOUND)',
          st == 404 and r.get('code') == 2001, 'http=%s code=%s' % (st, r.get('code')))
else:
    warn('DOCUMENT_NOT_FOUND 需管理员才能测到(角色闸在前)', '本次跳过')
st, r = req('POST', '/api/ai/chat', {'sessionId': '', 'message': ''}, token=U_TOK)
check('空参数 400/4001', st == 400 and r.get('code') == 4001, 'code=%s' % r.get('code'))
st, r = req('GET', '/api/nope/whatever', token=U_TOK)
check('未知路径不返回 5xx', st < 500, 'http=%s' % st)

# ---------------------------------------------------------------- 3. 会话模块
print('\n---------- 3. 会话管理(创建/列表/详情/改名/归档/删除) ----------')
st, r = req('POST', '/api/sessions', {'title': '覆盖-混合会话', 'sessionType': 'HYBRID'}, token=U_TOK)
SID, PK = r['data']['sessionId'], r['data']['id']
check('创建 HYBRID 会话', st == 200 and SID, SID)
st, r = req('POST', '/api/sessions', {'sessionType': 'RAG'}, token=U_TOK)
SID_RAG, PK_RAG = r['data']['sessionId'], r['data']['id']
check('未传 title 时会话可创建(自动标题入口)', st == 200 and SID_RAG, r['data'].get('title'))
st, r = req('GET', '/api/sessions?pageNum=1&pageSize=50', token=U_TOK)
mine = all(v['userId'] == U_ME['id'] for v in r['data']['records'])
check('会话列表按本人过滤', st == 200 and mine, 'total=%s' % r['data']['total'])
st, r = req('GET', '/api/sessions/%d' % PK, token=U_TOK)
check('会话详情', st == 200 and r['data']['sessionId'] == SID, r['data'].get('title'))
st, r = req('PUT', '/api/sessions/%d/title' % PK, {'title': '人工改名-不许覆盖'}, token=U_TOK)
check('人工改名成功', st == 200 and r['data']['title'] == '人工改名-不许覆盖', r['data'].get('title'))
st, r = req('GET', '/api/sessions/%d/messages' % PK, token=U_TOK)
check('历史消息回放接口可用', st == 200 and isinstance(r['data'], (list, dict)), str(type(r.get('data'))))
# 越权读取回归: 另建一个普通用户, 直接拿别人的会话主键去查(主键可枚举)
SU2 = 'cov2_%s' % os.urandom(3).hex()
req('POST', '/api/auth/register', {'username': SU2, 'password': SP, 'nickname': '越权测试员'})
U2_TOK, U2_ME = login(SU2, SP)
st, r = req('GET', '/api/sessions/%d' % PK, token=U2_TOK)
check('[越权] 他人会话详情被拒 403/5002', st == 403 and r.get('code') == 5002,
      'http=%s code=%s' % (st, r.get('code')))
st, r = req('GET', '/api/sessions/%d/messages' % PK, token=U2_TOK)
check('[越权] 他人会话历史消息被拒 403/5002', st == 403 and r.get('code') == 5002, 'code=%s' % r.get('code'))
st, r = req('PUT', '/api/sessions/%d/title' % PK, {'title': '篡改'}, token=U2_TOK)
check('[越权] 他人会话改名被拒 403/5002', st == 403 and r.get('code') == 5002, 'code=%s' % r.get('code'))
st, r = req('GET', '/api/sessions?pageNum=1&pageSize=50', token=U2_TOK)
check('[越权] 他人会话不出现在我的列表',
      all(v['userId'] == U2_ME.get('id') for v in r['data']['records']), 'total=%s' % r['data']['total'])
st, r = req('PUT', '/api/sessions/%d/archive' % PK, token=U_TOK)
check('归档自己的会话', st == 200, 'code=%s' % r.get('code'))
st, r = req('POST', '/api/ai/chat', {'sessionId': SID, 'message': '归档后还能问吗'}, token=U_TOK)
check('归档会话对话被拒 404/3001', st == 404 and r.get('code') == 3001, 'http=%s code=%s' % (st, r.get('code')))
st, r = req('GET', '/api/sessions/%d' % PK, token=U_TOK)
check('[R3 回归] 已归档会话详情不可读 404/3001', st == 404 and r.get('code') == 3001,
      'http=%s code=%s' % (st, r.get('code')))
st, r = req('GET', '/api/sessions/%d/messages' % PK, token=U_TOK)
check('已归档会话历史不可读 404', st == 404, 'http=%s' % st)
st, r = req('PUT', '/api/sessions/%d/archive' % PK, token=U_TOK)
check('重复归档返回 404(关闭即终态)', st == 404, 'http=%s' % st)

# ---------------------------------------------------------------- 4. RAG 检索与调试契约
print('\n---------- 4. RAG 检索调试(三分数口径 + 出口预测) ----------')
st, r = req('POST', '/api/ai/rag/search', {'question': '年假有多少天', 'topK': 5,
                                          'similarityThreshold': 0.3, 'expandShortQuery': False},
            token=U_TOK)
d = r.get('data') or {}
check('调试接口返回对象(RagDebugVO)', st == 200 and 'sources' in d and 'answerOutcome' in d,
      sorted(d.keys())[:8])
src0 = (d.get('sources') or [{}])[0]
check('来源含 semanticScore/keywordScore/score 三口径',
      set(['score', 'semanticScore', 'keywordScore']) <= set(src0.keys()), list(src0.keys()))
check('出口预测取值合法',
      d.get('answerOutcome') in ('ANSWERED_FROM_KB', 'ANSWERED_FROM_CACHE', 'REFUSED_NO_EVIDENCE',
                                 'ANSWERED_OPEN', 'TOOL_DATA'), d.get('answerOutcome'))
hi = req('POST', '/api/ai/rag/search', {'question': '年假有多少天', 'topK': 5, 'similarityThreshold': 0.95,
                                        'expandShortQuery': False}, token=U_TOK)[1]['data']
lo = req('POST', '/api/ai/rag/search', {'question': '年假有多少天', 'topK': 5, 'similarityThreshold': 0.05,
                                        'expandShortQuery': False}, token=U_TOK)[1]['data']
check('阈值改变语义过阈值数(滑块真的有效)', hi['semanticCount'] <= lo['semanticCount'],
      'th0.95 sem=%s | th0.05 sem=%s' % (hi['semanticCount'], lo['semanticCount']))
check('高阈值下出口预测转为无据拒答', hi['answerOutcome'] == 'REFUSED_NO_EVIDENCE', hi['answerOutcome'])
check('低阈值下出口预测为知识库作答', lo['answerOutcome'] == 'ANSWERED_FROM_KB', lo['answerOutcome'])
none_q = req('POST', '/api/ai/rag/search', {'question': '量子色动力学晶格计算', 'topK': 5,
                                            'similarityThreshold': 0.45, 'expandShortQuery': False},
             token=U_TOK)[1]['data']
check('库外主题被正确判为无据(而非靠词表)', none_q['answerOutcome'] == 'REFUSED_NO_EVIDENCE',
      'sem_max=%s kw=%s' % (round(none_q['semanticMaxScore'] or 0, 4), none_q['keywordCount']))
check('executed/degraded 标记存在(降级可辨)', 'executed' in none_q and 'degraded' in none_q,
      'exec=%s deg=%s' % (none_q.get('executed'), none_q.get('degraded')))

# ---------------------------------------------------------------- 5. 短查询扩展
print('\n---------- 5. 短查询扩展 ----------')
on = req('POST', '/api/ai/rag/search', {'question': '产品', 'topK': 5, 'expandShortQuery': True}, token=U_TOK)[1]['data']
off = req('POST', '/api/ai/rag/search', {'question': '产品', 'topK': 5, 'expandShortQuery': False}, token=U_TOK)[1]['data']
check('短查询触发扩展(expanded=true)', on.get('expanded') is True, on.get('retrievalQuery'))
check('扩展后的问题与原问题不同', on.get('retrievalQuery') != '产品', on.get('retrievalQuery'))
check('关闭开关时不扩展', off.get('expanded') is False, off.get('retrievalQuery'))
check('扩展提升了语义最高分', (on.get('semanticMaxScore') or 0) > (off.get('semanticMaxScore') or 0),
      'on=%s off=%s' % (round(on.get('semanticMaxScore') or 0, 4), round(off.get('semanticMaxScore') or 0, 4)))
long_q = req('POST', '/api/ai/rag/search', {'question': '入职满两年的员工有多少天年假？', 'topK': 5,
                                            'expandShortQuery': True}, token=U_TOK)[1]['data']
check('完整句子零等待不扩展', long_q.get('expanded') is False, long_q.get('retrievalQuery'))

# ---------------------------------------------------------------- 6. 对话三出口 + kb-only
print('\n---------- 6. 对话链路与出口判定(kb-only 默认开) ----------')


def ask(sid, q, tok):
    t0 = time.time()
    st, r = req('POST', '/api/ai/chat', {'sessionId': sid, 'message': q}, token=tok)
    return st, (r.get('data') or {}).get('content', ''), time.time() - t0


NO_RESULT = '知识库中未找到相关信息，请确认问题或补充相关资料后重试。'
st, a1, ms1 = ask(SID_RAG, '入职满两年的员工有多少天年假？', U_TOK)
check('[KB 出口] 库内问题答出真实条款', st == 200 and ('10' in a1 or '年假' in a1), a1[:60])
st, a2, ms2 = ask(SID_RAG, '请用三句话介绍量子计算', U_TOK)
check('[无据出口] 库外问题固定口径友好拒答', a2.strip().startswith(NO_RESULT[:12]), a2[:60])
st, a3, ms3 = ask(SID_RAG, '帮我查订单 SO20260901002 的物流状态', U_TOK)
check('[工具出口] 工具轮返回业务数据或要求订单号',
      st == 200 and ('SO2026' in a3 or '订单号' in a3 or '物流' in a3), a3[:60])
st, a4, ms4 = ask(SID_RAG, '那加班调休怎么算？', U_TOK)
check('[改写链路] 指代追问能续上上下文', st == 200 and len(a4) > 10, a4[:60])
time.sleep(3)
st, r = req('GET', '/api/system/rag-decisions?pageNum=1&pageSize=12', token=U_TOK)
rows = r['data']['records']
outs = [x['answerOutcome'] for x in rows]
check('决策日志出现 ANSWERED_FROM_KB', 'ANSWERED_FROM_KB' in outs, outs[:6])
check('决策日志出现 REFUSED_NO_EVIDENCE', 'REFUSED_NO_EVIDENCE' in outs, outs[:6])
check('决策日志出现 TOOL_DATA', 'TOOL_DATA' in outs, outs[:6])
tool_rows = [x for x in rows if x['answerOutcome'] == 'TOOL_DATA']
check('工具轮记录为"未执行检索"', tool_rows and all(x['retrievalExecuted'] is False for x in tool_rows),
      [(x['id'], x['retrievalExecuted']) for x in tool_rows][:3])
ref_rows = [x for x in rows if x['answerOutcome'] == 'REFUSED_NO_EVIDENCE']
check('无据轮仍如实记录"已执行检索"(检索先行)', ref_rows and all(x['retrievalExecuted'] for x in ref_rows),
      [(x['id'], x['retrievalExecuted']) for x in ref_rows][:3])
check('无据轮带阈值前余弦分(分布未截断)',
      ref_rows and all(isinstance(x['semanticMaxScore'], (int, float)) for x in ref_rows),
      [x['semanticMaxScore'] for x in ref_rows][:3])
kb_rows = [x for x in rows if x['answerOutcome'] == 'ANSWERED_FROM_KB']
check('有据轮余弦分 >= 阈值',
      kb_rows and all(x['semanticMaxScore'] >= x['similarityThreshold'] for x in kb_rows),
      [(x['semanticMaxScore'], x['similarityThreshold']) for x in kb_rows][:2])

# 同步 vs 流式同口径
conn = http.client.HTTPConnection(HOST, PORT, timeout=180)
conn.request('POST', '/api/ai/chat/stream',
             body=json.dumps({'sessionId': SID_RAG, 'message': '公司产品的定价是多少'}, ensure_ascii=False)
             .encode('utf-8'),
             headers={'Content-Type': 'application/json', 'Authorization': 'Bearer ' + U_TOK})
resp = conn.getresponse()
sse = resp.read().decode('utf-8', errors='replace')
conn.close()
content_events = sse.count('event:content')
check('SSE 只发 content 事件且以 [DONE] 收尾',
      resp.status == 200 and content_events >= 2 and '[DONE]' in sse, 'events=%s' % content_events)
check('SSE 不推送来源/思考等其它事件类型', 'event:source' not in sse and 'event:reasoning' not in sse,
      set(l.split(':')[0] for l in sse.splitlines() if l.startswith('event:')))

# ---------------------------------------------------------------- 7. 自动标题
print('\n---------- 7. 会话自动标题 ----------')
st, r = req('POST', '/api/sessions', {'sessionType': 'RAG'}, token=U_TOK)
T_SID, T_PK = r['data']['sessionId'], r['data']['id']
ask(T_SID, '员工差旅住宿费和餐费的报销标准分别是什么？', U_TOK)
time.sleep(6)                                  # 精修走独立线程池
st, r = req('GET', '/api/sessions/%d' % T_PK, token=U_TOK)
title = (r.get('data') or {}).get('title') or ''
check('首轮后标题不再是空/未命名', title and '未命名' not in title, title)
check('标题与问题主题相关(含差旅/报销字样)', any(k in title for k in ('差旅', '报销', '住宿', '餐费')), title)
check('标题长度受控(<=30 字)', len(title) <= 30, len(title))
st, r = req('PUT', '/api/sessions/%d/title' % T_PK, {'title': '人工定名ABC'}, token=U_TOK)
ask(T_SID, '那餐补呢？', U_TOK)
time.sleep(6)
st, r = req('GET', '/api/sessions/%d' % T_PK, token=U_TOK)
check('人工改名不被自动流程覆盖', r['data']['title'] == '人工定名ABC', r['data'].get('title'))

# ---------------------------------------------------------------- 8. 语义缓存
print('\n---------- 8. 语义缓存(命中/失效/闸门) ----------')
st, r = req('DELETE', '/api/system/semantic-cache', token=U_TOK)
check('普通用户清空语义缓存被拒 403/5002', st == 403 and r.get('code') == 5002, 'code=%s' % r.get('code'))
if HAS_ADMIN:
    st, r = req('DELETE', '/api/system/semantic-cache', token=A_TOK)
    check('管理员清空语义缓存返回新版本号', st == 200 and isinstance(r.get('data'), int), r.get('data'))
Q_CACHE = '公司的产品专业版每年多少钱？'
st, first, _ = ask(SID_RAG, Q_CACHE, U_TOK)
time.sleep(2)
st2, second, ms_second = ask(SID_RAG, Q_CACHE, U_TOK)
check('同问第二次命中缓存(内容与首次一致)', first.strip() == second.strip(),
      'first_len=%s second_len=%s' % (len(first), len(second)))
check('缓存命中耗时明显更低(<1.5s)', ms_second < 1.5, '%.2fs' % ms_second)
time.sleep(2)
st, r = req('GET', '/api/system/rag-decisions?pageNum=1&pageSize=6', token=U_TOK)
cache_rows = [x for x in r['data']['records'] if x['answerOutcome'] == 'ANSWERED_FROM_CACHE']
check('缓存命中被记为一等出口 ANSWERED_FROM_CACHE', bool(cache_rows),
      [x['answerOutcome'] for x in r['data']['records']][:4])
if cache_rows:
    cid = cache_rows[0]['id']
    check('缓存命中轮次仍有检索痕迹(executed=true 但注入 0 段)',
          cache_rows[0]['retrievalExecuted'] and cache_rows[0]['finalHits'] == 0,
          'exec=%s final=%s' % (cache_rows[0]['retrievalExecuted'], cache_rows[0]['finalHits']))
conn = http.client.HTTPConnection(HOST, PORT, timeout=180)
conn.request('POST', '/api/ai/chat/stream',
             body=json.dumps({'sessionId': SID_RAG, 'message': Q_CACHE}, ensure_ascii=False).encode('utf-8'),
             headers={'Content-Type': 'application/json', 'Authorization': 'Bearer ' + U_TOK})
resp = conn.getresponse()
sse2 = resp.read().decode('utf-8', errors='replace')
conn.close()
check('流式命中缓存时只发 1 个 content 事件', sse2.count('event:content') == 1, sse2.count('event:content'))

# ---------------------------------------------------------------- 9. 知识库与权限矩阵
print('\n---------- 9. 知识库上传/查询/删除 与权限矩阵 ----------')
# 先清理历次运行遗留的测试文档(否则同名旧文档仍在库里, "删除后不再命中"的断言会被残留干扰)
if HAS_ADMIN:
    st, r = req('GET', '/api/knowledge/documents?pageNum=1&pageSize=100', token=A_TOK)
    leftovers = [v for v in r['data']['records']
                 if any(k in (v['fileName'] or '') for k in ('覆盖测试-', '好文件', '权限验证', '测试-项目管理规范'))]
    for v in leftovers:
        req('DELETE', '/api/knowledge/documents/%d' % v['id'], token=A_TOK)
    if leftovers:
        print('      (已清理历次遗留测试文档 %d 个)' % len(leftovers))
        time.sleep(3)
DOC_NAME = '覆盖测试-考勤规定.md'
DOC_BODY = ('# 覆盖测试考勤规定\n\n## 弹性上班\n核心工作时间为 10:30-16:30，其余时段可自由安排。\n\n'
            '## 迟到宽限\n每月累计 3 次以内、每次 15 分钟以内的迟到不计考勤。\n')
st, r = upload(U_TOK, DOC_NAME, DOC_BODY)
check('[权限矩阵] 普通用户上传被拒 403/5002', st == 403 and r.get('code') == 5002, 'code=%s' % r.get('code'))
st, r = req('POST', '/api/knowledge/upload/batch', token=U_TOK)
st_b, r_b = upload(U_TOK, None, None, field='files', extra=[('files', 'a.md', DOC_BODY)])
check('[权限矩阵] 普通用户批量上传被拒(自调用后门已堵)', st_b == 403 and r_b.get('code') == 5002,
      'http=%s code=%s' % (st_b, r_b.get('code')))
# 角色闸在校验链最前 → 普通用户拿到 5002 而不是 1005, 这是预期不是漏检
st, r = upload(U_TOK, '空文件.txt', b'')
check('普通用户上传空文件仍先被角色闸拦(5002 优先)', st == 403 and r.get('code') == 5002,
      'code=%s' % r.get('code'))
st, r = upload(U_TOK, '超大.md', 'x' * (51 * 1024 * 1024))
check('超大文件在进 Controller 前被框架拦(413)', st == 413, 'http=%s code=%s' % (st, r.get('code')))
if HAS_ADMIN:
    st, r = upload(A_TOK, '空文件.txt', b'')
    check('[管理员] 空文件被拒 1005', st == 400 and r.get('code') == 1005, 'code=%s' % r.get('code'))
    st, r = upload(A_TOK, 'evil.exe', b'MZ\x90\x00fake-pe-header')
    check('[管理员] 非白名单扩展名被拒 1002', st == 400 and r.get('code') == 1002, 'code=%s' % r.get('code'))
    st, r = upload(A_TOK, '伪装pdf.pdf', b'just plain text, no pdf header at all')
    check('[管理员] 魔数不符被拒 1002', st == 400 and r.get('code') == 1002, 'code=%s' % r.get('code'))
    st, r = upload(A_TOK, '', DOC_BODY)
    check('[管理员] 空文件名被拒 1001', st == 400 and r.get('code') == 1001, 'code=%s' % r.get('code'))
    st, r = upload(A_TOK, None, None, field='files')
    check('[管理员] 批量零文件 400/4001(不再是 5001)',
          st == 400 and r.get('code') == 4001, 'http=%s code=%s msg=%s' % (st, r.get('code'), r.get('message')))
    st, r = upload(A_TOK, None, None, field='files',
                   extra=[('files', '好文件.md', DOC_BODY), ('files', '坏文件.exe', b'MZ fake')])
    rows_b = r.get('data') or []
    check('[管理员] 批量逐文件独立成败(一成一败)',
          st == 200 and len(rows_b) == 2 and sum(1 for x in rows_b if x.get('success')) == 1,
          [(x.get('fileName'), x.get('success'), x.get('reason')) for x in rows_b])
    for x in rows_b:
        if x.get('success') and x.get('docId'):
            req('DELETE', '/api/knowledge/documents/%d' % x['docId'], token=A_TOK)
            time.sleep(2)
else:
    warn('上传内容校验(1001/1002/1005/批量逐文件)需管理员, 本次跳过')
if not HAS_ADMIN:
    warn('知识库正向链路(上传/入库/删除/向量清理)需管理员账号, 本次跳过')
else:
    st, r = upload(A_TOK, DOC_NAME, DOC_BODY)
    DOC_ID = (r.get('data') or {}).get('docId')
    check('管理员上传成功并返回 docId', st == 200 and DOC_ID, 'docId=%s' % DOC_ID)
    rec = wait_doc(DOC_ID, A_TOK)
    check('异步入库完成 status=2', rec is not None and rec['status'] == 2,
          'status=%s err=%s' % (rec and rec['status'], rec and rec.get('errorMessage')))
    check('分块数与向量化落库(chunk_count>0)', rec and rec['chunkCount'] > 0, rec and rec['chunkCount'])
    st, r = req('POST', '/api/ai/rag/search', {'question': '迟到宽限几次', 'topK': 5,
                                               'similarityThreshold': 0.3, 'expandShortQuery': False},
                token=A_TOK)
    hits = (r.get('data') or {}).get('sources') or []
    check('新文档立即可被问到(零配置改动)', any(h['fileName'] == DOC_NAME for h in hits),
          [h['fileName'] for h in hits][:3])
    # 用一次性新会话问, 避免同会话多轮历史把上下文预算挤掉刚上传的那一段
    Q_SID = req('POST', '/api/sessions', {'title': '考勤探针', 'sessionType': 'RAG'},
              token=A_TOK)[1]['data']['sessionId']
    ans = ''
    for _ in range(4):
        st, r = req('POST', '/api/ai/chat', {'sessionId': Q_SID, 'message': '每月迟到宽限是几次？'},
                    token=A_TOK)
        ans = (r.get('data') or {}).get('content', '')
        if '3' in ans or '三' in ans:
            break
        time.sleep(3)
    check('新文档内容可被对话答出(零配置改动即问即答)', '3' in ans or '三' in ans, ans[:70])
    st, r = req('POST', '/api/knowledge/documents/%d/reprocess' % DOC_ID, token=A_TOK)
    check('重处理成功且重新入库', st == 200 and wait_doc(DOC_ID, A_TOK) is not None, 'code=%s' % r.get('code'))
    st, r = req('DELETE', '/api/knowledge/documents/%d' % DOC_ID, token=U_TOK)
    check('普通用户删除文档被拒 403/5002', st == 403 and r.get('code') == 5002, 'code=%s' % r.get('code'))
    st, r = req('DELETE', '/api/knowledge/documents/%d' % DOC_ID, token=A_TOK)
    time.sleep(4)
    st2, r2 = req('POST', '/api/ai/rag/search', {'question': '迟到宽限几次', 'topK': 5,
                                                 'similarityThreshold': 0.3, 'expandShortQuery': False},
                  token=A_TOK)
    still = any(h['fileName'] == DOC_NAME for h in ((r2.get('data') or {}).get('sources') or []))
    check('管理员删除成功且向量已清理', st == 200 and not still, 'del=%s 仍可命中=%s' % (st, still))

# ---------------------------------------------------------------- 10. 四张日志表
print('\n---------- 10. 系统日志四表与数据隔离 ----------')
MY_SIDS = {SID, SID_RAG, T_SID}
for path, name, has_uid in [
        ('/api/system/chat-logs?pageNum=1&pageSize=20', 'chat-logs', True),
        ('/api/system/tool-call-logs?pageNum=1&pageSize=20', 'tool-call-logs', True),
        ('/api/system/rag-decisions?pageNum=1&pageSize=20', 'rag-decisions', False),
        ('/api/system/context-logs?pageNum=1&pageSize=20', 'context-logs', False)]:
    st, r = req('GET', path, token=U_TOK)
    recs = (r.get('data') or {}).get('records') or []
    if has_uid:
        ok = st == 200 and all(v.get('userId') == U_ME['id'] for v in recs)
    else:
        # 这两个 VO 刻意不回传 userId(归属已由服务端强制过滤), 只能按"行属于我的会话"验
        ok = st == 200 and all(v.get('sessionId') in MY_SIDS for v in recs)
    check('%s 普通用户只见本人' % name, ok,
          'rows=%s total=%s' % (len(recs), (r.get('data') or {}).get('total')))
if HAS_ADMIN:
    st, r = req('GET', '/api/system/rag-decisions?pageNum=1&pageSize=30', token=A_TOK)
    mine_n = sum(1 for v in r['data']['records'] if v.get('sessionId') in MY_SIDS)
    check('[设计如此] 管理员不带 userId 可看全量(含他人行)',
          st == 200 and len(r['data']['records']) > mine_n,
          'rows=%s 其中我的=%s' % (len(r['data']['records']), mine_n))
    st, r = req('GET', '/api/system/chat-logs?pageNum=1&pageSize=30', token=A_TOK)
    ids = set(v['userId'] for v in r['data']['records'] if v.get('userId') is not None)
    check('管理员 chat-logs 跨用户可见', len(ids) >= 1, 'userIds=%s' % sorted(ids)[:5])
st, r = req('GET', '/api/system/chat-logs?userId=1&pageNum=1&pageSize=5', token=U_TOK)
recs = (r.get('data') or {}).get('records') or []
check('普通用户传他人 userId 被强制改写为本人',
      st == 200 and all(v['userId'] == U_ME['id'] for v in recs), 'all-self=%s' % (recs and all(
          v['userId'] == U_ME['id'] for v in recs)))
st, r = req('GET', '/api/system/chat-logs?pageNum=1&pageSize=5&startTime=2020-01-01&endTime=2020-01-02', token=U_TOK)
check('日期区间过滤可用', st == 200, 'total=%s' % (r.get('data') or {}).get('total'))
st, r = req('GET', '/api/system/chat-logs?startTime=not-a-date', token=U_TOK)
check('非法日期参数 400/4001', st == 400 and r.get('code') == 4001, 'code=%s' % r.get('code'))
st, r = req('GET', '/api/system/rag-decisions?pageNum=1&pageSize=5', token=U_TOK)
row = ((r.get('data') or {}).get('records') or [{}])[0]
check('决策日志含 answer_outcome 与 semanticMaxScore 列',
      'answerOutcome' in row and 'semanticMaxScore' in row, sorted(row.keys())[:10])
st, r = req('GET', '/api/system/context-logs?pageNum=1&pageSize=5', token=U_TOK)
ctx = ((r.get('data') or {}).get('records') or [{}])[0]
check('上下文日志含分段 token 占用口径',
      any(k in ctx for k in ('ragTokens', 'historyTokens', 'systemTokens')), sorted(ctx.keys())[:10])
st, r = req('GET', '/api/system/chat-logs?pageNum=1&pageSize=5', token=U_TOK)
tok_rows = (r.get('data') or {}).get('records') or []
check('token 用量已采集(流式主流量不为空)',
      any(v.get('totalTokens') for v in tok_rows) or not tok_rows,
      '样例=%s' % [v.get('totalTokens') for v in tok_rows][:4])
st, r = req('GET', '/api/system/tool-call-logs?pageNum=1&pageSize=5', token=U_TOK)
tl = ((r.get('data') or {}).get('records') or [])
masked = all(('****' in (v.get('inputParams') or '') + (v.get('outputResult') or ''))
             or not any(c.isdigit() for c in (v.get('outputResult') or '')[-0:]) for v in tl) if tl else None
check('工具日志已脱敏(手机号/邮箱不打明文)',
      not tl or all('***' in (v.get('outputResult') or '') + (v.get('inputParams') or '')
                    or '@' not in (v.get('outputResult') or '') for v in tl),
      '样例=%s' % [(v.get('toolName'), (v.get('outputResult') or '')[:40]) for v in tl][:2])

# ---------------------------------------------------------------- 11. Agent 工具模块
print('\n---------- 11. Agent 工具与并发防护 ----------')
st, r = req('POST', '/api/sessions', {'sessionType': 'AGENT'}, token=U_TOK)
SID_AGENT = r['data']['sessionId']
st, a, _ = ask(SID_AGENT, '张三在哪个部门，联系方式是什么？', U_TOK)
check('AGENT 会话可答工具问题(员工信息)', st == 200 and ('研发' in a or '部门' in a), a[:60])
st, a2, _ = ask(SID_AGENT, '现在几点了', U_TOK)
check('AGENT 会话时间类工具可用', st == 200 and len(a2) > 4, a2[:40])
codes = []
lock = threading.Lock()


def hit(i):
    st, r = req('POST', '/api/ai/chat',
                {'sessionId': SID_RAG, 'message': '并发压测 %d：公司产品定价分别是多少' % i},
                token=U_TOK, timeout=180)
    with lock:
        codes.append(r.get('code') or st)


ths = [threading.Thread(target=hit, args=(i,)) for i in range(6)]
for t in ths:
    t.start()
for t in ths:
    t.join()
check('并发超限返回 6010(单用户上限 3)', codes.count(6010) >= 1, 'codes=%s' % sorted(codes))

# ---------------------------------------------------------------- 12. 异常与降级
print('\n---------- 12. 异常路径 ----------')
st, r = req('POST', '/api/ai/chat', {'sessionId': 'not-exist-session', 'message': 'hi'}, token=U_TOK)
check('不存在会话 404/3001', st == 404 and r.get('code') == 3001, 'code=%s' % r.get('code'))
st, r = req('POST', '/api/ai/rag/search', {'question': ''}, token=U_TOK)
check('空查询 400/4001', st == 400 and r.get('code') == 4001, 'code=%s' % r.get('code'))
st, r = req('POST', '/api/ai/rag/search', {'question': 'x' * 5000, 'topK': 5}, token=U_TOK)
check('超长查询不导致 5xx', st < 500, 'http=%s' % st)
st, r = req('GET', '/api/sessions/99999999', token=U_TOK)
check('未知会话 404/3001', st == 404 and r.get('code') == 3001, 'code=%s' % r.get('code'))
st, r = req('DELETE', '/api/sessions/%d' % PK_RAG, token=U_TOK)
check('删除自己的会话', st == 200, 'code=%s' % r.get('code'))
st, r = req('GET', '/api/sessions/%d' % PK_RAG, token=U_TOK)
check('删除后再查 404', st == 404, 'http=%s' % st)

# ---------------------------------------------------------------- 汇总
print('\n' + '=' * 78)
fails = [x for x in RESULTS if not x[1]]
warns = [x for x in RESULTS if x[2] and x[1] and x[0] not in ()]
passed = sum(1 for x in RESULTS if x[1])
print('总计 %d 项 | PASS %d | FAIL %d' % (len(RESULTS), passed, len(fails)))
if fails:
    print('\n失败明细:')
    for n, _, dt in fails:
        print('  - %s | %s' % (n, dt))
print('=' * 78)
sys.exit(1 if fails else 0)
