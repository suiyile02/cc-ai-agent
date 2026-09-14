# -*- coding: utf-8 -*-
"""
会话历史滚动摘要端到端测试(异步版)。
摘要由每轮对话写回后异步触发, 因此跨过阈值后需轮询 context_log 等待摘要注入再验证召回。
建议以低阈值参数启动应用(默认阈值 20 条消息, 短测试触不到):
  java -jar target/ai-agent-0.0.1-SNAPSHOT.jar \
    --app.context.summary.trigger-messages=4 --app.context.summary.keep-recent-messages=2
运行: python docs/seed/e2e_summary_test.py
"""
import http.client
import json
import sys
import time

results = []


def check(name, ok, detail=''):
    results.append(ok)
    print(('PASS' if ok else 'FAIL') + ('  ' + name + ('  | ' + detail if detail else '')))


def post(path, payload, token=None, timeout=120):
    conn = http.client.HTTPConnection('localhost', 9090, timeout=timeout)
    headers = {'Content-Type': 'application/json; charset=utf-8'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    conn.request('POST', path, body=json.dumps(payload, ensure_ascii=False).encode('utf-8'), headers=headers)
    resp = json.loads(conn.getresponse().read().decode('utf-8'))
    conn.close()
    return resp


def get(path, token):
    conn = http.client.HTTPConnection('localhost', 9090, timeout=30)
    conn.request('GET', path, headers={'Authorization': 'Bearer ' + token})
    resp = json.loads(conn.getresponse().read().decode('utf-8'))
    conn.close()
    return resp


token = post('/api/auth/login', {'username': 'tester', 'password': 'test123'})['data']['token']
sid = post('/api/sessions', {'title': '【测试】滚动摘要验证', 'sessionType': 'RAG'},
           token=token)['data']['sessionId']
print('sessionId =', sid)


def chat(message):
    r = post('/api/ai/chat', {'sessionId': sid, 'message': message}, token=token)
    content = r['data']['content'] if r.get('data') else ''
    print(f'--- 用户: {message}')
    print(f'    助手: {content[:70].replace(chr(10), " ")}')
    return content


def latest_summary_tokens():
    r = get(f'/api/system/context-logs?sessionId={sid}&pageSize=1', token=token)
    rows = r['data']['records'] if r.get('data') else []
    return rows[0]['summaryTokens'] if rows else 0


print('\n== 阶段一: 写入需长期记忆的事实(2 轮) ==')
chat('请务必记住两个信息：第一，我的工号是 8848；第二，我的部门是质检部。')
chat('再记住一条：我的直属领导是王五。')

print('\n== 阶段二: 第 3 轮跨过阈值(6 条 > 4), 写回后触发后台异步摘要 ==')
chat('简单说一句今天的工作安排。')

print('\n== 阶段三: 轮询等待后台摘要就绪并注入(每次"继续"都会重新装配) ==')
ready = False
for i in range(8):
    time.sleep(8)
    tokens = latest_summary_tokens()
    print(f'    第 {i + 1} 次轮询: 最新 context_log summaryTokens={tokens}')
    if tokens > 0:
        ready = True
        break
    chat('继续。')
check('后台摘要生成并注入装配(轮询到 summaryTokens>0)', ready, f'attempts={i + 1}')

print('\n== 阶段四: 验证摘要化后早期信息仍可召回 ==')
a1 = chat('我的工号是多少？')
a2 = chat('我的直属领导是谁？')
check('摘要化后可召回工号 8848', '8848' in a1, f'回答: {a1[:60]}')
check('摘要化后可召回领导王五', '王五' in a2, f'回答: {a2[:60]}')

print('\n== 阶段五: 再触发二次摘要(合并既有摘要与新增历史) ==')
chat('随便说一句鼓励我的话。')
ready2 = False
for i in range(8):
    time.sleep(8)
    rows = get(f'/api/system/context-logs?sessionId={sid}&pageSize=1', token=token)['data']['records']
    tokens = rows[0]['summaryTokens'] if rows else 0
    if tokens > 0 and tokens != latest_summary_tokens():
        ready2 = True
        break
a3 = chat('我的工号和部门分别是什么？')
check('二次摘要合并后仍可召回两项事实', '8848' in a3 and '质检部' in a3, f'回答: {a3[:70]}')

r = get(f'/api/system/context-logs?sessionId={sid}&pageSize=5', token=token)
rows = r['data']['records'] if r.get('data') else []
summary_rows = [v for v in rows if v['summaryTokens'] > 0]
check('context_log 审计摘要注入', len(summary_rows) >= 1,
      f'最近 5 条中 {len(summary_rows)} 条 summaryTokens>0')

print('\nSESSION_ID_FOR_DB_CHECK =', sid)
print('核对 SQL:')
print(f"  SELECT LEFT(summary_text,300), summarized_until FROM conversation_summary WHERE conversation_id='{sid}';")
print(f"  SELECT COUNT(*) FROM SPRING_AI_CHAT_MEMORY WHERE conversation_id='{sid}';")

passed = sum(results)
print(f'\n汇总: {passed}/{len(results)} PASS')
sys.exit(0 if passed == len(results) else 1)
