# Agent instructions

为 ReleaseKite 仓库工作的 agent：开始任何“接入、使用、发布、维护、排障”任务前，先读
[`skills/releasekite/SKILL.md`](skills/releasekite/SKILL.md)。它提供任务路由、硬约束和验证清单，
再把具体细节指向 canonical docs。

- 文档：`docs/`（API_CONTRACT、INTEGRATION、RELEASE_PIPELINE、SECURITY、TESTING 等）
- 示例：`sample/`；宿主发布模板：`samples/github-workflows/release.yml`
- 门禁：`docs/TESTING.md`；发布库：见 `skills/releasekite/references/maintainer.md`
- 不要提交 secrets/keystore；不要静默安装；不要覆盖已发布 tag；修改行为必须同步测试与文档。

仓库内 skills 的 canonical source 是 `skills/releasekite/`。外部宿主优先用标准安装方式：
`npx skills add IAmKings/ReleaseKite --skill releasekite -g`；没有 Node/离线环境再用
`scripts/install-skills.sh` fallback。修改 skill 后运行 `python3 scripts/test-skills.py`。
