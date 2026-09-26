# -*- coding: utf-8 -*-
"""性能验收测试：单请求延迟基线 / 语义缓存收益 / 并发吞吐与错误率。
前置: 应用已启动(9090), LLM 端点可用(会产生真实模型调用与 Token 成本, 规模已控制在 ~40 次调用内)。
并发设计: 注册多个测试用户, 每用户并发 ≤2(低于单用户并发上限 3, 避免把限流当错误测)。
"""
import http.client
import json
import statistics
import threading
import time

HOST, PORT = 'localhost', 9090

def call(m, p, payload=None, tok=None, timeout=180):
    t0 = time.time()
    c = http.client.HTTPConnection(HOST, PORT, timeout=timeout)
    h = {'Content-Type': 'application/json; charset=utf-8'}
    if tok:
        h['Authorization'] = 'Bearer ' + tok
    c.request(m, p, body=json.dumps(payload, ensure_ascii=False).encode('utf-8') if payload is not None else None,
              headers=h)
    r = c.getresponse()
    r.read()
    c.close()
    return time.time() - t0, r.status

def pct(values, p):
    vs = sorted(values)
    if not vs:
        return 0
    idx = min(len(vs) - 1, max(0, round(p / 100 * len(vs)) - 1))
    return vs[idx]

def show(name, values):
    if not values:
        print('%-28s (无样本)' % name)
        return
    print('%-28s n=%-3d min=%6.2fs avg=%6.2fs P50=%6.2fs P95=%6.2fs P99=%6.2fs max=%6.2fs' % (
        name, len(values), min(values), statistics.mean(values),
        pct(values, 50), pct(values, 95), pct(values, 99), max(values)))

# 登录(主测试用户)
c = http.client.HTTPConnection(HOST, PORT, timeout=60)
c.request('POST', '/api/auth/login', body=json.dumps({'username': 'tester', 'password': 'test123'}).encode(),
          headers={'Content-Type': 'application/json'})
tok = json.loads(c.getresponse().read().decode())['data']['token']
c.close()

print('========== 1. 单请求延迟基线(串行) ==========')
kb_lat, general_lat, tool_lat = [], [], []
kb_q = '年假满两年能休几天？'
c = http.client.HTTPConnection(HOST, PORT, timeout=180)
c.request('POST', '/api/sessions', body=json.dumps({'title': '【性能验收-KB】'}, ensure_ascii=False).encode('utf-8'),
          headers={'Content-Type': 'application/json', 'Authorization': 'Bearer ' + tok})
SID_KB = json.loads(c.getresponse().read().decode())['data']['sessionId']
c.close()
for i in range(3):
    dt, st = call('POST', '/api/ai/chat', {'sessionId': SID_KB, 'message': '入职满三年的员工有哪些福利待遇？'}, tok=tok)
    if st == 200:
        kb_lat.append(dt)
dt, st = call('POST', '/api/ai/chat', {'sessionId': SID_KB, 'message': kb_q}, tok=tok)
if st == 200:
    kb_lat.append(dt)
show('KB 问答(检索+模型)', kb_lat)

c = http.client.HTTPConnection(HOST, PORT, timeout=180)
c.request('POST', '/api/sessions', body=json.dumps({'title': '【性能验收-闲聊】'}, ensure_ascii=False).encode('utf-8'),
          headers={'Content-Type': 'application/json', 'Authorization': 'Bearer ' + tok})
SID_GEN = json.loads(c.getresponse().read().decode())['data']['sessionId']
c.close()
for i in range(3):
    dt, st = call('POST', '/api/ai/chat', {'sessionId': SID_GEN, 'message': '帮我写一句鼓励学习的话'}, tok=tok)
    if st == 200:
        general_lat.append(dt)
show('GENERAL 自由问答(无检索)', general_lat)

c = http.client.HTTPConnection(HOST, PORT, timeout=180)
c.request('POST', '/api/sessions', body=json.dumps({'title': '【性能验收-工具】'}, ensure_ascii=False).encode('utf-8'),
          headers={'Content-Type': 'application/json', 'Authorization': 'Bearer ' + tok})
SID_TOOL = json.loads(c.getresponse().read().decode())['data']['sessionId']
c.close()
for i in range(2):
    dt, st = call('POST', '/api/ai/chat', {'sessionId': SID_TOOL, 'message': '查一下订单 SO20260101001 的状态'}, tok=tok)
    if st == 200:
        tool_lat.append(dt)
show('工具问答(检索跳过+调工具)', tool_lat)

print('========== 2. 语义缓存收益(同问题 首问 vs 命中) ==========')
c = http.client.HTTPConnection(HOST, PORT, timeout=180)
c.request('POST', '/api/sessions', body=json.dumps({'title': '【性能验收-缓存】'}, ensure_ascii=False).encode('utf-8'),
          headers={'Content-Type': 'application/json', 'Authorization': 'Bearer ' + tok})
SID_CACHE = json.loads(c.getresponse().read().decode())['data']['sessionId']
c.close()
q = '报销的发票要求是什么？'
miss_lat, hit_lat = [], []
for i in range(3):
    dt, st = call('POST', '/api/ai/chat', {'sessionId': SID_CACHE, 'message': q if i == 0 else q}, tok=tok)
    (miss_lat if i == 0 else hit_lat).append(dt)
# 再问两次同问题取命中样本
for i in range(2):
    dt, st = call('POST', '/api/ai/chat', {'sessionId': SID_CACHE, 'message': q}, tok=tok)
    hit_lat.append(dt)
show('缓存未命中(首次, 检索+模型)', miss_lat)
show('缓存命中(后续同问题)', hit_lat)
if miss_lat and hit_lat:
    gain = statistics.mean(miss_lat) / max(0.01, statistics.mean(hit_lat))
    print('%-28s 平均加速 %.1f 倍' % ('缓存收益', gain))

print('========== 3. 并发吞吐(10 用户 × 3 轮) ==========')
USERS = 10
ROUNDS = 3
results = []
lock = threading.Lock()

def worker(uid):
    c = http.client.HTTPConnection(HOST, PORT, timeout=180)
    c.request('POST', '/api/auth/login', body=json.dumps(
        {'username': 'perf_user_%d' % uid, 'password': 'perf_Pass_123', 'nickname': '压测'}).encode(),
        headers={'Content-Type': 'application/json'})
    try:
        tok_u = json.loads(c.getresponse().read().decode())['data']['token']
    except Exception:
        with lock:
            results.append((999, 'login-fail'))
        c.close()
        return
    c.close()
    for r in range(ROUNDS):
        c = http.client.HTTPConnection(HOST, PORT, timeout=180)
        qs = ['帮我查一下订单 SO20260101001 的状态', '地球为什么是圆的？', '报销的发票要求是什么？']
        payload = {'sessionId': sids[uid % len(sids)], 'message': qs[(uid + r) % len(qs)], '_': r}
        t0 = time.time()
        try:
            c.request('POST', '/api/ai/chat', body=json.dumps({k: v for k, v in payload.items() if k != '_'},
                      ensure_ascii=False).encode('utf-8'),
                      headers={'Content-Type': 'application/json', 'Authorization': 'Bearer ' + tok_u})
            resp = c.getresponse()
            resp.read()
            code = resp.status
        except Exception:
            code = 0
        dt = time.time() - t0
        c.close()
        with lock:
            results.append((dt, code))

# 先注册 10 个压测用户并各建一个会话
sids = []
for uid in range(USERS):
    c = http.client.HTTPConnection(HOST, PORT, timeout=60)
    c.request('POST', '/api/auth/register', body=json.dumps(
        {'username': 'perf_user_%d' % uid, 'password': 'perf_Pass_123', 'nickname': '压测'}).encode(),
        headers={'Content-Type': 'application/json'})
    c.getresponse().read()
    c.close()
    c = http.client.HTTPConnection(HOST, PORT, timeout=60)
    c.request('POST', '/api/auth/login', body=json.dumps(
        {'username': 'perf_user_%d' % uid, 'password': 'perf_Pass_123'}).encode(),
        headers={'Content-Type': 'application/json'})
    tok_u = json.loads(c.getresponse().read().decode())['data']['token']
    c.close()
    c = http.client.HTTPConnection(HOST, PORT, timeout=60)
    c.request('POST', '/api/sessions', body=json.dumps({'title': '【压测%d】' % uid}, ensure_ascii=False).encode('utf-8'),
              headers={'Content-Type': 'application/json', 'Authorization': 'Bearer ' + tok_u})
    sids.append(json.loads(c.getresponse().read().decode())['data']['sessionId'])
    c.close()

threads = [threading.Thread(target=worker, args=(u,)) for u in range(USERS)]
t0 = time.time()
for t in threads:
    t.start()
for t in threads:
    t.join()
wall = time.time() - t0

lat = [dt for dt, code in results if code == 200]
errs = [(code) for dt, code in results if code != 200]
show('并发问答延迟(混合出口)', lat)
print('%-28s 并发=%d 用户×%d 轮, 总请求 %d, 成功 %d, 失败 %d, 墙钟 %.1fs, 吞吐 %.1f req/min' % (
    '并发汇总', USERS, ROUNDS, len(results), len(lat), len(errs), wall, len(lat) / wall * 60))
if errs:
    from collections import Counter
    print('  错误分布:', dict(Counter(errs)))

print('\n========== 性能验收判定(建议阈值) ==========')
ok1 = kb_lat and pct(kb_lat, 95) < 20
ok2 = not hit_lat or pct(hit_lat, 95) < 2
ok3 = not errs
ok4 = miss_lat and hit_lat and statistics.mean(hit_lat) < statistics.mean(miss_lat)
print('  KB 问答 P95 < 20s(模型主导):', 'PASS' if ok1 else 'FAIL')
print('  缓存命中 P95 < 2s:', 'PASS' if ok2 else 'FAIL')
print('  并发零错误(10用户×3轮):', 'PASS' if ok3 else 'FAIL')
print('  缓存命中快于未命中:', 'PASS' if ok4 else 'FAIL')
print('  整体:', 'PASS' if all([ok1, ok2, ok3, ok4]) else 'FAIL')
