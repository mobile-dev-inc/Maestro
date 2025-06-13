# Maestro MCP Server

## Overview

The Maestro MCP (Model Context Protocol) server enables LLM-driven automation and orchestration of Maestro commands and device management. It exposes Maestro's capabilities as a set of tools accessible via the MCP protocol, allowing integration with LLM agents, IDEs, etc.

The MCP server is designed to be extensible, maintainable, and easy to run as part of the Maestro CLI. It supports real-time device management, app automation, and more, all via a standardized protocol.

## Features

- Exposes Maestro device and automation commands as MCP tools
- Supports listing, launching, and interacting with devices
- Easily extensible: add new tools with minimal boilerplate
- Includes a test script and config for automated validation

## Running the MCP Server

To use the MCP server as an end user, after following the maestro install instructions run:

```
maestro mcp
```

This launches the MCP server via the Maestro CLI, exposing Maestro tools over STDIO for LLM agents and other clients.

## Developing

## Extending the MCP Server

To add a new tool:
1. Create a new file in `maestro-cli/src/main/java/maestro/cli/mcp/tools/` subclassing `McpTool` or `McpCommandTool`.
2. Register your tool in `McpServer.kt` by adding `register(YourNewTool(sessionManager))` to the tool registry.
3. Build the CLI with `./gradlew :maestro-cli:installDist`
4. Test your tool by running `./maestro-cli/src/test/mcp/test-mcp-tool.sh`

## Implementation Notes & Rationale

### Why Not Use the Official MCP Kotlin SDK?

The [official MCP Kotlin SDK](https://github.com/modelcontextprotocol/kotlin-sdk) was considered as a starting point. However, it was not used for the following reasons:
- **Version Incompatibility:** The SDK requires Java 21 and Kotlin 2.x, while Maestro is built on Java 8 and Kotlin 1.8.x for broad compatibility.
- **Spec Lag:** At the time of integration, the SDK lagged behind the latest MCP protocol specification, which would have required significant patching or forking.
- **Minimalism:** Only a small subset of the SDK's functionality was needed, so a minimal, self-contained implementation was preferred for maintainability and reviewability.

### Why Integrate MCP Server Directly Into `maestro-cli`?

- **Dependency Management:** The MCP server needs access to abstractions like `MaestroSessionManager` and other CLI internals. Placing it in a separate module (e.g., `maestro-mcp`) would create a circular dependency between `maestro-cli` and the new module.
- **Simplicity:** Keeping all MCP logic within `maestro-cli` avoids complex build configurations and makes the integration easier to maintain and review.
- **Extensibility:** This approach allows new tools to be added with minimal boilerplate and direct access to CLI features.

### Potential Future Refactorings

- **Shared Abstractions:** If more MCP-related code or other integrations are needed, consider extracting shared abstractions (e.g., session management, tool interfaces) into a `common` or `core` module. This would allow for a clean separation and potentially enable a standalone `maestro-mcp` module.
- **Standalone Binary:** The MCP server could be refactored into its own binary, decoupled from the main CLI, for use cases where a dedicated MCP endpoint is desired.

