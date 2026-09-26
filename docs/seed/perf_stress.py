# -*- coding: utf-8 -*-
"""高压测试(阶段式)：
  阶段A 缓存命中高并发: 30 用户×10 请求=300 请求(并发 30), 每请求含真实 embedding 检索
        (P3-7 检索先行)——压应用本体(Tomcat 虚拟线程/Redis/MDC/审计异步落库/embedding 外调)。
  阶段B LLM 并发: 10 用户×2 请求=20 次真实模型调用——观察上游限流与并发下延迟。
前置: 应用已启动(9090); 会产生真实 Token 成本(阶段B), 规模已控制。
"""
import http.client
import json
import os
import statistics
import threading
import time
from collections import Counter

HOST, PORT = 'localhost', 9090
results_a = []          # (latency, status) 阶段A
results_b = []          # 阶段B
lock = threading.Lock()

def new_conn():
    return http.client.HTTPConnection(HOST, PORT, timeout=180)

def req_json(method, path, payload=None, tok=None, timeout=180):
    c = http.client.HTTPConnection(HOST, PORT, timeout=timeout)
    h = {'Content-Type': 'application/json; charset=utf-8'}
    if tok:
        h['Authorization'] = 'Bearer ' + tok
    c.request(method, path,
              body=json.dumps(payload, ensure_ascii=False).encode('utf-8') if payload is not None else None,
              headers=h)
    r = c.getresponse()
    raw = r.read().decode('utf-8', 'replace')
    c.close()
    try:
        return r.status, json.loads(raw)
    except Exception:
        return r.status, {}

def login(username, password):
    st, b = req_json('POST', '/api/auth/login', {'username': username, 'password': password})
    return (b.get('data') or {}).get('token')

def chat(tok, sid, message):
    t0 = time.time()
    try:
        st, b = req_json('POST', '/api/ai/chat', {'sessionId': sid, 'message': message}, tok=tok)
        code = st
    except Exception as e:
        code = str(e)[:40]
    return time.time() - t0, code

def pct(values, p):
    vs = sorted(values)
    if not vs:
        return 0.0
    return vs[min(len(vs) - 1, max(0, round(p / 100 * len(vs)) - 1))]

def show(name, values):
    if not values:
        print('%-34s (无样本)' % name)
        return
    print('%-34s n=%-4d min=%5.2f avg=%5.2f P50=%5.2f P95=%5.2f P99=%5.2f max=%5.2f' % (
        name, len(values), min(values), statistics.mean(values), pct(values, 50),
        pct(values, 95), pct(values, 99), max(values)))

# ---------- 准备: 注册 30 个压测用户 ----------
USERS_A = 30
REQ_PER_USER = 10
print('准备 %d 个压测用户...' % USERS_A)
tokens_a = []
for uid in range(USERS_A):
    c = new_conn()
    c.request('POST', '/api/auth/register', body=json.dumps(
        {'username': 'stress_a_%d' % uid, 'password': 'stress_Pass_1', 'nickname': '压测A'}).encode(),
        headers={'Content-Type': 'application/json'})
    c.getresponse().read()
    c.close()
    tokens_a.append(login('stress_a_%d' % uid, 'stress_Pass_1'))
print('用户就绪')

# 预热: 每用户建会话 + 首轮问答(写缓存, 保证阶段A全是"命中"路径)
PRE_QUESTIONS = ['年假满两年能休几天？', '报销的发票要求是什么？', '会议室怎么预订？', '密码要求是多少位？', '差旅住宿标准是多少？']
sids_a = []
for uid, tok in enumerate(tokens_a):
    st, b = req_json('POST', '/api/sessions', {'title': '【压测A-%d】' % uid}, tok=tok)
    sid = b['data']['sessionId']
    sids_a.append(sid)
    q = PRE_QUESTIONS[uid % len(PRE_QUESTIONS)]   # 每用户预热一个固定问题(命中同一缓存键)
    chat(tok, sid, q)
    time.sleep(0.1)
print('预热完成(30 轮真实问答, 已写语义缓存/记忆)')

# ---------- 阶段A: 缓存命中高并发 300 请求 ----------
print('\n========== 阶段A: 缓存命中高并发(30 用户 × 10 请求 = 300) ==========')
errors_a = Counter()
def worker_a(uid):
    tok = tokens_a[uid]
    sid = sids_a[uid]
    q = PRE_QUESTIONS[uid % len(PRE_QUESTIONS)]
    for r in range(REQ_PER_USER):
        dt, code = chat(tok, sid, q)
        with lock:
            results_a.append((dt, code))

threads = [threading.Thread(target=worker_a, args=(u,)) for u in range(USERS_A)]
t0 = time.time()
for t in threads:
    t.start()
for t in threads:
    t.join()
wall_a = time.time() - t0

lat_a = [dt for dt, code in results_a if code == 200]
errs_a = Counter(code for dt, code in results_a if code != 200)
show('阶段A 缓存命中请求延迟', lat_a)
print('阶段A 汇总: 请求 %d, 成功 %d, 失败 %d, 墙钟 %.1fs, 吞吐 %.0f req/min' % (
    len(results_a), len(lat_a), sum(errs_a.values()), wall_a, len(lat_a) / wall_a * 60))
if errs_a:
    print('  错误分布:', dict(errs_a))

# ---------- 阶段B: LLM 并发 20 次真实模型调用 ----------
print('\n========== 阶段B: LLM 并发(10 用户 × 2 次真实模型调用) ==========')
tokens_b = []
for uid in range(10):
    c = new_conn()
    c.request('POST', '/api/auth/register', body=json.dumps(
        {'username': 'stress_b_%d' % uid, 'password': 'stress_Pass_1', 'nickname': '压测B'}).encode(),
        headers={'Content-Type': 'application/json'})
    c.getresponse().read()
    c.close()
    tokens_b.append(login('stress_b_%d' % uid, 'stress_Pass_1'))
sids_b = []
for uid, tok in enumerate(tokens_b):
    st, b = req_json('POST', '/api/sessions', {'title': '【压测B-%d】' % uid}, tok=tok)
    sids_b.append(b['data']['sessionId'])

qs_b = ['帮我写一句关于团队协作的名言', '用一句话解释什么是机器学习', '给我一个周末读书的建议',
        '推荐三种提高专注力的方法', '用一句话介绍 Spring 框架']
def worker_b(uid):
    tok = tokens_b[uid]
    sid = sids_b[uid]
    for r in range(2):
        q = qs_b[(uid * 2 + r) % len(qs_b)]
        dt, code = chat(tok, sid, q + ' (压测%d-%d)' % (uid, r))   # 加后缀避免全部命中缓存
        with lock:
            results_b.append((dt, code))

threads = [threading.Thread(target=worker_b, args=(u,)) for u in range(10)]
t0 = time.time()
for t in threads:
    t.start()
for t in threads:
    t.join()
wall_b = time.time() - t0

lat_b = [dt for dt, code in results_b if code == 200]
errs_b = Counter(code for dt, code in results_b if code != 200)
show('阶段B LLM 并发延迟', lat_b)
print('阶段B 汇总: 请求 %d, 成功 %d, 失败 %d, 墙钟 %.1fs, 吞吐 %.1f req/min' % (
    len(results_b), len(lat_b), sum(errs_b.values()), wall_b, len(lat_b) / wall_b * 60))
if errs_b:
    print('  错误分布:', dict(errs_b))

# ---------- 汇总判定 ----------
print('\n========== 高压测试判定 ==========')
ok_a = not errs_a and lat_a and pct(lat_a, 99) < 5
ok_b = not errs_b and lat_b and pct(lat_b, 99) < 60
print('  阶段A 缓存命中 300 请求零错误且 P99<5s:', 'PASS' if ok_a else 'FAIL',
      '(P99=%.2fs 错误=%d)' % (pct(lat_a, 99) if lat_a else -1, sum(errs_a.values())))
print('  阶段B LLM 并发 20 次零错误且 P99<60s:', 'PASS' if ok_b else 'FAIL',
      '(P99=%.2fs 错误=%d)' % (pct(lat_b, 99) if lat_b else -1, sum(errs_b.values())))
print('  整体:', 'PASS' if (ok_a and ok_b) else 'FAIL')
