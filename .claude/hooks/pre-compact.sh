#!/bin/bash
# PreCompact Hook: 上下文压缩前自动保存当前状态
# 防止压缩丢失关键进度信息

PROJECT_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
REQUIREMENTS_DIR="$PROJECT_ROOT/requirements"
TIMESTAMP=$(date '+%Y-%m-%d %H:%M')

# 找到最近修改的需求目录（排除 .template）
LATEST_REQ=$(find "$REQUIREMENTS_DIR" -maxdepth 1 -mindepth 1 -type d -not -name '.template' -exec stat -f '%m %N' {} \; 2>/dev/null | sort -rn | head -1 | awk '{print $2}')

if [ -z "$LATEST_REQ" ]; then
    exit 0
fi

PROCESS_FILE="$LATEST_REQ/process.txt"

# 在 process.txt 顶部插入自动保存标记
if [ -f "$PROCESS_FILE" ]; then
    CONTENT=$(cat "$PROCESS_FILE")
    echo "$TIMESTAMP | auto-save | context-compact | 上下文压缩前自动保存，请读取本文件恢复状态" > "$PROCESS_FILE"
    echo "$CONTENT" >> "$PROCESS_FILE"
else
    echo "$TIMESTAMP | auto-save | context-compact | 上下文压缩前自动保存" > "$PROCESS_FILE"
fi
