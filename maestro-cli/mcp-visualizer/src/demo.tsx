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
  { commandId: "1", yaml: 'launchApp: "com.example.app"', depth: 0, status: "completed" },
  { commandId: "2", yaml: 'tapOn: "Sign in"', depth: 0, status: "completed" },
  { commandId: "3", yaml: 'inputText: "user@example.com"', depth: 0, status: "completed" },
  { commandId: "4", yaml: 'tapOn:\n  id: "submit_button"\n  index: 0', depth: 0, status: "completed" },
  { commandId: "5", yaml: 'assertVisible: "Welcome back, Leland!"', depth: 0, status: "warned" },
  { commandId: "6", yaml: 'scrollUntilVisible:\n  element:\n    text: "Settings"', depth: 0, status: "started" },
  {
    commandId: "7",
    yaml: 'tapOn:\n  text: "A very very very very very long button label that should overflow the panel"',
    depth: 0,
    status: "pending",
  },
  {
    commandId: "8",
    yaml: 'runScript: "evaluate_response.js"',
    depth: 0,
    status: "failed",
    errorMessage: "ReferenceError: response is not defined at evaluate_response.js:14",
  },
  { commandId: "9", yaml: 'takeScreenshot: "after_failure"', depth: 0, status: "skipped" },
  { commandId: "10", yaml: 'stopApp: "com.example.app"', depth: 0, status: "skipped" },
];

export function DemoApp() {
  const [collapsed, setCollapsed] = React.useState(false);
  const [rows, setRows] = React.useState<CommandEntry[]>(DEMO_ROWS);
  return (
    <VisualizerLayout
      rows={rows}
      collapsed={collapsed}
      onToggle={() => setCollapsed((c) => !c)}
      onClear={() => setRows([])}
      deviceState={DEMO_DEVICE}
    />
  );
}
