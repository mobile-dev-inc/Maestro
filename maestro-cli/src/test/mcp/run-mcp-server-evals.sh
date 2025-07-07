# Run the MCP server evals
#
# This script runs MCP server evals using the @modelcontextprotocol/inspector tool. Usually we could just run it using `npx @modelcontextprotocol/inspector`,
# but that version does not have eval support (yet). Until then, you'll need to follow these steps to build a binary to use in the Maestro repo:
# - clone the inspector from https://github.com/steviec/inspector
# - copy the cli/package.json dependencies to the root package.json (super annoying; you can't use `npx github:steviec/inspector` syntax and have it correctly install dependencies in workspaces)
# - run `npm pack` to create a tarball
# - copy the tarball to this directory
#
# If the evals framework doesn't get accepted into main, we'll create a bonafide published tool that can be installed via `npx` from a different location.

TEST_DIR="maestro-cli/src/test/mcp/"
CONFIG="$TEST_DIR/mcp-server-config.json"
EVALS="$TEST_DIR/maestro-evals.yaml"

npx -y $TEST_DIR/modelcontextprotocol-inspector-0.15.0.tgz --cli --evals $EVALS --config $CONFIG --server maestro-mcp