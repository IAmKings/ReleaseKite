# ReleaseKite AI Skills

本目录是 ReleaseKite 的 AI skill canonical source：让 agent 快速判断“接入 / 使用 / 发布 / 维护”
该读什么，而不是把整份文档塞进上下文。

## 内容

```text
skills/
└── releasekite/
    ├── SKILL.md            # 触发条件 + 任务路由 + 硬约束 + 验证
    └── references/
        ├── integration.md       # 依赖、初始化、Host、权限、R8
        ├── api-usage.md         # 状态机、Manager API、Beta/灰度/强制
        ├── release-workflow.md  # 宿主 App 的 Actions 发布 + 资产契约
        ├── maintainer.md        # 本仓库构建/测试/发版
        └── troubleshooting.md   # 接入、下载、安装、发布排障
```

## 安装

仓库里的 `skills/` **不会被所有 AI 工具自动扫描**；需要安装到 agent 的用户级 skill 目录。

```bash
# 默认安装到 ~/.agents/skills（symlink；仓库更新即时生效）
./scripts/install-skills.sh

# Claude Code 用户目录
./scripts/install-skills.sh --target ~/.claude/skills

# 无法使用 symlink 的环境（Windows/受限文件系统）
./scripts/install-skills.sh --target ~/.agents/skills --copy
```

安装后重启/刷新 agent 的 skill catalog，确认：

```bash
test -f ~/.agents/skills/releasekite/SKILL.md && echo OK
```

## 在 ReleaseKite 仓库内使用

仓库根目录有 `AGENTS.md` 指针，agent 在本仓库工作时会先读 `skills/releasekite/SKILL.md`。
修改 skill 后运行：

```bash
python3 scripts/test-skills.py
```

CI 也会执行同一校验。

## 维护规则

- 外部文档链接使用绝对 GitHub URL（安装后 skill 不在仓库内，相对路径会失效）；
- skill 内 references 之间的链接用相对路径；
- 不复制完整文档/测试数字；细节链接到 canonical docs，避免漂移；
- 不把 secrets、keystore、token、本机绝对路径写进 skill；
- 当前版本坐标是 `v0.1.0`（JitPack tag）/ `0.1.0`（artifact）；版本升级时同步 SKILL 与 references；
- fork 用户需把 GitHub URL 中的 `IAmKings` 替换为 fork owner。

## 兼容性

SKILL.md 采用通用的 YAML frontmatter + Markdown 格式（`name` / `description`），适用于 DSH/Claude
等支持 agent skill 的工具；不同工具的 skill 目录名可能不同，用 `--target` 指向对应目录即可。

