import React from "react";
import { VisualizerLayout, type CommandEntry, type DeviceState } from "./main";

// SVG placeholder shaped like a phone screen so the dark device frame renders at a
// realistic size without a real simulator stream.
const PLACEHOLDER_STREAM =
  "data:image/svg+xml;utf8," +
  encodeURIComponent(
    `<svg xmlns='http://www.w3.org/2000/svg' width='320' height='700' viewBox='0 0 320 700'>` +
      `<rect width='100%' height='100%' fill='#1c1917' />` +
      `<text x='160' y='350' text-anchor='middle' fill='#737373' ` +
      `font-family='monospace' font-size='14'>device placeholder</text>` +
    `</svg>`,
  );

const DEMO_DEVICE: DeviceState = {
  status: "streaming",
  platform: "android",
  streamUrl: PLACEHOLDER_STREAM,
};

// Static fixture for iterating on CommandsPanel design — visit /?demo to render
// this instead of the live app. Cover every status plus a few layout-stress cases
// (long lines, multi-line YAML, error messages) so visual changes can be eyeballed
// against realistic content.
const DEMO_ROWS: CommandEntry[] = [
  { commandId: "1", yaml: 'clearState: "com.example.app"', depth: 0, status: "completed" },
  { commandId: "2", yaml: 'launchApp: "com.example.app"', depth: 0, status: "completed" },
  { commandId: "3", yaml: 'tapOn: "Get started"', depth: 0, status: "completed" },
  { commandId: "4", yaml: 'tapOn: "Continue with email"', depth: 0, status: "completed" },
  { commandId: "5", yaml: 'assertVisible: "Create your account"', depth: 0, status: "completed" },
  { commandId: "6", yaml: 'inputText: "Leland"', depth: 0, status: "completed" },
  { commandId: "7", yaml: 'tapOn: "Next"', depth: 0, status: "completed" },
  { commandId: "8", yaml: 'inputText: "user@example.com"', depth: 0, status: "completed" },
  { commandId: "9", yaml: 'tapOn: "Next"', depth: 0, status: "completed" },
  { commandId: "10", yaml: 'tapOn: "Accept terms"', depth: 0, status: "completed" },
  { commandId: "11", yaml: 'launchApp: "com.example.app"', depth: 0, status: "completed" },
  { commandId: "12", yaml: 'tapOn: "Sign in"', depth: 0, status: "completed" },
  { commandId: "13", yaml: 'inputText: "user@example.com"', depth: 0, status: "completed" },
  { commandId: "14", yaml: 'tapOn:\n  id: "submit_button"\n  index: 0', depth: 0, status: "completed" },
  { commandId: "15", yaml: 'assertVisible: "Welcome back, Leland!"', depth: 0, status: "warned" },
  { commandId: "16", yaml: 'scrollUntilVisible:\n  element:\n    text: "Settings"', depth: 0, status: "started" },
  {
    commandId: "17",
    yaml: 'tapOn:\n  text: "A very very very very very long button label that should overflow the panel"',
    depth: 0,
    status: "pending",
  },
  { commandId: "18", yaml: 'assertVisible: "Notifications"', depth: 0, status: "pending" },
  { commandId: "19", yaml: 'tapOn: "Notifications"', depth: 0, status: "pending" },
  { commandId: "20", yaml: 'tapOn:\n  id: "enable_push"', depth: 0, status: "pending" },
  { commandId: "21", yaml: 'assertVisible: "All set"', depth: 0, status: "pending" },
  { commandId: "22", yaml: 'back', depth: 0, status: "pending" },
  { commandId: "23", yaml: 'tapOn: "Account"', depth: 0, status: "pending" },
  {
    commandId: "24",
    yaml: 'runScript: "evaluate_response.js"',
    depth: 0,
    status: "failed",
    errorMessage: "ReferenceError: response is not defined at evaluate_response.js:14",
  },
  { commandId: "25", yaml: 'takeScreenshot: "after_failure"', depth: 0, status: "skipped" },
  { commandId: "26", yaml: 'stopApp: "com.example.app"', depth: 0, status: "skipped" },
];

const NO_DEVICE: DeviceState = {
  status: "idle",
  message: "Maestro MCP will automatically connect when interacting with a device.",
};

export function DemoApp() {
  const [collapsed, setCollapsed] = React.useState(false);
  const [running, setRunning] = React.useState(true);
  const [connected, setConnected] = React.useState(true);
  // Derived: when "running" is off, swap any started row to pending so the panel
  // and device-frame chrome render their idle state.
  const rows = running
    ? DEMO_ROWS
    : DEMO_ROWS.map((r) => (r.status === "started" ? { ...r, status: "pending" as const } : r));
  return (
    <>
      <VisualizerLayout
        rows={connected ? rows : []}
        collapsed={collapsed}
        onToggle={() => setCollapsed((c) => !c)}
        onClear={() => {}}
        deviceState={connected ? DEMO_DEVICE : NO_DEVICE}
      />
      <div className="fixed bottom-4 right-4 z-10 flex flex-col gap-1.5 rounded-md border border-neutral-300 bg-white/95 px-3 py-2 text-xs text-neutral-700 shadow-md backdrop-blur dark:border-neutral-700 dark:bg-neutral-900/95 dark:text-neutral-200">
        <label className="flex cursor-pointer select-none items-center gap-2">
          <input type="checkbox" checked={connected} onChange={(e) => setConnected(e.target.checked)} />
          device connected
        </label>
        <label className={`flex cursor-pointer select-none items-center gap-2 ${connected ? "" : "opacity-40"}`}>
          <input type="checkbox" checked={running} disabled={!connected} onChange={(e) => setRunning(e.target.checked)} />
          running
        </label>
      </div>
    </>
  );
}
