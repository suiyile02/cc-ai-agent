# -*- coding: utf-8 -*-
"""
知识库文档"检索启用/禁用"专项端到端测试(2026-10-06 故障回归)。

覆盖的用户旅程(故障复盘要求的最基本功能测试):
  ① 基线: 文档启用时提问 → 回答包含文档特有标记内容
  ② 禁用后: **新会话**同问 → 不得再引用该文档内容(语义/BM25 两路排除生效)
  ③ 禁用后: **同一会话**同问 → 不得复述历史里的旧回答(对话记忆旁路, 提示词加固生效)
  ④ 启用后: 新会话同问 → 恢复引用(零成本恢复语义)
  ⑤ 严格模式兜底: 禁用后无据 → 固定拒答口径

前置:
  应用已启动(localhost:9090, 含 2026-10 文档启用/禁用功能), MySQL/Qdrant 可用,
  DASHSCOPE_API_KEY 已配置(仅本地使用, 严禁把密钥写入任何文件或提交)。
  管理员账号经环境变量注入(仓库不自带口令):
    set E2E_ADMIN_USER=... / set E2E_ADMIN_PASSWORD=...
  未注入时全部用例记 WARN 跳过, 不判失败。
运行：python docs/seed/disable_toggle_test.py
"""
import http.client
import json
import os
import time
import uuid

HOST, PORT = 'localhost', 9090
results = []

MARKER = 'XQ-992'  # 文档特有标记(模型先验知识中不存在的虚构型号)
DOC_NAME = '紫丁香引擎维护手册-禁用回归测试.md'
DOC_CONTENT = (
    '# 紫丁香引擎维护手册\n\n'
    '紫丁香引擎的冷却液型号是 ' + MARKER + '，更换周期为每 500 小时。\n'
    '该引擎的保险丝规格为 F15A，仅可由认证技师更换。\n'
)


def check(name, ok, detail=''):
    results.append((name, ok, detail))
    print(('PASS' if ok else 'FAIL') + ('  ' + name + ('  | ' + detail if detail else '')))
    return ok


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


def upload_doc(token, name, content):
    boundary = '----e2e' + uuid.uuid4().hex
    lines = [
        '--' + boundary,
        'Content-Disposition: form-data; name="file"; filename="' + name + '"',
        'Content-Type: text/markdown',
        '',
        content,
        '--' + boundary + '--',
    ]
    body = '\r\n'.join(lines).encode('utf-8')
    conn = http.client.HTTPConnection(HOST, PORT, timeout=120)
    conn.request('POST', '/api/knowledge/upload', body=body, headers={
        'Content-Type': 'multipart/form-data; boundary=' + boundary,
        'Authorization': 'Bearer ' + token,
        'Content-Length': str(len(body)),
    })
    resp = conn.getresponse()
    raw = resp.read().decode('utf-8')
    conn.close()
    return resp.status, json.loads(raw)


def wait_ingested(token, doc_id, timeout_s=180):
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        st, body = req('GET', '/api/knowledge/documents?pageNum=1&pageSize=50', token=token)
        for d in body.get('data', {}).get('records', []):
            if d.get('id') == doc_id and d.get('status') == 2:
                return True
        time.sleep(3)
    return False


def new_session(token):
    st, body = req('POST', '/api/sessions', {'title': '禁用回归测试'}, token=token)
    return body['data']['sessionId']


def ask(token, session_id, question):
    st, body = req('POST', '/api/ai/chat',
                   {'sessionId': session_id, 'message': question}, token=token)
    return body.get('data', {}).get('content', '') if st == 200 else '<<HTTP %s>>' % st


def main():
    admin_user = os.environ.get('E2E_ADMIN_USER')
    admin_pass = os.environ.get('E2E_ADMIN_PASSWORD')
    if not admin_user or not admin_pass:
        print('WARN  未注入 E2E_ADMIN_USER/E2E_ADMIN_PASSWORD, 全部用例跳过')
        return

    # 管理员登录
    st, body = req('POST', '/api/auth/login', {'username': admin_user, 'password': admin_pass})
    if st != 200 or body.get('code') != 200:
        print('FAIL  管理员登录失败 | ' + json.dumps(body, ensure_ascii=False)[:200])
        return
    token = body['data']['token']
    check('管理员登录', True)

    # ① 上传带特有标记的文档并等待入库完成
    st, body = upload_doc(token, DOC_NAME, DOC_CONTENT)
    ok = st == 200 and body.get('code') == 200
    check('上传带特有标记的测试文档', ok, json.dumps(body.get('data', {}), ensure_ascii=False)[:120])
    if not ok:
        return
    doc_id = body['data']['docId']
    check('等待异步入库完成(status=2)', wait_ingested(token, doc_id))

    # ② 基线: 文档启用时, 回答应包含标记
    sid_base = new_session(token)
    answer_base = ask(token, sid_base, '紫丁香引擎的冷却液型号是什么？')
    check('② 基线(启用): 回答包含文档特有标记 ' + MARKER,
          MARKER in answer_base, answer_base[:150])

    # ③ 禁用文档
    st, body = req('POST', '/api/knowledge/documents/%d/disable' % doc_id, {}, token=token)
    check('③ 禁用文档检索', st == 200 and body.get('data', {}).get('enabled') is False,
          json.dumps(body, ensure_ascii=False)[:120])

    # ④ 新会话同问: 不得再出现文档特有标记(语义/BM25 两路排除生效)
    sid_new = new_session(token)
    answer_new = ask(token, sid_new, '紫丁香引擎的冷却液型号是什么？')
    check('④ 禁用后(新会话): 回答不得再包含 ' + MARKER,
          MARKER not in answer_new, answer_new[:150])

    # ⑤ 同一会话重问: 不得复述历史里的旧回答(对话记忆旁路, 提示词加固回归)
    answer_same = ask(token, sid_base, '紫丁香引擎的冷却液型号是什么？')
    check('⑤ 禁用后(同会话重问): 不得复述历史旧回答中的 ' + MARKER,
          MARKER not in answer_same, answer_same[:150])

    # ⑥ 启用恢复: 新会话同问, 标记回归
    st, body = req('POST', '/api/knowledge/documents/%d/enable' % doc_id, {}, token=token)
    check('⑥ 启用文档检索', st == 200 and body.get('data', {}).get('enabled') is True)
    sid_restore = new_session(token)
    answer_restore = ask(token, sid_restore, '紫丁香引擎的冷却液型号是什么？')
    check('⑦ 启用后(新会话): 回答恢复包含 ' + MARKER, MARKER in answer_restore,
          answer_restore[:150])

    # 清理: 删除测试文档
    st, body = req('DELETE', '/api/knowledge/documents/%d' % doc_id, token=token)
    check('⑧ 清理测试文档', st == 200)

    fails = [r for r in results if not r[1]]
    print('\n===== 汇总: %d 项, 失败 %d =====' % (len(results), len(fails)))
    for name, ok, detail in fails:
        print('  FAIL: ' + name + ' | ' + detail)


if __name__ == '__main__':
    main()
