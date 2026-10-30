#!/usr/bin/env python3
"""Validate the repository's AI skills without third-party dependencies.

Checks:
- every skill has SKILL.md with YAML-ish frontmatter (name/description);
- frontmatter name matches directory and description is bounded;
- required sections exist;
- all references are non-empty and linked from SKILL.md;
- all relative Markdown links resolve inside the repository;
- no obvious secrets or local absolute paths;
- AGENTS.md points at skills/releasekite/SKILL.md and README links skills/.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SKILLS = ROOT / "skills"
NAME_RE = re.compile(r"^[a-z0-9]+(?:-[a-z0-9]+)*$")
LINK_RE = re.compile(r"\[[^\]]*\]\(([^)]+)\)")
SECRET_PATTERNS = [
    re.compile(r"ghp_[A-Za-z0-9]{20,}"),
    re.compile(r"github_pat_[A-Za-z0-9_]{20,}"),
    re.compile(r"BEGIN [A-Z ]*PRIVATE KEY"),
]
REQUIRED_SECTIONS = ["## 先判断任务", "## 硬约束", "## Canonical sources", "## 完成前验证"]


def fail(errors: list[str], message: str) -> None:
    errors.append(message)


def parse_frontmatter(path: Path, errors: list[str]) -> tuple[dict[str, str], str]:
    text = path.read_text(encoding="utf-8")
    if not text.startswith("---\n"):
        fail(errors, f"{path}: 缺少 YAML frontmatter（首行应为 ---）")
        return {}, text
    end = text.find("\n---\n", 4)
    if end < 0:
        fail(errors, f"{path}: frontmatter 未闭合（缺少结束 ---）")
        return {}, text
    raw = text[4:end]
    body = text[end + 5 :]
    data: dict[str, str] = {}
    for line in raw.splitlines():
        line = line.rstrip()
        if not line or line.lstrip().startswith("#"):
            continue
        if ":" not in line:
            fail(errors, f"{path}: frontmatter 行无法解析: {line!r}")
            continue
        key, value = line.split(":", 1)
        key = key.strip()
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        if key in data:
            fail(errors, f"{path}: frontmatter 重复 key: {key}")
        data[key] = value
    return data, body


def check_links(path: Path, errors: list[str]) -> int:
    text = path.read_text(encoding="utf-8")
    checked = 0
    for match in LINK_RE.finditer(text):
        target = match.group(1).strip()
        if target.startswith(("http://", "https://", "mailto:", "#")):
            continue
        target = target.split("#", 1)[0].strip()
        if not target:
            continue
        checked += 1
        resolved = (path.parent / target).resolve()
        try:
            resolved.relative_to(ROOT)
        except ValueError:
            fail(errors, f"{path}: 相对链接逃出仓库: {target}")
            continue
        if not resolved.exists():
            fail(errors, f"{path}: 相对链接不存在: {target}")
    return checked


def check_secrets(path: Path, errors: list[str]) -> None:
    text = path.read_text(encoding="utf-8")
    for pattern in SECRET_PATTERNS:
        if pattern.search(text):
            fail(errors, f"{path}: 含疑似 secret/private key")
    for bad in ("/Users/", "/private/"):
        if bad in text:
            fail(errors, f"{path}: 含本机绝对路径 {bad}")


def main() -> int:
    errors: list[str] = []
    if not SKILLS.is_dir():
        print(f"ERROR: 缺少 {SKILLS}", file=sys.stderr)
        return 1

    skill_dirs = sorted(p for p in SKILLS.iterdir() if (p / "SKILL.md").is_file())
    if not skill_dirs:
        print("ERROR: skills/ 下没有 SKILL.md", file=sys.stderr)
        return 1

    link_count = 0
    for skill in skill_dirs:
        skill_md = skill / "SKILL.md"
        data, body = parse_frontmatter(skill_md, errors)

        name = data.get("name", "")
        description = data.get("description", "")
        if not NAME_RE.match(name):
            fail(errors, f"{skill_md}: name 必须为小写 kebab-case")
        if name != skill.name:
            fail(errors, f"{skill_md}: name={name!r} 与目录名 {skill.name!r} 不一致")
        if not description:
            fail(errors, f"{skill_md}: description 不能为空")
        elif len(description) > 1024:
            fail(errors, f"{skill_md}: description 过长（{len(description)} > 1024）")

        for heading in REQUIRED_SECTIONS:
            if heading not in body:
                fail(errors, f"{skill_md}: 缺少必备章节 {heading!r}")

        refs = skill / "references"
        ref_files = sorted(p for p in refs.glob("*.md")) if refs.is_dir() else []
        if not ref_files:
            fail(errors, f"{skill}: 缺少 references/*.md")
        for ref in ref_files:
            if not ref.read_text(encoding="utf-8").strip():
                fail(errors, f"{ref}: 文件为空")
            if ref.name not in body:
                fail(errors, f"{skill_md}: 未链接 reference {ref.name}")

        for md in [skill_md, *sorted(skill.rglob("*.md"))]:
            check_secrets(md, errors)
            link_count += check_links(md, errors)

    agents = ROOT / "AGENTS.md"
    if not agents.is_file():
        fail(errors, "AGENTS.md 不存在（用于仓库内 agent 发现 skill）")
    elif "skills/releasekite/SKILL.md" not in agents.read_text(encoding="utf-8"):
        fail(errors, "AGENTS.md 没有指向 skills/releasekite/SKILL.md")

    readme = ROOT / "README.md"
    if readme.is_file() and "skills/README.md" not in readme.read_text(encoding="utf-8"):
        fail(errors, "README.md 没有链接 skills/README.md")

    if errors:
        print("Skill validation FAILED:", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1

    print(f"OK: {len(skill_dirs)} skill(s), {link_count} relative link(s) validated")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
