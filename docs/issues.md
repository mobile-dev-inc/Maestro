# Issues

How to open a good issue, whether you use the form on GitHub or file it some other way, such
as `gh issue create` or an agent. The forms in
[`.github/ISSUE_TEMPLATE/`](../.github/ISSUE_TEMPLATE/) ask for the same things; this page is
for when you're not using them.

Before you open one:

- **Search the [existing issues](https://github.com/mobile-dev-inc/maestro/issues)** — yours
  may already be there. A comment with your details on an existing issue helps more than a
  duplicate.
- **Maestro Studio** (the desktop app) has its own
  [issue tracker](https://github.com/mobile-dev-inc/maestro-studio/issues/new/choose).
- **Questions and discussion** belong in the [Slack community](https://slack.maestro.dev).

Issues here are public permanently. The rules on
[sensitive information](pull-requests.md#sensitive-information-public-repo) apply to issues
too, including to the logs, screenshots, and flows you attach.

## Bug reports

Use these headings, in this order (`### Steps to reproduce` and so on), which is how an issue
filed through the form reads.

- **Steps to reproduce** — a minimal, reproducible example, step by step. Include the device
  you saw it on (physical Android device, iOS simulator, …) and its OS version (e.g. Android 14
  with Play Services, iOS 18). Include the flow file: without it, a bug is much harder to
  reproduce, and issues that can't be reproduced are much more likely to be closed.
- **Actual results** — what happens.
- **Expected results** — what you expected to happen.
- **About app** — the app you're testing: open source (link it) or closed source (share a
  binary or a small sample that reproduces it); native or cross-platform; the framework it's
  built with and its version (e.g. SwiftUI, Compose 1.6, React Native, Flutter 3.22); its
  minimum and target SDK or iOS version, if relevant.
- **About environment** — the machine running Maestro: OS and version, processor architecture
  (x86_64, arm64), Java version (`java -version`), and Xcode version for iOS.
- **Logs** — the full output of the command you ran, as text in a code block, not a
  screenshot. `maestro bugreport` produces a zip you can attach.
- **Maestro version** — from `maestro --version`.
- **How did you install Maestro?** — install script, Homebrew, built from source (give the
  commit), or other.
- **Anything else?** — links, related issues, anything else that gives context.

## Feature requests

- **Use case** — the problem that led you to want this, and the alternatives you've considered.
  Describe the problem, not only the solution you have in mind.
- **Proposal** — what the feature should look like from the user's side, e.g. the YAML you'd
  like to write.
- **Anything else?** — links, related issues, anything else that gives context.
