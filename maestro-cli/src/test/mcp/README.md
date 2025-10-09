# MCP Testing Framework

This directory contains testing infrastructure for Maestro's MCP (Model Context Protocol) server.

## Quick Start

```bash
# Test tool functionality (API validation)
./run_mcp_tool_tests.sh ios

# Test LLM behavior evaluations
./run_mcp_evals.sh ios
```

## Testing Types

### Tool Functionality Tests (`run_mcp_tool_tests.sh`)
- **Purpose**: Validate that MCP tools execute without errors and return expected data types
- **Speed**: Fast (no complex setup required)
- **Use case**: CI/CD gating, quick smoke tests during development

### LLM Behavior Evaluations (`run_mcp_evals.sh`)
- **Purpose**: Validate that LLMs can properly use MCP tools to complete tasks
- **Speed**: Slower (includes LLM reasoning evaluation)
- **Use case**: Behavior validation, regression testing of LLM interactions
