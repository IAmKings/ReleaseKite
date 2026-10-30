#!/usr/bin/env python3
"""Fail if the generated documentation site has a broken local link/resource.

This intentionally validates only local URLs; external links are not fetched.
It protects the two repo-file links copied into the site root (samples/, NOTICE)
and all Markdown/nav/image links.
"""

from __future__ import annotations

import sys
import urllib.parse
from html.parser import HTMLParser
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SITE = ROOT / "site"
BASE_PATH = "/ReleaseKite/"


class LinkParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.links: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        for key, value in attrs:
            if key in ("href", "src") and value:
                self.links.append(value)


def is_external(url: str) -> bool:
    return url.startswith(("http://", "https://", "//", "mailto:", "tel:", "javascript:", "data:", "#"))


def local_target(page: Path, raw: str) -> Path | None:
    url = raw.strip()
    if not url or is_external(url):
        return None
    url = url.split("#", 1)[0].split("?", 1)[0]
    if not url:
        return None
    url = urllib.parse.unquote(url)
    if url.startswith("/"):
        if not url.startswith(BASE_PATH):
            return None
        url = url[len(BASE_PATH) :]
    target = (page.parent / url).resolve()
    try:
        target.relative_to(SITE.resolve())
    except ValueError:
        return None
    if target.is_dir():
        target = target / "index.html"
    return target


def main() -> int:
    if not SITE.is_dir():
        print(f"错误: 缺少站点目录 {SITE}", file=sys.stderr)
        return 1

    errors: list[str] = []
    checked = 0
    for page in sorted(SITE.rglob("*.html")):
        parser = LinkParser()
        parser.feed(page.read_text(encoding="utf-8"))
        for raw in parser.links:
            target = local_target(page, raw)
            if target is None:
                continue
            checked += 1
            if not target.is_file():
                errors.append(f"{page.relative_to(SITE)} -> {raw}")

    if errors:
        print("站点本地链接检查 FAILED:", file=sys.stderr)
        for error in errors[:50]:
            print(f"- {error}", file=sys.stderr)
        if len(errors) > 50:
            print(f"- ... 还有 {len(errors) - 50} 个", file=sys.stderr)
        return 1

    print(f"OK: {checked} 个站点本地链接/资源存在")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
