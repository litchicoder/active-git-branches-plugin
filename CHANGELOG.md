# Changelog

All notable changes to this project will be documented in this file.
本项目所有显著变更都会记录在此文件。

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

---

## [1.6.0] - 2026-05-11

### Added
- **Custom branch input** — new per-parameter option `allowCustomBranch`. When enabled, an extra `✏ Custom (manual input)...` entry appears at the bottom of the dropdown; selecting it reveals a text input so users can submit any branch / ref that is not in the active list (e.g. a brand-new feature branch that has not yet been picked up by the cache).
- **Branch name sanitization** for custom input — rejects whitespace, `..`, `~ ^ : ? * [ \`, and leading `-`, mirroring `git check-ref-format` semantics. Submissions that fail sanitization are rejected at parameter-value creation time.
- **Stale-While-Revalidate (SWR) in-memory cache** — the "Build with Parameters" page now returns the cached branch list immediately and triggers an asynchronous refresh in the background. Cache key is composed of `(repositoryUrl, credentialsId, maxBranchCount, branchFilter, alwaysIncludeBranches, useQuickFetch)`. Concurrent refreshes for the same key are deduplicated.
- **Freshness UI** — under the dropdown the page now shows `fetched X ago` plus a `refresh` link. If the last background refresh failed, a `⚠ last refresh failed` warning is rendered so users know they're looking at older data.
- **Help text** — new `help-allowCustomBranch.html` documenting the manual-input option and its trade-offs.

### Changed
- `fetchBranches()` refactored from a synchronous fetch-on-every-render to the SWR pattern above. Cold start (no cache) still blocks, all subsequent renders are non-blocking.
- `tryFetchFromWorkspace(Job)` now accepts an explicit `Job` argument, so background refresh threads (which lack `StaplerRequest`) can still reuse the job workspace for time-sorted output.
- Build pipeline hardened so the SezPoz `@Extension` index file (`META-INF/annotations/hudson.Extension`) is always regenerated:
  - `maven-clean-plugin` is now bound to the `initialize` phase and wipes `${project.build.outputDirectory}` before every compile.
  - `maven-compiler-plugin` has `useIncrementalCompilation=false`.

### Fixed
- Intermittent `AssertionError: ActiveGitBranchesParameterDefinition is missing its descriptor` on the **Build with Parameters** page after partial recompilation (typically caused by IDE incremental builds skipping annotation processing).

### Compatibility
- No breaking changes. Existing job configurations from `1.5.x` continue to work; `allowCustomBranch` defaults to `false`.

---

### 新增
- **手动输入分支** —— 新增参数选项 `allowCustomBranch`。开启后，下拉框底部会多出 `✏ Custom (manual input)...` 一项；选中后展开输入框，可以提交任何不在活跃列表中的分支或引用（例如刚创建、还没进缓存的 feature 分支）。
- **分支名安全校验** —— 手动输入会拒绝空白字符、`..`、`~ ^ : ? * [ \` 以及以 `-` 开头的值，对齐 `git check-ref-format` 的约束。不合法的输入会在参数值创建阶段被拦截。
- **SWR(Stale-While-Revalidate) 内存缓存** —— 打开 “Build with Parameters” 页面时直接返回缓存中的分支列表，同时在后台异步刷新缓存。缓存 key 由 `(仓库地址, 凭证 ID, maxBranchCount, branchFilter, alwaysIncludeBranches, useQuickFetch)` 组成；相同 key 的并发刷新会自动去重。
- **新鲜度提示** —— 下拉框下方会显示 `fetched X ago` 以及 `refresh` 链接；如果上一次后台刷新失败，会显示 `⚠ last refresh failed`，提醒用户当前看到的是旧数据。
- **帮助文档** —— 新增 `help-allowCustomBranch.html`，说明手动输入选项的用途和注意事项。

### 变更
- `fetchBranches()` 由“每次渲染都同步拉取”重构为上述 SWR 模式：冷启动（无缓存）仍是同步拉取，后续渲染全部非阻塞。
- `tryFetchFromWorkspace(Job)` 改为接受显式 `Job` 参数，使没有 `StaplerRequest` 上下文的后台刷新线程仍能复用 job workspace，从而维持按提交时间排序的结果。
- 构建链路加固，确保 SezPoz 注解索引 `META-INF/annotations/hudson.Extension` 每次都重新生成：
  - 将 `maven-clean-plugin` 绑定到 `initialize` 阶段，在每次编译前清掉 `${project.build.outputDirectory}`。
  - `maven-compiler-plugin` 关闭增量编译（`useIncrementalCompilation=false`）。

### 修复
- 偶发的 `AssertionError: ActiveGitBranchesParameterDefinition is missing its descriptor` —— 出现在仅做了部分重新编译（通常是 IDE 增量构建跳过了注解处理）之后打开 **Build with Parameters** 页面时。

### 兼容性
- 无破坏性变更，`1.5.x` 的作业配置可直接沿用；`allowCustomBranch` 默认关闭。

---

## [1.5.x] and earlier / 及更早版本

Initial public iterations: dynamic branch fetching, regex filtering, Top-N limit, credentials integration, `Test Connection`, quick-fetch toggle, etc.

早期公开迭代：动态拉取分支、正则过滤、Top-N 限制、凭证集成、`Test Connection`、quick-fetch 开关等基础能力。

[1.6.0]: https://github.com/litchicoder/active-git-branches-plugin/releases/tag/v1.6.0
