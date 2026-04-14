#!/bin/bash
# Stop Hook: 每次 Agent 响应结束时执行
# 功能1：需求完成后，检查 notes.md 是否有未沉淀的内容
# 功能2：检测试错路径（process.txt 的 "What Did NOT Work"），提前提醒沉淀
# 功能3：检测复杂任务（本轮修改文件数量多），提醒记录成功路径

PROJECT_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
REQUIREMENTS_DIR="$PROJECT_ROOT/requirements"

# 找到最近修改的需求目录
LATEST_REQ=$(find "$REQUIREMENTS_DIR" -maxdepth 1 -mindepth 1 -type d -not -name '.template' -exec stat -f '%m %N' {} \; 2>/dev/null | sort -rn | head -1 | awk '{print $2}')

if [ -z "$LATEST_REQ" ]; then
    exit 0
fi

NOTES_FILE="$LATEST_REQ/notes.md"
META_FILE="$LATEST_REQ/meta.yaml"
PROCESS_FILE="$LATEST_REQ/process.txt"

# ── 功能1：需求已完成 + notes 有内容 ──────────────────────────────────────
if [ -f "$META_FILE" ]; then
    STATUS=$(grep 'status:' "$META_FILE" | head -1 | awk '{print $2}' | tr -d '"')
    if [ "$STATUS" = "completed" ]; then
        if [ -f "$NOTES_FILE" ]; then
            CONTENT_LINES=$(grep -v '^#\|^>\|^$\|^<!--\|^-->' "$NOTES_FILE" | grep -cv '^\s*$')
            if [ "$CONTENT_LINES" -gt 0 ]; then
                echo "💡 提醒：需求已完成，notes.md 中有 ${CONTENT_LINES} 行经验待沉淀。建议执行 /optimize-flow。"
            fi
        fi
    fi
fi

# ── 功能2：process.txt 有试错记录（不管任务是否完成）────────────────────────
# 捕获 "What Did NOT Work" 到下一个 "##" 区域之间的非空、非占位行
if [ -f "$PROCESS_FILE" ]; then
    DEAD_END_LINES=$(awk '/## What Did NOT Work/{found=1; next} found && /^## /{exit} found' "$PROCESS_FILE" \
        | grep -v '^\s*$\|暂无\|^#\|^-\s*(\|^-\s*$' | wc -l | tr -d ' ')
    if [ "$DEAD_END_LINES" -gt 0 ]; then
        REQ_NAME=$(basename "$LATEST_REQ")
        echo "🚧 检测到试错记录（$REQ_NAME/process.txt 有 ${DEAD_END_LINES} 条 'What Did NOT Work'）。"
        echo "   这些走不通的路应沉淀为 dead-ends.md 并加入 index.md，防止下次重蹈覆辙。建议执行 /optimize-flow。"
    fi
fi

# ── 功能3：本轮修改文件数量多 → 复杂任务，提醒记录成功路径 ─────────────────
# 统计 10 分钟内修改的 Java/Vue/SQL 文件数（即本轮 Agent 改动量）
RECENT_FILES=$(find "$PROJECT_ROOT" -path "*/.git" -prune -o \
    \( -name "*.java" -o -name "*.vue" -o -name "*.sql" \) \
    -mmin -10 -print 2>/dev/null | wc -l | tr -d ' ')
if [ "$RECENT_FILES" -ge 5 ]; then
    echo "📦 检测到本轮修改了 ${RECENT_FILES} 个文件（复杂任务）。"
    echo "   如果路径中有非显然的技术决策或转折，建议执行 /optimize-flow 记录成功路径。"
fi
