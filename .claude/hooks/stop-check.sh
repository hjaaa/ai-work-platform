#!/bin/bash
# Stop Hook: 每次 Agent 响应结束时执行
# 功能：检查是否有未记录的 notes，提醒沉淀

PROJECT_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
REQUIREMENTS_DIR="$PROJECT_ROOT/requirements"

# 找到最近修改的需求目录
LATEST_REQ=$(find "$REQUIREMENTS_DIR" -maxdepth 1 -mindepth 1 -type d -not -name '.template' -exec stat -f '%m %N' {} \; 2>/dev/null | sort -rn | head -1 | awk '{print $2}')

if [ -z "$LATEST_REQ" ]; then
    exit 0
fi

NOTES_FILE="$LATEST_REQ/notes.md"
META_FILE="$LATEST_REQ/meta.yaml"

# 检查 meta.yaml 中 status 是否为 completed
if [ -f "$META_FILE" ]; then
    STATUS=$(grep 'status:' "$META_FILE" | head -1 | awk '{print $2}' | tr -d '"')
    if [ "$STATUS" = "completed" ]; then
        # 需求已完成，检查 notes.md 是否有未沉淀的内容
        if [ -f "$NOTES_FILE" ]; then
            # 检查 notes 中是否有实际内容（非注释、非空行）
            CONTENT_LINES=$(grep -v '^#\|^>\|^$\|^<!--\|^-->' "$NOTES_FILE" | grep -cv '^\s*$')
            if [ "$CONTENT_LINES" -gt 0 ]; then
                echo "提醒：需求已完成，notes.md 中有 ${CONTENT_LINES} 行未沉淀的经验。建议执行 /optimize-flow 进行知识沉淀。"
            fi
        fi
    fi
fi
