# MCP Testing Framework

This directory contains testing infrastructure for Maestro's MCP (Model Context Protocol) server. There are two main types of tests: **tool functionality tests** and **LLM behavior evaluations**.

## Quick Start

```bash
# Test tool functionality (API validation)
./run_tool_tests.sh ios

# Test LLM behavior (basic evals)  
./run_mcp_evals.sh basic-evals.yaml

# Test LLM behavior with app setup (complex evals)
./run_mcp_evals.sh --with-apps view-hierarchy-evals.yaml
```

## Testing Types

### Tool Functionality Tests (`run_tool_tests.sh`)
**Purpose**: Validate that MCP tools execute without errors and return expected data types  
**What it tests**: API functionality - "does this tool work?"  
**Speed**: Fast (no complex setup required)  
**Use case**: CI/CD gating, quick smoke tests during development

### LLM Behavior Evaluations (`run_mcp_evals.sh`)
**Purpose**: Validate that LLMs can properly use MCP tools  
**What it tests**: Tool usage patterns, reasoning, safety, client/server interaction  
**Speed**: Slower (includes LLM reasoning evaluation)  
**Use case**: Behavior validation, regression testing of LLM interactions

## Directory Structure

```
maestro-cli/src/test/mcp/
├── run_tool_tests.sh              # Tool functionality testing
├── run_mcp_evals.sh              # LLM behavior testing  
├── mcp-server-config.json        # Shared MCP configuration
├── modelcontextprotocol-inspector-*.tgz  # MCP inspector tool
│
├── tool-tests/                   # Tool functionality tests
│   ├── test-single-mcp-tool.sh   # Test individual tools
│   ├── test-all-mcp-tools.sh     # Test all tools
│   ├── flow1.yaml               # Simple test flows
│   └── flow2.yaml
│
├── evals/                       # LLM behavior evaluations
│   ├── basic-evals.yaml         # Basic tool usage patterns
│   ├── view-hierarchy-evals.yaml  # View hierarchy optimization
│   └── safety-evals.yaml        # Safety and error handling
│
└── setup/                      # Shared environment setup
    ├── download-and-install-apps.sh  # App installation
    ├── launch-simulator.sh          # Device management
    └── flows/                       # Setup flows
        ├── setup-wikipedia-search-ios.yaml
        ├── setup-wikipedia-search-android.yaml
        └── verify-ready-state.yaml
```

## Setup Utilities

The `setup/` directory provides building blocks for both test types:

### `setup/launch-simulator.sh <platform> [--auto-launch]`
Checks and optionally launches simulators/emulators
```bash
./setup/launch-simulator.sh ios --auto-launch
```

### `setup/download-and-install-apps.sh <platform>`
Downloads and installs test apps using e2e infrastructure
```bash
./setup/download-and-install-apps.sh ios
```

### Setup Flows
- `setup-wikipedia-search-*.yaml`: Navigate to search for rich UI hierarchy
- `verify-ready-state.yaml`: Ensure app is responsive for testing

## Usage Patterns

### Development Workflow
```bash
# Quick tool validation during development
./run_tool_tests.sh ios

# Test specific eval file
./run_mcp_evals.sh my-eval.yaml
```

### Pre-commit Testing
```bash
# Full tool functionality suite
./run_tool_tests.sh ios

# Core LLM behavior validation
./run_mcp_evals.sh basic-evals.yaml
```

### Complex Evaluation Testing
```bash
# View hierarchy optimization testing with full app setup
./run_mcp_evals.sh --with-apps view-hierarchy-evals.yaml

# Multiple evaluations with shared setup
./setup/download-and-install-apps.sh ios
./setup/launch-simulator.sh ios --auto-launch
maestro test setup/flows/setup-wikipedia-search-ios.yaml
./run_mcp_evals.sh eval1.yaml eval2.yaml eval3.yaml
```

## Adding New Tests

### Tool Functionality Test
Add new test cases to `tool-tests/test-all-mcp-tools.sh`

### LLM Behavior Evaluation
Create new `.yaml` file in `evals/` directory following the existing format

### Setup Flow
Add new flows to `setup/flows/` for custom app states or environments

## Prerequisites

- MCP inspector tool (included as `.tgz` file)
- iOS simulator or Android emulator running (for device-dependent tests)
- `MAESTRO_API_KEY` environment variable (for API-dependent tools like `query_docs`)

The framework is designed to be modular and composable, allowing you to mix and match setup utilities based on your testing needs.