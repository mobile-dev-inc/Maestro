# maestro-ai

This project implements AI support for use in Maestro.

It's both a library and an executable demo app.

## Demo app

An API key is required. Set it with `MAESTRO_CLI_AI_KEY` env var. Examples:

- OpenAI: `export MAESTRO_CLI_AI_KEY=sk-...`
- Antrophic: `export MAESTRO_CLI_AI_KEY=sk-ant-api-...`

### Build

```console
./gradlew :maestro-ai:installDist
```

The startup script will be generated in `./maestro-ai/build/install/maestro-ai-demo/bin/maestro-ai-demo`.

### How to use

First of all, try out the `--help` flag.

Run test for a single screenshot that contains defects (i.e. is bad):

```console
maestro-ai-demo foo_1_bad.png
```

Run tests for all screenshots from the Uber that contain defects (i.e. are bad). Additionally, show prompts and raw
LLM response:

```console
maestro-ai-demo \
  --model gpt-4o-2024-08-06 \
  --show-prompts \
  --show-raw-response \
  test-ai-fixtures/uber_*_bad.png
```
