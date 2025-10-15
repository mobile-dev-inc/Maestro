> [!TIP]
> Ready to wire into CI or scale up your testing? [Run Maestro in the Cloud](https://docs.maestro.dev/cloud)

<p align="center">
  <a href="https://www.maestro.dev">
    <img width="200" alt="Maestro logo" src="https://github.com/user-attachments/assets/91c4f440-288e-4a9e-93a3-3c89a19d2f04" />
  </a>
</p>


<p align="center">
  <strong>Maestro</strong> is an open-source framework that makes UI and end-to-end testing for Android, iOS, and web apps simple and fast.<br/>
  Write your first test in under five minutes using YAML flows and run them on any emulator, simulator, or browser.
</p>

<img src="https://user-images.githubusercontent.com/847683/187275009-ddbdf963-ce1d-4e07-ac08-b10f145e8894.gif" />

---

## Table of Contents

- [Quick Start](#quick-start)
- [Why Maestro?](#why-maestro)
- [Getting Started](#getting-started)
- [Maestro Studio – Test IDE](#maestro-studio--test-ide)
- [Maestro Cloud – Parallel Execution & Scalability](#maestro-cloud--parallel-execution--scalability)
- [Resources & Community](#resources--community)
- [Contributing](#contributing)

---

## Quick Start

Want to see Maestro in action? Run the sample flows included in the `samples` folder.  
In just a few minutes you can install the CLI, clone the repository and exercise a YAML flow against a sample app:

```bash
# install the CLI (macOS, Linux or WSL)
curl -fsSL "https://get.maestro.mobile.dev" | bash

```

```

# clone the repository and run a sample flow
git clone https://github.com/mobile-dev-inc/maestro.git
cd maestro/samples
maestro test android-flow.yaml
```
This spins up the sample app on an emulator, interacts with the UI, and validates elements without manual coding.

---

## Why Maestro?

Maestro combines a human-readable YAML syntax with an interpreted execution engine.  
It lets you write, run, and scale cross-platform end-to-end tests for mobile and web with ease:

- **Cross-platform coverage** – test Android, iOS, and web apps (React Native, Flutter, hybrid) on emulators, simulators, or real devices.  
- **Human-readable YAML flows** – express interactions as commands like `launchApp`, `tapOn`, and `assertVisible`.  
- **Resilience & smart waiting** – built-in flakiness tolerance and automatic waiting handle dynamic UIs without manual `sleep()` calls.  
- **Fast iteration & simple install** – flows are interpreted (no compilation) and installation is a single script.

---
## Getting Started

- [Installing Maestro](https://docs.maestro.dev/getting-started/installing-maestro)
- [Build and install your app](https://docs.maestro.dev/getting-started/build-and-install-your-app)
- [Run a sample flow](https://docs.maestro.dev/getting-started/run-a-sample-flow)
- [Writing your first flow](https://docs.maestro.dev/getting-started/writing-your-first-flow)

---

## Maestro Studio – Test IDE

**Maestro Studio Desktop** is a lightweight IDE that lets you design and execute tests visually — no terminal needed.

- **Simple setup** – just download the native app for macOS, Windows, or Linux.  
- **Visual flow builder & inspector** – record interactions, inspect elements, and build flows visually.  
- **AI assistance** – use MaestroGPT to generate commands and answer questions while authoring tests.

[Download Maestro Studio](https://maestro.dev/?utm_source=github-readme#maestro-studio)

---

## Maestro Cloud – Parallel Execution & Scalability

When your test suite grows, **Maestro Cloud** lets you run flows in parallel on dedicated infrastructure:

- **Run at scale** – execute hundreds of tests in parallel and cut run times by up to 90%.  
- **Built-in notifications** – send results to GitHub PRs, Slack, or email.  
- **Deterministic environments** – stable infrastructure for reproducible tests.  
- **Debugging & device selection** – includes video replay, logs, and device model selection.

👉 [Start your free 7-day trial](https://maestro.dev/cloud?utm_source=github-readme)

---

## Resources & Community

- 📘 [Documentation](https://docs.maestro.dev)  
- 📰 [Blog](https://maestro.dev/blog?utm_source=github-readme) 
- 💬 [Join the Slack Community](https://maestrodev.typeform.com/to/FelIEe8A)
- 🐦 [Follow us on X](https://twitter.com/maestro__dev)

---

## Contributing

Maestro is open-source under the Apache 2.0 license — contributions are welcome!

- Check [good first issues](https://github.com/mobile-dev-inc/maestro/issues?q=is%3Aopen+is%3Aissue+label%3A%22good+first+issue%22)
- Read the [Contribution Guide](https://github.com/mobile-dev-inc/Maestro/blob/main/CONTRIBUTING.md) 
- Fork, create a branch, and open a Pull Request.

If you find Maestro useful, ⭐ star the repository to support the project.

```
  Built with ❤️ by Maestro.dev
```


