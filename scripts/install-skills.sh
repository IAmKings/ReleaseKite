#!/usr/bin/env bash
# 把 ReleaseKite canonical skill 安装到 agent 的用户级 skill 目录。
# 默认 symlink 到 ~/.agents/skills；仓库更新即时生效；--copy 用于不支持 symlink 的环境。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SOURCE="$ROOT/skills/releasekite"
TARGET="${HOME}/.agents/skills"
MODE="symlink"

usage() {
    cat <<'EOF'
用法: scripts/install-skills.sh [--target DIR] [--copy]

  --target DIR   安装目录（默认 ~/.agents/skills）
  --copy          复制 skill 而不是创建 symlink
  -h, --help      显示帮助
EOF
}

while [ $# -gt 0 ]; do
    case "$1" in
        --target)
            [ $# -ge 2 ] || { echo "错误: --target 需要目录参数" >&2; exit 2; }
            TARGET="$2"
            shift 2
            ;;
        --copy)
            MODE="copy"
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            echo "错误: 未知参数 $1" >&2
            usage >&2
            exit 2
            ;;
    esac
done

[ -f "$SOURCE/SKILL.md" ] || {
    echo "错误: 找不到 $SOURCE/SKILL.md（是否在 ReleaseKite 仓库内运行？）" >&2
    exit 1
}

mkdir -p "$TARGET"
DEST="$TARGET/releasekite"

if [ "$MODE" = "symlink" ]; then
    if [ -L "$DEST" ]; then
        CURRENT="$(readlink "$DEST")"
        if [ "$CURRENT" = "$SOURCE" ]; then
            echo "已安装: $DEST -> $SOURCE"
            exit 0
        fi
        echo "错误: $DEST 已指向其它位置: $CURRENT；请手动移除后重试" >&2
        exit 1
    fi
    if [ -e "$DEST" ]; then
        echo "错误: $DEST 已存在（非 symlink）；请手动移除或使用 --copy 到另一个目录" >&2
        exit 1
    fi
    ln -s "$SOURCE" "$DEST"
else
    if [ -e "$DEST" ]; then
        echo "错误: $DEST 已存在；请手动移除后重试（copy 模式不会覆盖）" >&2
        exit 1
    fi
    cp -R "$SOURCE" "$DEST"
fi

echo "已安装 ($MODE): $DEST"
echo "验证: test -f \"$DEST/SKILL.md\" && echo OK"
echo "如 agent 未自动发现，请重启/刷新其 skill catalog。"

