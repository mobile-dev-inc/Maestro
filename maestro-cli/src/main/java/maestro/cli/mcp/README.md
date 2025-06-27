# Maestro MCP Server

## Overview

The Maestro MCP (Model Context Protocol) server enables LLM-driven automation and orchestration of Maestro commands and device management. It serves two primary functions for calling LLMs:
- enables LLMs to direct control and interact with devices using Maestro device capabilities
- enables LLMs to write, validate, and run Maestro code (flows)

The MCP server is designed to be extensible, maintainable, and easy to run as part of the Maestro CLI. It supports real-time device management, app automation, and more, all via a standardized protocol.

## Features

- Exposes Maestro device and automation commands as MCP tools
- Supports listing, launching, and interacting with devices
- Supports running flow yaml or files and checking the flow file syntax
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
1. Create a new file in `maestro-cli/src/main/java/maestro/cli/mcp/tools/` following the same patterns as the other tools.
2. Add your tool to the `addTools` call in `McpServer.kt`
3. Build the CLI with `./gradlew :maestro-cli:installDist`
4. Test your tool by running `./maestro-cli/src/test/mcp/test-mcp-tool.sh` with appropriate args for your tool

## Implementation Notes & Rationale

### Using forked version of official kotlin MCP SDK

The [official MCP Kotlin SDK](https://github.com/modelcontextprotocol/kotlin-sdk) can't be used directly because it requires Java 21 and Kotlin 2.x, while Maestro is built on Java 8 and Kotlin 1.8.x for broad compatibility. However, we want to be able to benefit from features added to the SDK since the MCP spec is changing rapiadly. So we created a fork that "downgrades" the reference SDK to Java 8 and Kotlin 1.8.22.


### Why Integrate MCP Server Directly Into `maestro-cli`?

- **Dependency Management:** The MCP server needs access to abstractions like `MaestroSessionManager` and other CLI internals. Placing it in a separate module (e.g., `maestro-mcp`) would create a circular dependency between `maestro-cli` and the new module.
- **Simplicity:** Keeping all MCP logic within `maestro-cli` avoids complex build configurations and makes the integration easier to maintain and review.
- **Extensibility:** This approach allows new tools to be added with minimal boilerplate and direct access to CLI features.

### Potential Future Improvements

- **Shared Abstractions:** If more MCP-related code or other integrations are needed, consider extracting shared abstractions (e.g., session management, tool interfaces) into a `common` or `core` module. This would allow for a clean separation and potentially enable a standalone `maestro-mcp` module.
- **Streamable HTTP:** This MCP server currently only uses STDIO for communication.

