#!/usr/bin/env bash
# =====================================================================
# 智能问答 Agent 一键演示脚本(bash / Git-Bash / WSL / Linux/macOS)
# 前置：应用已启动(http://localhost:9090), 已配置 DASHSCOPE_API_KEY
# 用法: bash docs/demo.sh
# =====================================================================
set -e
BASE="${BASE:-http://localhost:9090}"
JQ="jq"
command -v jq >/dev/null 2>&1 || { echo "缺少 jq, 请安装: sudo apt install jq / brew install jq"; exit 1; }

echo "==> 1. 创建 HYBRID 会话"
SID=$(curl -s -X POST "$BASE/api/sessions" -H "Content-Type: application/json" -H "X-User-Id: 1" \
  -d '{"title":"demo"}' | jq -r .data.sessionId)
echo "    sessionId=$SID"

echo "==> 2. 上传示例知识文档(异步入库, 等待完成)"
DOC=$(curl -s -X POST "$BASE/api/knowledge/upload" -F "file=@docs/sample/员工手册示例.md" -F "userId=1")
echo "    $DOC"
DOCID=$(echo "$DOC" | jq -r .data.docId)
for i in $(seq 1 20); do
  ST=$(curl -s "$BASE/api/knowledge/documents?pageSize=1" | jq -r --argjson id "$DOCID" \
       '.data.records[] | select(.id==$id) | .status')
  [ "$ST" = "2" ] && { echo "    入库完成"; break; }
  [ "$ST" = "3" ] && { echo "    入库失败, 查看 errorMessage"; break; }
  sleep 1
done

echo "==> 3. RAG 检索调试"
curl -s -X POST "$BASE/api/ai/rag/search" -H "Content-Type: application/json" \
  -d '{"question":"年假怎么规定的？","topK":3,"similarityThreshold":0.3}' | jq .

echo "==> 4. 同步问答(知识库)"
curl -s -X POST "$BASE/api/ai/chat" -H "Content-Type: application/json" \
  -d "{\"sessionId\":\"$SID\",\"message\":\"入职满两年能休几天年假？\"}" | jq -r '.data.content'

echo "==> 5. 同步问答(Agent 工具)"
curl -s -X POST "$BASE/api/ai/chat" -H "Content-Type: application/json" \
  -d "{\"sessionId\":\"$SID\",\"message\":\"查询张三的部门和联系方式\"}" | jq -r '.data.content'

echo "==> 6. 多轮记忆(追问, 应能结合上文)"
curl -s -X POST "$BASE/api/ai/chat" -H "Content-Type: application/json" \
  -d "{\"sessionId\":\"$SID\",\"message\":\"那帮我查一下李四的邮箱\"}" | jq -r '.data.content'

echo "==> 7. 流式问答(SSE 前几行)"
curl -s -N -X POST "$BASE/api/ai/chat/stream" -H "Content-Type: application/json" \
  -d "{\"sessionId\":\"$SID\",\"message\":\"差旅住宿报销标准？\"}" | head -n 8

echo "==> 8. 会话 / 对话日志 / 工具日志"
curl -s "$BASE/api/sessions?pageSize=5" -H "X-User-Id: 1" | jq '.data.total'
curl -s "$BASE/api/system/chat-logs?sessionId=$SID" | jq '.data.total'
curl -s "$BASE/api/system/tool-call-logs" | jq '.data.total'
echo "DONE."
