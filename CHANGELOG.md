# Changelog

## [1.0.0] - 2025-01-18

### Added
- Initial project structure and Gradle build configuration
- Agent core: QueryEngine, Tool interface, Message types, AppState
- Multi-provider architecture with ModelRegistry and ModelRouter
- Multi-protocol support: Anthropic Messages API, OpenAI Chat Completions, Google Gemini
- Tool registry with 29 tool definitions
- Permission system (allow/deny/ask modes)
- Hook manager for lifecycle events
- Skill manager for Markdown-based skill discovery and execution
- Subagent manager for multi-agent coordination
- MCP manager for Model Context Protocol integration
- Plan mode manager
- Session manager for persistence
- Context manager for token tracking and compaction
- CLI with Clikt and Mordant terminal rendering
- Documentation: README, CONTRIBUTING, migration mapping

### Notes
- This is the initial release. Tools are implemented as type-safe skeletons.
- Full HTTP request execution and test suite are pending.
