# Contributing to Mimicry Claw

## Development Setup

1. Clone the repository
2. Install JDK 21
3. Run `./gradlew build` to verify the setup

## Code Style

- Follow Kotlin coding conventions
- Use descriptive variable and function names
- Add KDoc comments to public APIs
- Keep functions focused and single-responsibility

## Commit Guidelines

- Use imperative mood in commit messages (e.g., "Add tool registry")
- Prefix with scope (e.g., "feat(tools): add bash tool")
- Keep commits atomic and focused

## Pull Requests

1. Create a feature branch from `main`
2. Make your changes
3. Ensure tests pass: `./gradlew test`
4. Submit a PR with a clear description

## Reporting Issues

Use the GitHub issue tracker. Include:
- Steps to reproduce
- Expected behavior
- Actual behavior
- Environment details (OS, JDK version, etc.)
