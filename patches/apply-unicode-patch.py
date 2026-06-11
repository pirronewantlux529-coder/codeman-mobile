#!/usr/bin/env python3
"""给 Codeman (https://github.com/Ark0N/Codeman) 打中文/Unicode 路径支持补丁。

上游用 ASCII 白名单正则校验 case 名称和路径，中文（及其他非拉丁文字）会被拒绝。
本补丁把相关正则改为 Unicode 属性类（\\p{L}\\p{N}），保留原有的防注入设计
（shell 元字符、路径穿越仍被拦截）。

用法:
    python3 apply-unicode-patch.py ~/.codeman/app
    cd ~/.codeman/app && npm run build   # 打完必须重新构建

升级/重装 Codeman 后需要重新执行。
"""
import sys
from pathlib import Path

EDITS = [
    ("src/utils/regex-patterns.ts", [
        (r"export const SAFE_PATH_PATTERN = /^[a-zA-Z0-9_/\-. ~]+$/;",
         r"export const SAFE_PATH_PATTERN = /^[\p{L}\p{N}_/\-. ~]+$/u;"),
    ]),
    ("src/web/schemas.ts", [
        (r"/^[a-zA-Z0-9_-]+$/, 'Invalid case name",
         r"/^[\p{L}\p{N}_-]+$/u, 'Invalid case name"),
    ]),
    ("src/web/public/session-ui.js", [
        (r"/^[a-zA-Z0-9_-]+$/.test(name)",
         r"/^[\p{L}\p{N}_-]+$/u.test(name)"),
        (r"session.name.match(/^w(\d+)-([a-zA-Z0-9_-]+)/)",
         r"session.name.match(/^w(\d+)-([\p{L}\p{N}_-]+)/u)"),
        (r"session.name.match(/^s(\d+)-([a-zA-Z0-9_-]+)/)",
         r"session.name.match(/^s(\d+)-([\p{L}\p{N}_-]+)/u)"),
    ]),
    ("src/web/public/app.js", [
        (r"/^(w\d+-[a-zA-Z0-9_-]+|s\d+-[a-zA-Z0-9_-]+)/",
         r"/^(w\d+-[\p{L}\p{N}_-]+|s\d+-[\p{L}\p{N}_-]+)/u"),
    ]),
]


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__)
        return 1
    root = Path(sys.argv[1]).expanduser()
    if not (root / "package.json").exists():
        print(f"错误: {root} 不是 Codeman 目录（找不到 package.json）")
        return 1
    total = 0
    for rel, pairs in EDITS:
        path = root / rel
        text = path.read_text(encoding="utf-8")
        for old, new in pairs:
            n = text.count(old)
            if n == 0 and new not in text:
                print(f"!! 未命中（上游可能已改动）: {rel}: {old[:50]}")
                continue
            text = text.replace(old, new)
            total += n
            print(f"OK {rel} x{n}")
        path.write_text(text, encoding="utf-8")
    print(f"\n共替换 {total} 处。请执行: cd {root} && npm run build")
    return 0


if __name__ == "__main__":
    sys.exit(main())
