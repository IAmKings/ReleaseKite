#!/usr/bin/env bash
# Build the GitHub Pages documentation site.
#
# docs/ 是唯一内容源；这里在临时目录做最小 staging：
# - 把 docs/ 复制到临时目录（不修改仓库中的 canonical Markdown）；
# - 把 docs 中指向仓库根 ../samples、../NOTICE 的链接改写为 GitHub URL
#   （这些文件不是 MkDocs 页面；GitHub 链接在站点上仍可直接打开/下载）；
# - 用临时 mkdocs.yml 构建到仓库 site/，再做本地链接检查。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

TMP="$(mktemp -d "${TMPDIR:-/tmp}/releasekite-docs.XXXXXX")"
cleanup() {
    rm -rf "$TMP"
}
trap cleanup EXIT

cp -R docs "$TMP/docs"
cp mkdocs.yml "$TMP/mkdocs.yml"

python3 - "$TMP/docs" <<'PY'
import re
import sys
from pathlib import Path

docs = Path(sys.argv[1])
base = "https://github.com/IAmKings/ReleaseKite/blob/main"
for path in docs.rglob("*.md"):
    text = path.read_text(encoding="utf-8")
    text = re.sub(r"\]\(\.\./samples/([^)]+)\)", lambda m: f"]({base}/samples/{m.group(1)})", text)
    text = text.replace("](../NOTICE)", f"]({base}/NOTICE)")
    path.write_text(text, encoding="utf-8")
PY

rm -rf site
python3 -m mkdocs build --strict -f "$TMP/mkdocs.yml" -d "$ROOT/site"

[ -f site/index.html ] || { echo "错误: mkdocs 未生成 site/index.html" >&2; exit 1; }

python3 scripts/check-site-links.py

echo "站点构建完成: $ROOT/site"
echo "本地预览: python3 -m http.server 8000 --directory site"
