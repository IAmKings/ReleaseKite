# ReleaseKite AI Skills

本目录是 ReleaseKite 的 AI skill canonical source：让 agent 快速判断“接入 / 使用 / 发布 / 维护”
该读什么，而不是把整份文档塞进上下文。

仓库采用标准 `skills/<name>/SKILL.md` 布局，可直接被 Vercel Labs 的
[`npx skills`](https://github.com/vercel-labs/skills) 发现，无需额外 manifest。

## 推荐安装：npx skills add

需要 Node.js **22.20+**。在**你的宿主项目**根目录执行：

```bash
# 全局安装到当前 AI agent（以 Claude Code 为例；-g = 用户级，--skill = 只装 ReleaseKite）
npx skills add IAmKings/ReleaseKite --skill releasekite -g -a claude-code -y

# 或安装到当前项目（写入 .claude/skills/ 或 .agents/skills/；团队共享时提交 skills-lock.json）
npx skills add IAmKings/ReleaseKite --skill releasekite -a claude-code -y

# 先看仓库里有哪些 skill，不安装
npx skills add IAmKings/ReleaseKite --list

# 装到所有已检测到的 agent
npx skills add IAmKings/ReleaseKite --all -y

# 不支持 symlink 的环境
npx skills add IAmKings/ReleaseKite --skill releasekite -a claude-code --copy -y
```

其他常用命令：

```bash
npx skills check          # 检查已安装 skill 是否有更新
npx skills update         # 更新已安装 skill
npx skills remove releasekite
```

安装后重启/刷新 agent 的 skill catalog。不同 agent 使用不同目录（Claude Code 为
`.claude/skills/` / `~/.claude/skills/`，Codex/Cline/Cursor 等常用 `.agents/skills/` /
`~/.agents/skills/`）；用 `-a <agent>` 或 `--all` 选择。

## 没有 Node / 离线环境：脚本 fallback

仓库保留 `scripts/install-skills.sh`，适合：

- 没有 Node 22.20+，或无法访问 npm/GitHub；
- CI/内网环境；
- 想直接从当前仓库 symlink（修改即时生效）而不是走 skills CLI。

```bash
# 默认安装到 ~/.agents/skills（symlink）
./scripts/install-skills.sh

# Claude Code 用户目录
./scripts/install-skills.sh --target ~/.claude/skills

# 不支持 symlink
./scripts/install-skills.sh --target ~/.agents/skills --copy

# 修改 skill 后校验
python3 scripts/test-skills.py
```

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
- fork 用户需把 GitHub URL/安装命令中的 `IAmKings` 替换为 fork owner；
- `npx skills add` 按默认分支（main）发现本目录，保持 `skills/<name>/SKILL.md` 布局。

## 兼容性

`SKILL.md` 采用通用 YAML frontmatter + Markdown（`name` / `description`），符合
[Agent Skills 规范](https://agentskills.io)，可被 skills CLI 及 DSH/Claude/Codex/Cursor 等兼容 agent
使用；不依赖某个 agent 的私有格式。

