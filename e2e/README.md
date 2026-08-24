# e2e

This directory contains glue code for testing Maestro itself.

## Testing

Typical workflow is:

1. Start Android emulator and iOS simulator
2. `download_apps`
3. `install_apps`
4. `run_tests`

We try to keep shell code in separate files, so we don't get too tightly coupled
to GitHub Actions.

### Web fixtures

The web flows in `workspaces/web` fetch their pages from a static server on port 7357, serving
`workspaces/web/fixtures`. `run_tests web` starts one and stops it again, so CI needs nothing
extra. To drive a web flow by hand, through the MCP, or in Maestro Studio, make sure one is up
first:

```sh
e2e/ensure_fixtures
maestro --platform web test workspaces/web/date_input.yaml
```

`ensure_fixtures` is idempotent and waits until the server actually answers, so it is safe to
call before every flow — and worth calling, since a flow started too early fails in a way that
points nowhere near the server: `launchApp` succeeds against the dead port because Chrome serves
its own error page, so the flow reports `Element not found` for a correct selector.

It prints the pid of a server it started, and nothing when it reused one, so a caller can stop
only what it started. `serve_fixtures` is the server itself, if you want it in the foreground.
`FIXTURES_PORT` moves the port, and must be set for every command that talks to it.

Pages live in files rather than inline `data:` URLs so they can be read, edited and diffed —
and served from a real origin, which `data:` and `file://` are not.

### Expected failures

Let's say a critical bug is introduced that causes Maestro to always mark all
tests as passed. If our e2e test suite only was only checking if all tests pass
(i.e. `maestro test` exit code is 0), then wouldn't catch such a bug.

To prevent this, all flows in this directory MUST have a `passing` or `failing`
label, so the correct outcome can be asserted.

## Samples

This directory also contains samples that are downloaded by the `maestro download-samples` command,
and some glue code to facilitate updating those samples.

`maestro download-samples` provides a set of flows and apps so that users can
quickly try out Maestro, without having to write any flows for their own app.

`download-samples` downloads these files and apps from our publicly-available
Google Cloud Storage bucket (hosted on `storage.googleapis.com`).

### Intro

The samples are automatically updated by the GitHub Action on every new commit
to the `main` branch.

There zip archive that is downloaded by `download-samples` consists of 2 things:
- the Maestro workspace with flows (located in the `workspaces/wikipedia` directory)
- the app binary files that are used in the flows (located in the `apps` directory)

App binary files are heavy, so we don't store them in the repository. Instead, they are hosted
on publicly available directory in Google Cloud Storage:

### Update the samples

Run the script:

```console
./update_samples
```
