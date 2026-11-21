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
├── GUI Launcher                       runtime/GuiLauncher.kt     (GUI 生命周期与 Runtime 解耦)
│
├── CLI Frontend                       cli/Main.kt        (tui/exec/serve/status/sessions/resume/providers/tools/gui)
├── TUI Frontend                       tui/TuiApp.kt      (Normal Mode / Full Screen Mode)
└── GUI Frontend                       gui/               (.NET 8 + Avalonia 11 + MVVM)
    ├── RuntimeClient                  gui/Mimicry.Gui/Services/RuntimeClient.cs
    ├── RuntimeLocator                 gui/Mimicry.Gui/Services/RuntimeLocator.cs
    ├── ViewModels                     gui/Mimicry.Gui/ViewModels/
    └── Views                          gui/Mimicry.Gui/Views/MainWindow.axaml
```

关键约束：

- Agent Loop 只存在于 `core/QueryEngine.kt`，前端不实现任何模型调用或工具调度。
- 所有前端通过 `ApplicationService` 观察同一份任务、会话、事件和权限状态。
- GUI 关闭不会终止 Runtime；`mimicry gui` 会连接已有 Runtime，或为本次会话启动独立 Runtime，并把连接信息交给 GUI。
- Runtime 只监听回环地址、使用随机端口与每次启动生成的随机 token。GUI 拒绝非回环地址、禁止重定向，也不在界面暴露 token。

## 二、通信协议（Runtime HTTP API）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/health` | 存活检查与 runtimeId 校验 |
| GET | `/v1/snapshot` | 完整应用快照：会话、任务、Agent、Plan、Provider、工具、待审批权限 |
| GET | `/v1/events?since=<seq>` | SSE 事件流（历史回放、keepalive） |
| GET | `/v1/events/history?since=<seq>` | 事件历史（非流式） |
| POST | `/v1/tasks` | 提交任务，`?wait=1&timeoutMs=` 可同步等待 |
| GET | `/v1/tasks/{id}` | 任务状态 |
| POST | `/v1/tasks/{id}/interrupt` | 中断任务 |
| POST | `/v1/permissions/{id}` | 审批/拒绝权限请求 |
| GET | `/v1/sessions` | 会话列表 |
| POST | `/v1/sessions` | 新建并激活会话，供 GUI 的 New Session 使用 |
| POST | `/v1/sessions/{id}/resume` | 恢复会话 |
| GET | `/v1/sessions/{id}/messages` | 会话文本/工具记录，附带读取时的事件水位 |
| POST | `/v1/model`, `/v1/provider` | 切换模型 / Provider |
| GET | `/v1/config` | 运行时配置 |
| POST | `/v1/shutdown` | 显式关闭 Runtime |

事件模型（`AgentEvent`）覆盖：runtime/session/task/message/text-delta/tool/permission/plan/file/usage/log/error。
Session 持久化为 JSON（`<project>/.mimicry/sessions/<id>.json`），使用 `kind` 作为多态判别字段；事件流使用 `type`。
GUI 合并持久化 transcript 与 SSE 回放时按消息 ID / 工具调用 ID 去重。

## 三、实现状态（如实）

### 已实现并有测试覆盖

- Agent Loop：工具调用 → 权限判定 → 工具执行 → 结果回传 → 下一轮；`maxTurns`、中止、用量统计。
- 工具：Bash、Read、Write、Edit、Glob、Grep（真实文件/进程操作，失败以 tool error 回传）；TodoWrite、TaskCreate/Get/Update/List（项目级持久化任务/待办）；NotebookEdit（真实 .ipynb 单元格编辑）；ToolSearch；ListMcpResources/ReadMcpResource（读取已配置资源，不伪造 live MCP 连接）；Brief；WebFetch（公共 HTTP/HTTPS 抓取、拒绝私有地址与重定向、限制响应大小并提取纯文本）。当前共 17 个真实工具。
- Provider：Anthropic、OpenAI、Google、DeepSeek、Kimi、ZCode、MiMo（OpenAI 兼容协议）。
- 协议：Anthropic Messages、OpenAI Chat Completions、Google Gemini。
- 会话：JSON 落盘、读取、恢复、新会话、transcript API；拒绝路径穿越 ID。
- 应用层：任务队列、会话互斥、权限桥（超时/取消/非交互拒绝）、等待权限状态、快照。
- Runtime：HTTP + SSE 服务端与客户端，Bearer 鉴权，回环地址与 token 校验。
- CLI：`tui / exec / serve / status / sessions / resume / providers / tools / gui`。
- TUI：Normal Mode（行式，SSH/小终端可用）与 Full Screen Mode（备用屏、键盘滚动、y/n 审批、斜杠命令），无法全屏时明确降级并提示。
- GUI：Avalonia 11 桌面前端，共用 Runtime HTTP/SSE；Overview / Conversation / Tool Activity，Sidebar / Workspace / Inspector，可折叠/可调整的底部面板；红黄绿自绘标题栏；显式权限审批；会话历史与 live 事件合并；Provider/Model 切换；结构化工具活动；命令输出面板明确标注"不是 PTY"。
- GUI 生命周期：Exit/Close 只断开 GUI，不停止 Runtime；Shutdown Runtime 是菜单里的独立确认动作。
- CI：编译、测试、fat JAR 冒烟、Avalonia 三平台 headless 测试、真实 Kotlin Runtime 集成测试、6 平台自包含 GUI 打包、桌面预览版发布。

### 未实现（不得对外宣称可用）

- 12 个工具仍是 stub（Agent、TaskOutput、TaskStop、Skill、EnterPlanMode、ExitPlanMode、WebSearch、AskUserQuestion、LSP、Config、EnterWorktree、ExitWorktree），`ToolRegistry.getEnabled()` 不把它们发给模型；`mimicry tools` 会标记为 `stub`。
- Skills / DSH 插件集成、MCP 实际连接、Subagent 真实调度、Plan Mode 交互尚未接入运行时；GUI 只显示 Runtime 已报告的 Plan，不能创建或驱动 Plan。
- Tests / Build Output 底部面板没有接入采集器，界面明确显示"未集成"，不伪造绿色状态。
- Terminal 面板是显式用户命令的 stdout/stderr 输出查看器，不是 PTY、不是 Agent 工具通道。
- Wayland/Hyprland 等窗口管理器的 Close/Minimize/Hide/Restore/Maximize/Fullscreen 行为需在真实设备上验证；GUI 对不支持的操作给出提示，不伪装成功。
- 真实 API 端到端调用需要用户提供 key，CI 中只做离线验证。
- ARM64 桌面包为交叉发布产物，需在目标架构做实际启动验收。

## 四、构建与验证

- 本地不编译（磁盘容量考虑），全部通过 GitHub Actions 验证。
- 工作流：`.github/workflows/build.yml`
  - `build-jar`：JDK 21 → compileKotlin → test → fatJar → JAR 冒烟 → 上传产物
  - `build-gui`：Linux/macOS/Windows 三平台 .NET 8 → Avalonia build → headless 测试 → 对真实 Kotlin Runtime 的集成测试
  - `publish-gui`：6 个 RID 的 `.NET` 自包含 GUI
  - `package`：JAR + 自包含 GUI → 6 个平台 ZIP
  - `release`：发布按 commit SHA 标记的桌面预览版（不覆盖 v1.0.0）
- `mimicry gui` 启动前先定位桌面可执行文件；找不到时立刻报错，不会留下无窗口的后台 Runtime。

## 五、后续工作

1. 在真实 Windows/macOS/Linux 桌面环境验收自绘标题栏、窗口语义、缩放与 Wayland/Hyprland 降级。
2. 将 Tests / Build Output 面板接到实际采集器，而不是目前的明确未集成状态。
3. 接入 Skills / MCP / Subagent / Plan Mode，再把 UI 的只读呈现升级为可操作界面。
4. 逐步把剩余 12 个 stub 工具替换为真实实现，并保持 `mimicry tools` 的诚实标注。
