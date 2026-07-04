#!/usr/bin/env python3
"""Check Maven pom.xml files for duplicate <properties> declarations.

Maven silently lets a later property declaration override an earlier one in the
same <properties> block, which hides version drift (e.g. two spring-boot.version
lines). The Maven Enforcer plugin has no built-in rule for this, so this script
covers that gap.

Usage:
    python3 scripts/check-pom-duplicate-properties.py [pom.xml ...]

Without arguments it checks every pom.xml tracked by git (excluding target/).
Exit code 0 = clean, 1 = duplicates found.
"""
import subprocess
import sys
import xml.etree.ElementTree as ET


def find_poms():
    out = subprocess.run(
        ["git", "ls-files", "*pom.xml", "**/pom.xml"],
        capture_output=True, text=True, check=True,
    ).stdout.split()
    return sorted(set(out))


def local_name(tag):
    return tag.rsplit("}", 1)[-1]


def check(pom_path):
    tree = ET.parse(pom_path)
    root = tree.getroot()
    errors = []
    for props in root.iter():
        if local_name(props.tag) != "properties":
            continue
        seen = {}
        for child in props:
            name = local_name(child.tag)
            value = (child.text or "").strip()
            if name in seen:
                errors.append(
                    f"{pom_path}: 属性 <{name}> 重复声明"
                    f"（先 {seen[name]!r} 后 {value!r}，Maven 以后者生效）"
                )
            else:
                seen[name] = value
    return errors


def main():
    poms = sys.argv[1:] or find_poms()
    all_errors = []
    for pom in poms:
        all_errors.extend(check(pom))
    if all_errors:
        print("发现重复的 pom 属性声明：", file=sys.stderr)
        for e in all_errors:
            print(f"  {e}", file=sys.stderr)
        return 1
    print(f"OK：{len(poms)} 个 pom.xml 无重复属性声明")
    return 0


if __name__ == "__main__":
    sys.exit(main())
