> [!TIP]
> Great things happen when testers connect — [Join the Maestro Community](https://maestrodev.typeform.com/to/FelIEe8A)


<p align="center">
  <a href="https://www.maestro.dev">
    <img width="1200" alt="Maestro logo" src="https://github.com/mobile-dev-inc/Maestro/blob/main/assets/banne_logo.png" />
  </a>
</p>


<p align="center">
  <strong>Maestro</strong> is an open-source framework that makes UI and end-to-end testing for Android, iOS, and web apps simple and fast.<br/>
  Write your first test in under five minutes using YAML flows and run them on any emulator, simulator, or browser.
</p>

<img src="https://user-images.githubusercontent.com/847683/187275009-ddbdf963-ce1d-4e07-ac08-b10f145e8894.gif" />

---

## Table of Contents

- [Why Maestro?](#why-maestro)
- [Getting Started](#getting-started)
- [Resources & Community](#resources--community)
- [Contributing](#contributing)
- [Maestro Studio – Test IDE](#maestro-studio--test-ide)
- [Maestro Cloud – Parallel Execution & Scalability](#maestro-cloud--parallel-execution--scalability)


---

## Why Maestro?

Maestro is built on learnings from its predecessors (Appium, Espresso, UIAutomator, XCTest, Selenium, Playwright) and allows you to easily define and test your Flows.

By combining a human-readable YAML syntax with an interpreted execution engine, it lets you write, run, and scale cross-platform end-to-end tests for mobile and web with ease.

- **Cross-platform coverage** – test Android, iOS, and web apps (React Native, Flutter, hybrid) on emulators, simulators, or real devices.  
- **Human-readable YAML flows** – express interactions as commands like `launchApp`, `tapOn`, and `assertVisible`.  
- **Resilience & smart waiting** – built-in flakiness tolerance and automatic waiting handle dynamic UIs without manual `sleep()` calls.  
- **Fast iteration & simple install** – flows are interpreted (no compilation) and installation is a single script.

**Simple Example:**
```
# flow_contacts_android.yaml

appId: com.android.contacts
---
- launchApp
- tapOn: "Create new contact"
- tapOn: "First Name"
- inputText: "John"
- tapOn: "Last Name"
- inputText: "Snow"
- tapOn: "Save"
```

---
## Getting Started

Maestro requires Java 17 or higher to be installed on your system. You can verify your Java version by running:

```
java -version
```

Installing the CLI:

Run the following command to install Maestro on macOS, Linux or Windows (WSL):

```
curl -fsSL "https://get.maestro.mobile.dev" | bash
```

The links below will guide you through the next steps.

- [Installing Maestro](https://docs.maestro.dev/getting-started/installing-maestro) (includes regular Windows installation)
- [Build and install your app](https://docs.maestro.dev/getting-started/build-and-install-your-app)
- [Run a sample flow](https://docs.maestro.dev/getting-started/run-a-sample-flow)
- [Writing your first flow](https://docs.maestro.dev/getting-started/writing-your-first-flow)


---

## Resources & Community

- 💬 [Join the Slack Community](https://maestrodev.typeform.com/to/FelIEe8A)
- 📘 [Documentation](https://docs.maestro.dev)  
- 📰 [Blog](https://maestro.dev/blog?utm_source=github-readme) 
- 🐦 [Follow us on X](https://twitter.com/maestro__dev)

---

## Contributing

Maestro is open-source under the Apache 2.0 license — contributions are welcome!

- Check [good first issues](https://github.com/mobile-dev-inc/maestro/issues?q=is%3Aopen+is%3Aissue+label%3A%22good+first+issue%22)
- Read the [Contribution Guide](https://github.com/mobile-dev-inc/Maestro/blob/main/CONTRIBUTING.md) 
- Fork, create a branch, and open a Pull Request.

If you find Maestro useful, ⭐ star the repository to support the project.

---

## Maestro Studio – Test IDE

**Maestro Studio Desktop** is a lightweight IDE that lets you design and execute tests visually — no terminal needed. 
It is also free, even though Studio is not an open-source project. So you won't find the Maestro Studio code here.

- **Simple setup** – just download the native app for macOS, Windows, or Linux.  
- **Visual flow builder & inspector** – record interactions, inspect elements, and build flows visually.  
- **AI assistance** – use MaestroGPT to generate commands and answer questions while authoring tests.

[Download Maestro Studio](https://maestro.dev/?utm_source=github-readme#maestro-studio)

---

## Maestro Cloud – Parallel Execution & Scalability

When your test suite grows, run hundreds of tests in parallel on dedicated infrastructure, cutting execution times by up to 90%. Includes built-in notifications, deterministic environments, and complete debugging tools.

Pricing for Maestro Cloud is completely transparent and can be found on the [pricing page](https://maestro.dev/pricing?utm_source=github-readme).

👉 [Start your free 7-day trial](https://maestro.dev/cloud?utm_source=github-readme)



```
  Built with ❤️ by Maestro.dev
```


