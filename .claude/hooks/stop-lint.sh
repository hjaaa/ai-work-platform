#!/bin/bash
# Stop Lint Hook: 每次 Agent 响应结束时，批量检查本次修改的 Java/XML 文件
# 设计参考：OpenAI Harness Engineering — "错误信息中注入修复指令"
# 性能参考：ECC — 不逐次检查，Stop 时批量处理

PROJECT_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

# 多模块项目：扫描所有 platform-*/src 目录
JAVA_FILES=$(find "$PROJECT_ROOT" -path "*/platform-*/src" -name "*.java" -mmin -10 2>/dev/null)
XML_FILES=$(find "$PROJECT_ROOT" -path "*/platform-*/src" -name "*.xml" -mmin -10 2>/dev/null)

if [ -z "$JAVA_FILES" ] && [ -z "$XML_FILES" ]; then
    # 兼容单模块项目
    if [ -d "$PROJECT_ROOT/src" ]; then
        JAVA_FILES=$(find "$PROJECT_ROOT/src" -name "*.java" -mmin -10 2>/dev/null)
        XML_FILES=$(find "$PROJECT_ROOT/src" -name "*.xml" -mmin -10 2>/dev/null)
    fi
fi

if [ -z "$JAVA_FILES" ] && [ -z "$XML_FILES" ]; then
    exit 0
fi

VIOLATIONS=""

# ============================================================
# L001: 禁止 @Autowired
# ============================================================
if [ -n "$JAVA_FILES" ]; then
    RESULT=$(echo "$JAVA_FILES" | xargs grep -ln "@Autowired" 2>/dev/null)
    if [ -n "$RESULT" ]; then
        VIOLATIONS="${VIOLATIONS}
❌ [L001 ERROR] 禁止 @Autowired
   文件: $(echo "$RESULT" | tr '\n' ', ')
   修复: 删除 @Autowired，改为构造器注入（private final 字段 + @RequiredArgsConstructor）。参考 context/rules/pattern-rules.md §1"
    fi
fi

# ============================================================
# L002: 禁止 select *
# ============================================================
if [ -n "$XML_FILES" ]; then
    RESULT=$(echo "$XML_FILES" | xargs grep -lin "select \*\|SELECT \*" 2>/dev/null)
    if [ -n "$RESULT" ]; then
        VIOLATIONS="${VIOLATIONS}
❌ [L002 ERROR] 禁止 select *
   文件: $(echo "$RESULT" | tr '\n' ', ')
   修复: 将 select * 替换为显式列名，从对应 Entity 类中找到所有字段逐个列出。"
    fi
fi
if [ -n "$JAVA_FILES" ]; then
    RESULT=$(echo "$JAVA_FILES" | xargs grep -ln "select \*\|SELECT \*" 2>/dev/null)
    if [ -n "$RESULT" ]; then
        VIOLATIONS="${VIOLATIONS}
❌ [L002 ERROR] 禁止 select *（Java 文件中）
   文件: $(echo "$RESULT" | tr '\n' ', ')
   修复: 将 select * 替换为显式列名。"
    fi
fi

# ============================================================
# L004: 日志禁止打印敏感信息
# ============================================================
if [ -n "$JAVA_FILES" ]; then
    RESULT=$(echo "$JAVA_FILES" | xargs grep -ln 'log\.\(info\|warn\|error\|debug\).*\(password\|token\|secret\|idCard\|bankCard\)' 2>/dev/null)
    if [ -n "$RESULT" ]; then
        VIOLATIONS="${VIOLATIONS}
❌ [L004 ERROR] 日志中包含敏感信息
   文件: $(echo "$RESULT" | tr '\n' ', ')
   修复: 删除敏感字段的日志打印，或使用脱敏方法（手机号 138****1234，身份证 3201****1234）。参考 context/team/logging-standards.md"
    fi
fi

# ============================================================
# L005: 禁止空 catch 块（吞异常）
# ============================================================
if [ -n "$JAVA_FILES" ]; then
    RESULT=$(echo "$JAVA_FILES" | xargs grep -ln 'catch.*{[[:space:]]*}' 2>/dev/null)
    if [ -n "$RESULT" ]; then
        VIOLATIONS="${VIOLATIONS}
⚠️ [L005 WARN] 发现空 catch 块（吞异常）
   文件: $(echo "$RESULT" | tr '\n' ', ')
   修复: catch 块必须 (1) 重新抛出业务异常，或 (2) 记录日志并返回明确错误。参考 context/team/coding-standards.md §异常处理"
    fi
fi

# ============================================================
# 输出结果
# ============================================================
if [ -n "$VIOLATIONS" ]; then
    CHECKED_COUNT=0
    [ -n "$JAVA_FILES" ] && CHECKED_COUNT=$((CHECKED_COUNT + $(echo "$JAVA_FILES" | wc -l)))
    [ -n "$XML_FILES" ] && CHECKED_COUNT=$((CHECKED_COUNT + $(echo "$XML_FILES" | wc -l)))
    echo "🔍 Lint 检查（已检查 ${CHECKED_COUNT} 个最近修改的文件）："
    echo "$VIOLATIONS"
fi
