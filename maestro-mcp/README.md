# Maestro MCP

This module implements a Model Context Protocol (MCP) server for Maestro using the official [MCP Kotlin SDK](https://github.com/modelcontextprotocol/kotlin-sdk).

## Requirements

- Java 17 or higher

## Features

- Standalone MCP server implementation
- Integration with Maestro CLI
- JSON-RPC protocol support
- Resource content delivery

## Usage

Build the module:

```
./gradlew :maestro-mcp:build
```

Install the distribution:

```
./gradlew :maestro-mcp:installDist
```

Run the MCP server:

```
./maestro-mcp/build/install/maestro-mcp/bin/maestro-mcp
```

## Development

This module is built using Java 17 and the official MCP Kotlin SDK, so it's built separately from the rest of Maestro (which required Java 8).

Debug the MCP with the official [MCP Kotlin SDK inspector](https://github.com/modelcontextprotocol/inspector):

un the app in sse mode (`./maestro-mcp/build/install/maestro-mcp/bin/maestro-mcp --sse`)

Open the inspector:
```
npx @modelcontextprotocol/inspector 
```

Connect to the MCP using the URL `http://localhost:13379/sse`