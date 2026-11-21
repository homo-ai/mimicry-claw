# Mimicry Claw

Multi-model software engineering agent with one shared Kotlin/JVM runtime and three frontends: CLI, TUI and Avalonia GUI.

## Current status

Implemented and verified in CI:

- Agent loop with real tool calls, permissions, interruption and usage tracking.
- Providers/protocols: Anthropic, OpenAI, Google, DeepSeek, Kimi, ZCode, MiMo; Anthropic Messages, OpenAI Chat Completions and Gemini parsing.
- Session JSON persistence, resume and transcript API.
- Local Runtime: loopback-only HTTP + SSE, per-process bearer token.
- CLI: `tui`, `exec`, `serve`, `status`, `sessions`, `resume`, `providers`, `tools`, `gui`.
- TUI: Normal Mode and Full Screen Mode.
- GUI: .NET 8 + Avalonia 11 MVVM desktop client, self-contained packages for six targets.
- 16 implemented tools: `Bash`, `Read`, `Write`, `Edit`, `Glob`, `Grep`, `TodoWrite`, `TaskCreate`, `TaskGet`, `TaskUpdate`, `TaskList`, `NotebookEdit`, `ToolSearch`, `ListMcpResources`, `ReadMcpResource`, `Brief`.
- Task/todo state persists per project in `.mimicry/tasks.json`.
- 13 tools remain honest stubs and are never advertised to the model.

Not yet integrated:

- `Agent`, `TaskOutput`, `TaskStop`, `Skill`, `EnterPlanMode`, `ExitPlanMode`, `WebFetch`, `WebSearch`, `AskUserQuestion`, `LSP`, `Config`, `EnterWorktree`, `ExitWorktree`.
- Live MCP connections, real subagent scheduling, Skills content, DSH plugins.
- GUI Tests/Build panels and PTY terminal. These are shown as unintegrated rather than faked.
- Wayland/Hyprland native window semantics and ARM64 desktop runtime require device validation.

See [docs/architecture.md](docs/architecture.md) for exact boundaries and protocol details.

## Layout

```text
src/main/kotlin/com/mimicryos/agent/
├── core/       QueryEngine, Tool, Message, Context, AppState
├── app/        ApplicationService, task queue, permissions, snapshots
├── runtime/    HTTP + SSE server/client, runtime discovery, GUI launcher
├── tools/      ToolRegistry and tool implementations
├── provider/   provider registry and settings
├── protocol/   Anthropic, OpenAI and Gemini parsers
├── session/    JSON session persistence
├── permission/ allow/deny/ask rules
├── plan/       plan state
├── cli/        command dispatcher
└── tui/        Normal and Full Screen terminal UI

gui/
├── Mimicry.Gui/       Avalonia MVVM frontend
└── Mimicry.Gui.Tests/ protocol, reducer and headless window tests
```

## Build and verify

Local compilation is intentionally skipped to save disk space. GitHub Actions performs all verification:

```text
build-jar    Kotlin compile + tests + fat JAR smoke tests
build-gui    Avalonia tests on Linux, macOS and Windows
             plus live tests against the real Kotlin runtime
publish-gui  self-contained .NET publish for six RIDs
package      JAR + GUI packaged into six platform ZIPs
release      desktop-preview-<sha> prerelease, never overwrites v1.0.0
```

## Run

Download a platform ZIP from Releases, or build locally with JDK 21:

```bash
./run.sh tui
./run.sh exec --provider anthropic "Explain this repository"
./run.sh serve
./run.sh status --json
./run.sh gui
```

`run.bat` is the Windows equivalent.

GUI lifecycle is independent from the runtime: closing the GUI leaves tasks running. Use **File → Shutdown Runtime** to stop the runtime explicitly.

## Configuration

Set the key for the chosen provider before running:

```bash
export ANTHROPIC_API_KEY="sk-ant-..."
export OPENAI_API_KEY="sk-..."
```

Common CLI options include `--cwd`, `--provider`, `--model`, `--api-key`, `--base-url`, `--permissions`, `--max-turns`, `--session` and `--json`.

## License

Research and educational use. Independent implementation.
