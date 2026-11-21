# Mimicry Claw 架构与进度

## 一、产品结构

CLI、TUI、GUI 是同一套应用的不同交互入口，共用一个 Application Core 与 Agent Runtime。

```
Mimicry Claw
│
├── Application Core (Kotlin/JVM)      src/main/kotlin/com/mimicryos/agent/
│   ├── Agent Runtime                  core/QueryEngine.kt（唯一 Agent Loop）
│   ├── Task / Session / Context       app/, session/, context/
│   ├── Provider / Protocol            provider/, protocol/
│   ├── Tool Dispatch                  tools/ToolRegistry.kt
│   ├── Permission                     permission/, app/PermissionBroker.kt
│   ├── Event System                   events/AgentEvent.kt, events/EventBus.kt
│   └── Configuration                  app/AppConfig.kt
│
├── Runtime Server                     runtime/RuntimeServer.kt   (127.0.0.1 HTTP + SSE)
├── Runtime Client                     runtime/RuntimeClient.kt
├── Frontend Runtime 抽象              runtime/FrontendRuntime.kt (Local / Remote)
│
├── CLI Frontend                       cli/Main.kt        (tui/exec/serve/status/sessions/resume/providers/tools)
├── TUI Frontend                       tui/TuiApp.kt      (Normal Mode / Full Screen Mode)
└── GUI Frontend                       gui/               (Stage B，尚未开始)
```

关键约束：

- Agent Loop 只存在于 `core/QueryEngine.kt`，前端不实现任何模型调用或工具调度。
- 所有前端通过 `ApplicationService` 观察同一份任务、会话、事件和权限状态。
- GUI 关闭不会终止 Runtime；重开 GUI 通过 `~/.mimicry/runtime.json` + Bearer token 重连。
- 运行时只监听回环地址，随机端口，每次启动生成随机 token。

## 二、通信协议（Runtime HTTP API）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/health` | 存活检查（无需业务数据） |
| GET | `/v1/snapshot` | 完整应用快照：会话、任务、Agent、Plan、Provider、工具、待审批权限 |
| GET | `/v1/events?since=<seq>` | SSE 事件流（含历史回放、keepalive） |
| GET | `/v1/events/history?since=<seq>` | 事件历史（非流式） |
| POST | `/v1/tasks` | 提交任务，`?wait=1&timeoutMs=` 可同步等待 |
| GET | `/v1/tasks/{id}` | 任务状态 |
| POST | `/v1/tasks/{id}/interrupt` | 中断任务 |
| POST | `/v1/permissions/{id}` | 审批/拒绝权限请求 |
| GET | `/v1/sessions` | 会话列表 |
| POST | `/v1/sessions/{id}/resume` | 恢复会话 |
| POST | `/v1/model`, `/v1/provider` | 切换模型 / Provider |
| GET | `/v1/config` | 运行时配置 |
| POST | `/v1/shutdown` | 关闭 Runtime |

事件模型（`AgentEvent`）覆盖：runtime/session/task/message/text-delta/tool/permission/plan/file/usage/log/error。
Session 持久化为 JSON（`<project>/.mimicry/sessions/<id>.json`），使用 `kind` 作为多态判别字段；事件流使用 `type`。

## 三、实现状态（如实）

### 已实现并有测试覆盖

- Agent Loop：工具调用 → 权限判定 → 工具执行 → 结果回传 → 下一轮；`maxTurns`、中止、用量统计。
- 工具：Bash、Read、Write、Edit、Glob、Grep（真实文件/进程操作，失败以 tool error 回传）。
- Provider：Anthropic、OpenAI、Google、DeepSeek、Kimi、ZCode、MiMo（OpenAI 兼容协议）。
- 协议：Anthropic Messages、OpenAI Chat Completions、Google Gemini。
- 会话：JSON 落盘与恢复（`loadMessages` 真实读回）。
- 应用层：任务队列、会话互斥、权限桥（超时/取消/非交互拒绝）、快照。
- Runtime：HTTP + SSE 服务端与客户端，Bearer 鉴权。
- CLI：`tui / exec / serve / status / sessions / resume / providers / tools`。
- TUI：Normal Mode（行式，SSH/小终端可用）与 Full Screen Mode（备用屏、键盘滚动、y/n 审批、斜杠命令），无法全屏时明确降级并提示。
- CI：编译、测试、fat JAR 校验（`--help`、`--version`、`tools`、`providers`、`status --json`、`sessions --json`）、6 平台打包、Release 发布。

测试：32 个用例通过（Agent Loop 工具往返、拒绝路径、Session 持久化、工具 schema/输入解码、应用层任务生命周期与中断、Runtime HTTP 与鉴权、三协议解析）。

### 未实现（不得对外宣称可用）

- GUI（Avalonia）尚未开始；`mimicry gui` 只输出诚实提示，不伪造窗口。
- 23 个工具仍是 stub，`ToolRegistry.getEnabled()` 不把它们发给模型；`mimicry tools` 会标记为 `stub`。
- Windows/macOS/Linux 原生窗口行为（Close/Minimize/Hide/Restore/Maximize/Fullscreen/Quit）尚无实现，需随 GUI 落地。
- Skills / DSH 插件集成、MCP 实际连接、Subagent 真实调度、Plan Mode 交互尚未接入运行时。
- 真实 API 端到端调用需要用户提供 key，CI 中只做离线验证。

## 四、构建与验证

- 本地不编译（磁盘容量考虑），全部通过 GitHub Actions 验证。
- 工作流：`.github/workflows/build.yml`
  - `build-jar`：JDK 21 → compileKotlin → test → fatJar → JAR 冒烟 → 上传产物
  - `package`：6 个平台 ZIP（矩阵）
  - `release`：发布 `v1.0.0` 资产（需要 `contents: write`）

## 五、下一步（Stage B）

1. `gui/` 建立 .NET 8 + Avalonia 11 + CommunityToolkit.Mvvm 工程。
2. 通过 `RuntimeClient` 接入同一 Runtime（HTTP + SSE），不新建 Agent Loop。
3. macOS 风格自绘标题栏（左上角红黄绿三色按钮），所有平台统一。
4. 主窗口结构：三色按钮 + 菜单栏 + Toolbar + Sidebar + Main Workspace + Inspector + Bottom Panel。
5. Tool Activity 使用结构化组件，而不是聊天气泡。
6. 窗口语义严格区分 Close / Minimize / Hide / Restore / Maximize / Fullscreen / Quit；Wayland/Hyprland 按实际 WM 能力降级，不伪装成功。
7. GUI 生命周期独立于 Runtime：关闭窗口不终止 Agent，重开可恢复。
