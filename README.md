# Mimicry Claw

Multi-Model Software Engineering Agent — a Kotlin/JVM implementation of an AI Coding Agent.

## Overview

Mimicry Claw is an independent software engineering agent tool within the Mimicry ecosystem. It is built by migrating and refactoring a mature AI Coding Agent implementation to Kotlin/JVM, then extending it with multi-model, multi-provider, and multi-protocol support.

**This is not** an API client. It is a full agent runtime with tool calling, session management, skills, subagents, MCP integration, and permission control.

## Features

- **Agent Loop** — Full conversational agent with tool calling and iterative execution
- **Multi-Provider** — Support for Anthropic, OpenAI, Google, and custom providers
- **Multi-Protocol** — Anthropic Messages API, OpenAI Chat Completions, Google Gemini, and custom HTTP
- **Tool System** — 29 built-in tools (Bash, File Read/Write/Edit, Glob, Grep, Agent, Skills, etc.)
- **Tool Registry** — Pluggable tool registration with permission-based filtering
- **Skills System** — Markdown-based skill discovery, loading, and execution
- **Subagents** — Multi-agent coordination with different model support
- **MCP Integration** — Model Context Protocol for external tools
- **Plan Mode** — Planning and approval workflow for complex tasks
- **Permission System** — Deterministic permission control with allow/deny/ask modes
- **Session Management** — Persistent session storage and recovery
- **Context Management** — Token tracking and compaction
- **Hooks** — Lifecycle hooks for tool use, sessions, compaction, and more
- **CLI** — Interactive REPL and print modes with Clikt + Mordant

## Architecture

```
mimicry-claw/
├── core/           # Agent core: QueryEngine, Tool, Message, AppState, Context
├── provider/       # Provider interface, ModelRegistry, ModelRouter
├── protocol/       # Anthropic, OpenAI, Gemini protocol implementations
├── tools/          # ToolRegistry with 29 built-in tools
├── permission/     # Permission system (allow/deny/ask)
├── hooks/          # Lifecycle hook manager
├── skills/         # Skill discovery, loading, execution
├── session/        # Session persistence
├── context/        # Context window management and compaction
├── subagent/       # Subagent spawning and coordination
├── mcp/            # MCP server integration
├── plan/           # Plan mode management
├── cli/            # CLI entry point (Clikt + Mordant)
└── util/           # Utilities
```

## Technology Stack

| Component | Technology |
|-----------|-----------|
| Language | Kotlin 2.1.20 |
| JVM | JDK 21 |
| Build | Gradle 8.x + Kotlin DSL |
| Coroutines | kotlinx-coroutines 1.9.0 |
| HTTP | Ktor 3.0 |
| Serialization | kotlinx-serialization 1.7.1 |
| CLI | Clikt 5.0.0 |
| Terminal | Mordant 2.4.0 |
| Logging | kotlinx-logging + SLF4J + Logback |

## Requirements

- **JDK 21** or later
- **Gradle 8.x** (or use the Gradle wrapper)
- **Kotlin 2.1.20**

## Build

```bash
./gradlew build
```

## Run

```bash
# Interactive REPL mode
./gradlew run

# One-shot prompt
./gradlew run -- "your prompt here"

# With specific model and provider
./gradlew run -- --model gpt-4o --provider openai --api-key YOUR_KEY
```

## Configuration

Copy `.env.example` to `.env` and fill in your API keys:

```bash
cp .env.example .env
```

Configuration is also available via CLI flags:

| Flag | Description |
|------|-------------|
| `--model` | Model to use |
| `--provider` | Provider (anthropic, openai, google) |
| `--api-key` | API key for the provider |
| `--permissions` | Permission mode (default, bypass, auto, plan) |
| `--max-turns` | Maximum turns per session |
| `--cwd` | Working directory |

## Current Status

This is an early-stage implementation. The agent core and tool system are functional skeletons with full type definitions and architecture. Some tools are fully implemented; others are stubs awaiting implementation.

**Implemented:**
- Agent core (QueryEngine, Tool, Message types, AppState)
- Multi-provider architecture (Anthropic, OpenAI, Google)
- Multi-protocol support (Anthropic Messages, OpenAI Chat, Gemini)
- Tool registry with 29 tool definitions
- Permission system
- Hook system
- Skill manager
- Subagent manager
- MCP manager
- Plan mode manager
- Session manager
- Context manager
- CLI with Clikt

**Not yet implemented:**
- Full tool implementations (current tools are stubs)
- Actual HTTP request execution
- Test suite
- DSH plugin integration
- Skills content (Markdown files)
- Design workflows

## License

This project is for research and educational purposes. This project is an independent implementation.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for development guidelines.
# trigger
