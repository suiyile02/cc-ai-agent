#!/usr/bin/env bash
# =====================================================================
# 智能问答 Agent 一键演示脚本(bash / Git-Bash / WSL / Linux/macOS)
# 前置：应用已启动(http://localhost:9090), 已配置大模型 API Key
# 演示登录: 默认管理员 admin/admin123(首次启动自动播种)
# 用法: bash docs/demo.sh [username] [password]
# =====================================================================
set -e
BASE="${BASE:-http://localhost:9090}"
USERNAME="${1:-admin}"
PASSWORD="${2:-admin123}"

command -v jq >/dev/null 2>&1 || { echo "缺少 jq, 请安装: sudo apt install jq / brew install jq"; exit 1; }

echo "==> 0. 登录获取 Token(不存在则自动注册)"
LOGIN=$(curl -s -X POST "$BASE/api/auth/login" -H "Content-Type: application/json" \
  -d "{\"username\":\"$USERNAME\",\"password\":\"$PASSWORD\"}")
TOKEN=$(echo "$LOGIN" | jq -r '.data.token // empty')
if [ -z "$TOKEN" ] || [ "$TOKEN" = "null" ]; then
  echo "    登录失败, 尝试注册: $USERNAME"
  curl -s -X POST "$BASE/api/auth/register" -H "Content-Type: application/json" \
    -d "{\"username\":\"$USERNAME\",\"password\":\"$PASSWORD\",\"nickname\":\"演示用户\"}" | jq .
  TOKEN=$(curl -s -X POST "$BASE/api/auth/login" -H "Content-Type: application/json" \
    -d "{\"username\":\"$USERNAME\",\"password\":\"$PASSWORD\"}" | jq -r .data.token)
fi
AUTH="Authorization: Bearer $TOKEN"
echo "    token 已获取(前16位): ${TOKEN:0:16}..."

echo "==> 1. 创建 HYBRID 会话"
SID=$(curl -s -X POST "$BASE/api/sessions" -H "Content-Type: application/json" -H "$AUTH" \
  -d '{"title":"demo"}' | jq -r .data.sessionId)
echo "    sessionId=$SID"

echo "==> 2. 上传示例知识文档(异步入库, 等待完成; 上传人=当前登录用户)"
DOC=$(curl -s -X POST "$BASE/api/knowledge/upload" -H "$AUTH" \
  -F "file=@docs/sample/员工手册示例.md")
echo "    $DOC"
DOCID=$(echo "$DOC" | jq -r .data.docId)
for i in $(seq 1 20); do
  ST=$(curl -s "$BASE/api/knowledge/documents?pageSize=1" -H "$AUTH" | jq -r --argjson id "$DOCID" \
       '.data.records[] | select(.id==$id) | .status')
  [ "$ST" = "2" ] && { echo "    入库完成"; break; }
  [ "$ST" = "3" ] && { echo "    入库失败, 查看 errorMessage"; break; }
  sleep 1
done

echo "==> 3. RAG 检索调试"
curl -s -X POST "$BASE/api/ai/rag/search" -H "Content-Type: application/json" -H "$AUTH" \
  -d '{"question":"年假怎么规定的？","topK":3,"similarityThreshold":0.3}' | jq .

echo "==> 4. 同步问答(知识库)"
curl -s -X POST "$BASE/api/ai/chat" -H "Content-Type: application/json" -H "$AUTH" \
  -d "{\"sessionId\":\"$SID\",\"message\":\"入职满两年能休几天年假？\"}" | jq -r '.data.content'

echo "==> 5. 同步问答(Agent 工具)"
curl -s -X POST "$BASE/api/ai/chat" -H "Content-Type: application/json" -H "$AUTH" \
  -d "{\"sessionId\":\"$SID\",\"message\":\"查询张三的部门和联系方式\"}" | jq -r '.data.content'

echo "==> 6. 多轮记忆(追问, 应能结合上文)"
curl -s -X POST "$BASE/api/ai/chat" -H "Content-Type: application/json" -H "$AUTH" \
  -d "{\"sessionId\":\"$SID\",\"message\":\"那帮我查一下李四的邮箱\"}" | jq -r '.data.content'

echo "==> 6.1 多轮指代消解(上下文管线会把“他的”改写为具体实体再检索)"
curl -s -X POST "$BASE/api/ai/chat" -H "Content-Type: application/json" -H "$AUTH" \
  -d "{\"sessionId\":\"$SID\",\"message\":\"入职满一年能休几天年假？\"}" | jq -r '.data.content'
curl -s -X POST "$BASE/api/ai/chat" -H "Content-Type: application/json" -H "$AUTH" \
  -d "{\"sessionId\":\"$SID\",\"message\":\"那满两年呢？\"}" | jq -r '.data.content'

echo "==> 7. 流式问答(SSE 前几行)"
curl -s -N -X POST "$BASE/api/ai/chat/stream" -H "Content-Type: application/json" -H "$AUTH" \
  -d "{\"sessionId\":\"$SID\",\"message\":\"差旅住宿报销标准？\"}" | head -n 8

echo "==> 8. 会话 / 对话日志 / 工具日志 / 上下文装配日志"
curl -s "$BASE/api/sessions?pageSize=5" -H "$AUTH" | jq '.data.total'
curl -s "$BASE/api/system/chat-logs?sessionId=$SID" -H "$AUTH" | jq '.data.total'
curl -s "$BASE/api/system/tool-call-logs" -H "$AUTH" | jq '.data.total'
echo "    上下文装配日志(各段 Token 占用/是否截断/改写结果):"
curl -s "$BASE/api/system/context-logs?sessionId=$SID&pageSize=3" -H "$AUTH" \
  | jq '.data.records[] | {intentMode, rewrittenQuery, totalTokens, ragTokens, historyTokens, truncated}'
echo "DONE."
