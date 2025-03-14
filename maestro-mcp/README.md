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

This module is built using Java 17 and the official MCP Kotlin SDK. It provides a clean implementation
that doesn't affect the Java 8 compatibility of the main Maestro project.


Debug the MCP with the official [MCP Kotlin SDK inspector](https://github.com/modelcontextprotocol/inspector):

```

npx @modelcontextprotocol/inspector mcp-proxy

```

