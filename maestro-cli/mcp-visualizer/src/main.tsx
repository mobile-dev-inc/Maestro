import React from "react";
import { createRoot } from "react-dom/client";
import "./styles.css";

type CommandStatus = "pending" | "started" | "completed" | "failed" | "warned" | "skipped";
type DriverStatus = "started" | "completed" | "failed";
/** Normalized [0, 1] coordinates within the device's screen. */
type Point2D = { x: number; y: number };

type CommandEntry = {
  commandId: string;
  yaml: string;
  /** 0 = top-level; >0 = nested. Today we only render top-level rows. */
  depth: number;
  status: CommandStatus;
  errorMessage?: string | null;
};

type VisualizerEvent =
  | { type: "maestro.connected"; platform: string; deviceId: string }
  | { type: "maestro.flow_state"; commands: CommandEntry[] }
  | { type: "driver.tap"; status: DriverStatus; point: Point2D }
  | {
      type: "driver.swipe";
      status: DriverStatus;
      start: Point2D;
      end: Point2D;
      durationMs: number;
    }
  | { type: "driver.input_text"; status: DriverStatus; textLength: number };

type DeviceState = {
  status: "idle" | "starting" | "streaming" | "error";
  platform?: string;
  deviceId?: string;
  streamUrl?: string;
  message?: string;
};

type StreamDeviceType = "android" | "android_device" | "ios";

type DeviceTarget = {
  platform: string;
  deviceType: StreamDeviceType;
  deviceId: string;
};

type OverlayPoint = {
  x: number;
  y: number;
};

type DeviceOverlayBase = {
  id: string;
  timestampMs: number;
  durationMs: number;
  expiresAt: number;
};

type DeviceOverlay =
  | DeviceOverlayBase & {
    kind: "tap";
    point: OverlayPoint;
  }
  | DeviceOverlayBase & {
    kind: "swipe";
    start: OverlayPoint;
    end: OverlayPoint;
  }
  | DeviceOverlayBase & {
    kind: "input_text";
    text: string;
  };

const TAP_ANIMATION_DURATION_MS = 200;
const TAP_RADIUS_START = 15;
const TAP_RADIUS_END = 30;
const SWIPE_FINGER_RADIUS = 20;

function clampPoint(point: Point2D): OverlayPoint {
  return {
    x: Math.max(0, Math.min(1, point.x)),
    y: Math.max(0, Math.min(1, point.y)),
  };
}
// Snapshot-based merge: the backend publishes the current flow's full command list
// on every change. Rows from prior flows linger because their commandIds aren't in
// the new snapshot — we keep what we have and overwrite anything the snapshot covers.
// commandIds come from a process-monotonic counter, so numeric sort is chronological.
function applyFlowState(rows: CommandEntry[], snapshot: CommandEntry[]): CommandEntry[] {
  const next = new Map(rows.map((r) => [r.commandId, r]));
  for (const c of snapshot) next.set(c.commandId, c);
  return [...next.values()].sort((a, b) => Number(a.commandId) - Number(b.commandId));
}

function MaestroLogo({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 180 180" className={className} aria-hidden="true">
      <path
        d="M146.478 30H29V151H80.1327C74.8347 149.081 70.256 145.577 67.0185 140.965C63.781 136.353 62.0417 130.856 62.0369 125.221C62.0344 121.613 62.7429 118.039 64.1219 114.705C65.5009 111.371 67.5234 108.341 70.0738 105.789C72.6243 103.237 75.6526 101.212 78.9859 99.8307C82.3191 98.4493 85.8919 97.7383 89.5 97.7383C96.7836 97.7383 103.769 100.632 108.919 105.782C114.07 110.932 116.963 117.918 116.963 125.201C116.958 130.836 115.219 136.333 111.981 140.945C108.744 145.557 104.165 149.061 98.8672 150.981H150V30H146.478Z"
        fill="currentColor"
      />
    </svg>
  );
}

function StatusIcon({ status }: { status: CommandStatus }) {
  const common = "mt-[2px] h-4 w-4 shrink-0 stroke-current";
  switch (status) {
    case "pending":
      return (
        <svg className={`${common} text-neutral-400`} fill="none" viewBox="0 0 16 16" aria-hidden="true">
          <circle cx="8" cy="8" r="5" stroke="currentColor" strokeWidth="1.5" />
        </svg>
      );
    case "started":
      return (
        <svg className={`${common} animate-spin text-sky-700`} viewBox="0 0 16 16" fill="none" aria-hidden="true">
          <circle cx="8" cy="8" r="6" stroke="currentColor" strokeWidth="2" strokeOpacity="0.25" />
          <path d="M14 8a6 6 0 0 0-6-6" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
        </svg>
      );
    case "completed":
      return (
        <svg className={`${common} text-emerald-500`} fill="none" viewBox="0 0 16 16" aria-hidden="true">
          <path d="M3.75 8.25 6.75 11.25 12.25 5" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
      );
    case "failed":
      return (
        <svg className={`${common} text-red-500`} fill="none" viewBox="0 0 16 16" aria-hidden="true">
          <path d="M5 11 11 5M11 11 5 5" strokeWidth="2" strokeLinecap="round" />
        </svg>
      );
    case "warned":
      return (
        <svg className={`${common} text-amber-500`} fill="none" viewBox="0 0 16 16" aria-hidden="true">
          <path d="M8 4.5v5M8 11.5h.01" strokeWidth="2" strokeLinecap="round" />
        </svg>
      );
    case "skipped":
      return (
        <svg className={`${common} text-neutral-400`} fill="none" viewBox="0 0 16 16" aria-hidden="true">
          <path d="M4 8h8" strokeWidth="2" strokeLinecap="round" />
        </svg>
      );
  }
}

function asYamlListItem(yaml: string): string {
  const lines = yaml.split("\n");
  return lines.map((line, i) => (i === 0 ? `- ${line}` : `  ${line}`)).join("\n");
}

function CommandsPanel({
  rows,
  collapsed,
  onToggle,
  onClear,
}: {
  rows: CommandEntry[];
  collapsed: boolean;
  onToggle: () => void;
  onClear: () => void;
}) {
  // The backend sends every command in the tree (top-level + nested). We only render
  // top-level rows today; nested rendering is a future UX decision that lives here.
  const visibleRows = rows.filter((r) => r.depth === 0);
  const listRef = React.useRef<HTMLOListElement | null>(null);

  // Snap to the very bottom (past the list's bottom padding) on every rows update.
  // `rows` reference changes on every event so status flips and late-arriving error
  // messages also trigger a re-scroll.
  React.useEffect(() => {
    const el = listRef.current;
    if (!el) return;
    el.scrollTop = el.scrollHeight;
  }, [visibleRows]);

  if (collapsed) {
    return (
      <aside className="flex h-full shrink-0 flex-col items-center gap-2 border-r border-neutral-200 bg-neutral-50 py-2">
        <button
          type="button"
          onClick={onToggle}
          aria-label="Expand Maestro MCP"
          title="Expand Maestro MCP"
          className="grid h-7 w-7 place-items-center rounded text-neutral-500 transition hover:bg-neutral-200 hover:text-neutral-800"
        >
          <ChevronIcon direction="right" />
        </button>
        <MaestroLogo className="h-4 w-4 text-neutral-700" />
        <span
          className="rotate-180 select-none text-[10px] font-semibold uppercase tracking-[0.18em] text-neutral-500"
          style={{ writingMode: "vertical-rl" }}
        >
          Maestro MCP
        </span>
      </aside>
    );
  }

  return (
    <aside className="flex h-full w-80 shrink-0 flex-col border-r border-neutral-200 bg-neutral-50">
      <header className="flex h-8 shrink-0 items-center justify-between border-b border-neutral-200 px-2">
        <h2 className="flex items-center gap-1.5 text-[10px] font-semibold uppercase tracking-[0.18em] text-neutral-700">
          <MaestroLogo className="h-3.5 w-3.5 text-neutral-900" />
          Maestro MCP
        </h2>
        <div className="flex items-center gap-0.5">
          <button
            type="button"
            onClick={onClear}
            disabled={visibleRows.length === 0}
            aria-label="Clear log"
            title="Clear log"
            className="grid h-6 w-6 place-items-center rounded text-neutral-500 transition hover:bg-neutral-200 hover:text-neutral-800 disabled:cursor-not-allowed disabled:opacity-40 disabled:hover:bg-transparent disabled:hover:text-neutral-500"
          >
            <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" className="h-3.5 w-3.5">
              <path d="M3 4.5h10" />
              <path d="M6.5 4.5V3.5a1 1 0 0 1 1-1h1a1 1 0 0 1 1 1v1" />
              <path d="M4.5 4.5l.5 8a1 1 0 0 0 1 .9h4a1 1 0 0 0 1-.9l.5-8" />
            </svg>
          </button>
          <button
            type="button"
            onClick={onToggle}
            aria-label="Collapse Maestro MCP"
            title="Collapse Maestro MCP"
            className="grid h-6 w-6 place-items-center rounded text-neutral-500 transition hover:bg-neutral-200 hover:text-neutral-800"
          >
            <ChevronIcon direction="left" />
          </button>
        </div>
      </header>
      <div className="min-h-0 flex-1 overflow-hidden font-mono text-sm leading-5 text-neutral-600">
        {visibleRows.length === 0 ? (
          <p className="px-3 pt-2 text-neutral-400">Commands executed by Maestro MCP</p>
        ) : (
          <ol ref={listRef} className="m-0 h-full list-none overflow-y-auto pl-2 pt-2 pb-8 [&>li]:mt-0">
            {visibleRows.map((row) => {
              const running = row.status === "started";
              return (
                <li
                  key={row.commandId}
                  data-command-id={row.commandId}
                  className={
                    // Keep rounded-l on every row so the running highlight's left corners
                    // don't snap from rounded to square mid-fade when the status flips and
                    // transition-colors animates bg from sky-900 back to transparent.
                    "flex gap-2 rounded-l-xl py-0.5 pl-1.5 pr-2 leading-5 transition-colors " +
                    (running
                      ? "bg-sky-100 text-sky-900"
                      : row.status === "failed"
                        ? "text-neutral-900"
                        : "")
                  }
                >
                  <span className="sr-only">{row.status}</span>
                  <StatusIcon status={row.status} />
                  <div className="min-w-0 flex-1 leading-5">
                    <pre className="m-0 whitespace-pre overflow-x-auto leading-[inherit]">{asYamlListItem(row.yaml)}</pre>
                    {row.errorMessage && row.status === "failed" ? (
                      <p className="mt-0 text-xs leading-4 text-red-600">{row.errorMessage}</p>
                    ) : null}
                  </div>
                </li>
              );
            })}
          </ol>
        )}
      </div>
    </aside>
  );
}

function ChevronIcon({ direction }: { direction: "left" | "right" }) {
  return (
    <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" className="h-3.5 w-3.5">
      {direction === "left" ? <path d="M10 4 6 8l4 4" /> : <path d="M6 4l4 4-4 4" />}
    </svg>
  );
}

function overlayFromEvent(event: VisualizerEvent): DeviceOverlay | undefined {
  const timestampMs = Date.now();
  const id = `${event.type}-${timestampMs}`;

  if (event.type === "driver.tap" && event.status === "started") {
    return {
      id,
      kind: "tap",
      point: clampPoint(event.point),
      timestampMs,
      durationMs: TAP_ANIMATION_DURATION_MS,
      expiresAt: timestampMs + TAP_ANIMATION_DURATION_MS,
    };
  }

  if (event.type === "driver.swipe" && event.status === "started") {
    return {
      id,
      kind: "swipe",
      start: clampPoint(event.start),
      end: clampPoint(event.end),
      timestampMs,
      durationMs: event.durationMs,
      expiresAt: timestampMs + event.durationMs,
    };
  }

  if (event.type === "driver.input_text" && event.status === "started") {
    return {
      id,
      kind: "input_text",
      text: `input text · ${event.textLength} chars`,
      timestampMs,
      durationMs: 1_200,
      expiresAt: timestampMs + 1_200,
    };
  }

  return undefined;
}

function pink(opacity: number) {
  return `oklch(71.8% 0.202 349.761 / ${opacity})`;
}

function drawTap(ctx: CanvasRenderingContext2D, tap: Extract<DeviceOverlay, { kind: "tap" }>, currentTimeMs: number) {
  const progress = (currentTimeMs - tap.timestampMs) / TAP_ANIMATION_DURATION_MS;
  if (progress < 0 || progress > 1) return;

  const x = tap.point.x * ctx.canvas.width;
  const y = tap.point.y * ctx.canvas.height;
  const radius = TAP_RADIUS_START + progress * (TAP_RADIUS_END - TAP_RADIUS_START);

  ctx.beginPath();
  ctx.arc(x, y, radius, 0, Math.PI * 2);
  ctx.fillStyle = pink(1);
  ctx.fill();
  ctx.strokeStyle = pink(1);
  ctx.lineWidth = 5;
  ctx.stroke();
}

function drawSwipe(ctx: CanvasRenderingContext2D, swipe: Extract<DeviceOverlay, { kind: "swipe" }>, currentTimeMs: number) {
  const progress = (currentTimeMs - swipe.timestampMs) / swipe.durationMs;
  if (progress < 0 || progress > 1) return;

  const startX = swipe.start.x * ctx.canvas.width;
  const startY = swipe.start.y * ctx.canvas.height;
  const endX = swipe.end.x * ctx.canvas.width;
  const endY = swipe.end.y * ctx.canvas.height;
  const deltaX = endX - startX;
  const deltaY = endY - startY;
  const fingerHeight = SWIPE_FINGER_RADIUS * 2;
  const swipeDistance = Math.sqrt(deltaX * deltaX + deltaY * deltaY);
  const stretchAmount = progress * swipeDistance;
  const fingerWidth = fingerHeight + stretchAmount;
  const angle = Math.atan2(deltaY, deltaX);

  ctx.save();
  ctx.translate(startX, startY);
  ctx.rotate(angle);

  ctx.beginPath();
  ctx.roundRect(-fingerHeight / 2, -fingerHeight / 2, fingerWidth, fingerHeight, fingerHeight / 2);
  ctx.fillStyle = pink(0.7);
  ctx.fill();
  ctx.strokeStyle = pink(1);
  ctx.lineWidth = 3;
  ctx.stroke();

  ctx.restore();
}

function DeviceOverlayCanvas({ overlays }: { overlays: DeviceOverlay[] }) {
  const canvasRef = React.useRef<HTMLCanvasElement | null>(null);

  React.useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return undefined;

    let animationFrame = 0;

    function draw() {
      const rect = canvas.getBoundingClientRect();
      const width = Math.max(1, Math.round(rect.width));
      const height = Math.max(1, Math.round(rect.height));
      if (canvas.width !== width) canvas.width = width;
      if (canvas.height !== height) canvas.height = height;

      const ctx = canvas.getContext("2d");
      if (!ctx) return;

      ctx.clearRect(0, 0, canvas.width, canvas.height);
      const now = Date.now();
      for (const overlay of overlays) {
        if (overlay.kind === "tap") drawTap(ctx, overlay, now);
        if (overlay.kind === "swipe") drawSwipe(ctx, overlay, now);
      }

      if (overlays.some((overlay) => overlay.kind !== "input_text" && overlay.expiresAt > now)) {
        animationFrame = window.requestAnimationFrame(draw);
      }
    }

    draw();
    return () => window.cancelAnimationFrame(animationFrame);
  }, [overlays]);

  return <canvas ref={canvasRef} className="absolute inset-0 h-full w-full" />;
}

function InputTextOverlay({ overlay }: { overlay: Extract<DeviceOverlay, { kind: "input_text" }> }) {
  return (
    <div
      className="absolute bottom-8 left-1/2 -translate-x-1/2 rounded-full px-3 py-1 text-xs font-semibold text-white shadow"
      style={{ backgroundColor: pink(0.95) }}
    >
      {overlay.text}
    </div>
  );
}

// We send raw touch Down/Move/Up rather than synthesizing tap/swipe — the simulator
// interprets short presses as taps and drags as swipes natively, which feels more
// responsive than driver-mediated gestures.
type InputCommand =
  | { kind: "touch"; action: "Down" | "Move" | "Up"; x: number; y: number }
  | { kind: "key"; action: "Down" | "Up"; code: number }
  | { kind: "button"; action: "Down" | "Up"; name: string };

let pendingMove: InputCommand | null = null;

function formatInput(cmd: InputCommand): string {
  switch (cmd.kind) {
    case "touch": return `touch ${cmd.action} ${cmd.x},${cmd.y}`;
    case "key": return `key ${cmd.action} ${cmd.code}`;
    case "button": return `button ${cmd.action} ${cmd.name}`;
  }
}

function dispatchInput(cmd: InputCommand) {
  fetch("/api/device/input", {
    method: "POST",
    headers: { "Content-Type": "text/plain" },
    body: formatInput(cmd),
  }).catch(() => {});
}

function flushInput() {
  if (!pendingMove) return;
  const cmd = pendingMove;
  pendingMove = null;
  dispatchInput(cmd);
}

// Coalesce rapid Move events to one per animation frame so we don't flood the server
// and starve the MJPEG stream.
function sendInput(cmd: InputCommand) {
  if (cmd.kind === "touch" && cmd.action === "Move") {
    pendingMove = cmd;
    return;
  }
  if (pendingMove) flushInput();
  dispatchInput(cmd);
}

function useInputFlushLoop() {
  React.useEffect(() => {
    let raf = 0;
    const tick = () => {
      flushInput();
      raf = window.requestAnimationFrame(tick);
    };
    raf = window.requestAnimationFrame(tick);
    return () => window.cancelAnimationFrame(raf);
  }, []);
}

// USB HID Usage IDs (HID Usage Tables §10), as expected by simulator-server.
const HID: Record<string, number> = {
  KeyA: 4, KeyB: 5, KeyC: 6, KeyD: 7, KeyE: 8, KeyF: 9, KeyG: 10, KeyH: 11,
  KeyI: 12, KeyJ: 13, KeyK: 14, KeyL: 15, KeyM: 16, KeyN: 17, KeyO: 18, KeyP: 19,
  KeyQ: 20, KeyR: 21, KeyS: 22, KeyT: 23, KeyU: 24, KeyV: 25, KeyW: 26, KeyX: 27,
  KeyY: 28, KeyZ: 29,
  Digit1: 30, Digit2: 31, Digit3: 32, Digit4: 33, Digit5: 34,
  Digit6: 35, Digit7: 36, Digit8: 37, Digit9: 38, Digit0: 39,
  Enter: 40, Escape: 41, Backspace: 42, Tab: 43, Space: 44,
  Minus: 45, Equal: 46, BracketLeft: 47, BracketRight: 48, Backslash: 49,
  Semicolon: 51, Quote: 52, Backquote: 53, Comma: 54, Period: 55, Slash: 56,
  ArrowRight: 79, ArrowLeft: 80, ArrowDown: 81, ArrowUp: 82,
};

function useKeyboardInput() {
  React.useEffect(() => {
    function handle(action: "Down" | "Up", e: KeyboardEvent): boolean {
      if (e.metaKey || e.ctrlKey) return false; // leave browser shortcuts alone
      const tag = (e.target as Element | null)?.tagName;
      if (tag === "INPUT" || tag === "TEXTAREA") return false;
      const code = HID[e.code];
      if (code == null) return false;
      sendInput({ kind: "key", action, code });
      return true;
    }
    const onDown = (e: KeyboardEvent) => { if (handle("Down", e)) e.preventDefault(); };
    const onUp = (e: KeyboardEvent) => { if (handle("Up", e)) e.preventDefault(); };
    window.addEventListener("keydown", onDown);
    window.addEventListener("keyup", onUp);
    return () => {
      window.removeEventListener("keydown", onDown);
      window.removeEventListener("keyup", onUp);
    };
  }, []);
}

function GestureLayer() {
  const draggingRef = React.useRef(false);

  function devicePoint(e: React.PointerEvent<HTMLDivElement>) {
    const rect = e.currentTarget.getBoundingClientRect();
    const x = Math.min(Math.max((e.clientX - rect.left) / rect.width, 0), 1);
    const y = Math.min(Math.max((e.clientY - rect.top) / rect.height, 0), 1);
    return { x, y };
  }

  return (
    <div
      className="absolute inset-0 cursor-crosshair touch-none select-none"
      onPointerDown={(e) => {
        draggingRef.current = true;
        e.currentTarget.setPointerCapture(e.pointerId);
        const p = devicePoint(e);
        sendInput({ kind: "touch", action: "Down", x: p.x, y: p.y });
      }}
      onPointerMove={(e) => {
        if (!draggingRef.current) return;
        const p = devicePoint(e);
        sendInput({ kind: "touch", action: "Move", x: p.x, y: p.y });
      }}
      onPointerUp={(e) => {
        if (!draggingRef.current) return;
        draggingRef.current = false;
        e.currentTarget.releasePointerCapture(e.pointerId);
        const p = devicePoint(e);
        sendInput({ kind: "touch", action: "Up", x: p.x, y: p.y });
      }}
      onPointerCancel={(e) => {
        if (!draggingRef.current) return;
        draggingRef.current = false;
        const p = devicePoint(e);
        sendInput({ kind: "touch", action: "Up", x: p.x, y: p.y });
      }}
    />
  );
}

function HardwareButton({ name, label, hideForPlatform, platform, children }: {
  name: string;
  label: string;
  hideForPlatform?: string;
  platform?: string;
  children: React.ReactNode;
}) {
  if (hideForPlatform && platform === hideForPlatform) return null;
  return (
    <button
      type="button"
      title={label}
      aria-label={label}
      onClick={() => {
        sendInput({ kind: "button", action: "Down", name });
        window.setTimeout(() => sendInput({ kind: "button", action: "Up", name }), 80);
      }}
      className="grid h-10 w-10 place-items-center rounded-md border border-white/40 bg-white/40 text-neutral-700 shadow-sm backdrop-blur transition hover:border-white/70 hover:bg-white/85 hover:text-neutral-900 active:scale-95 active:bg-white/95 active:shadow-inner"
    >
      {children}
    </button>
  );
}

function HardwareRail({ platform }: { platform?: string }) {
  if (platform !== "android" && platform !== "ios") return null;
  return (
    <div className="flex shrink-0 flex-col gap-1.5 self-start rounded-lg border border-white/40 bg-white/30 p-1.5 shadow-sm backdrop-blur-md">
      <HardwareButton name="power" label="Power">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" className="h-4 w-4">
          <path d="M12 3v9" /><path d="M7 7a7 7 0 1 0 10 0" />
        </svg>
      </HardwareButton>
      <HardwareButton name="volumeUp" label="Volume up">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" className="h-4 w-4">
          <path d="M12 5v14" /><path d="M5 12h14" />
        </svg>
      </HardwareButton>
      <HardwareButton name="volumeDown" label="Volume down">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" className="h-4 w-4">
          <path d="M5 12h14" />
        </svg>
      </HardwareButton>
      <div className="my-1 h-px bg-white/50" />
      <HardwareButton name="back" label="Back" hideForPlatform="ios" platform={platform}>
        <svg viewBox="0 0 24 24" fill="currentColor" className="h-4 w-4"><path d="M15 5 L7 12 L15 19 Z" /></svg>
      </HardwareButton>
      <HardwareButton name="home" label="Home">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" className="h-4 w-4">
          <path d="M3 10.5 12 3l9 7.5V20a1 1 0 0 1-1 1h-5v-7h-6v7H4a1 1 0 0 1-1-1z" />
        </svg>
      </HardwareButton>
      <HardwareButton name="appSwitch" label="Recents">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" className="h-4 w-4">
          <rect x="6" y="6" width="12" height="12" rx="1.2" />
        </svg>
      </HardwareButton>
    </div>
  );
}

function App() {
  const [overlays, setOverlays] = React.useState<DeviceOverlay[]>([]);
  const [commandRows, setCommandRows] = React.useState<CommandEntry[]>([]);
  const [deviceState, setDeviceState] = React.useState<DeviceState>({ status: "idle" });
  const [commandsCollapsed, setCommandsCollapsed] = React.useState(false);
  const didAutoStartDeviceStream = React.useRef(false);

  useInputFlushLoop();
  useKeyboardInput();

  React.useEffect(() => {
    const stream = new EventSource("/api/events/stream");

    stream.onerror = (event) => console.error("[mcp-visualizer] event stream error", event);
    stream.onmessage = (message) => {
      const event = JSON.parse(message.data) as VisualizerEvent;

      if (event.type === "maestro.flow_state") {
        setCommandRows((rows) => applyFlowState(rows, event.commands));
      }

      const overlay = overlayFromEvent(event);
      if (overlay) {
        setOverlays((current) => [overlay, ...current].slice(0, 8));
      }
    };

    return () => stream.close();
  }, []);

  React.useEffect(() => {
    if (overlays.length === 0) return;

    const timeout = window.setTimeout(() => {
      const now = Date.now();
      setOverlays((current) => current.filter((overlay) => overlay.expiresAt > now));
    }, 100);
    return () => window.clearTimeout(timeout);
  }, [overlays]);

  React.useEffect(() => {
    const stream = new EventSource("/api/device/state");

    stream.onmessage = (message) => {
      setDeviceState(JSON.parse(message.data));
    };

    return () => stream.close();
  }, []);

  React.useEffect(() => {
    if (didAutoStartDeviceStream.current) return;
    didAutoStartDeviceStream.current = true;

    async function startOnlyConnectedDevice() {
      const response = await fetch("/api/device/targets");
      const body = await response.json() as { devices?: DeviceTarget[] };
      const devices = body.devices || [];
      if (devices.length !== 1) return;

      const [device] = devices;
      await startDeviceStream(device);
    }

    startOnlyConnectedDevice().catch((error) => {
      setDeviceState({
        status: "error",
        message: error instanceof Error ? error.message : String(error),
      });
    });
  }, []);

  async function startDeviceStream(target: DeviceTarget) {
    const response = await fetch("/api/device/start", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        platform: target.platform,
        deviceType: target.deviceType,
        deviceId: target.deviceId,
      }),
    });
    setDeviceState(await response.json());
  }

  const isRunning = commandRows.some((r) => r.status === "started");
  // Hold the "running" tint for a moment after the last command stops so the bg
  // doesn't flicker between commands (which arrive ~100ms apart). Going to running
  // is instant; leaving running is debounced.
  const [showRunning, setShowRunning] = React.useState(false);
  React.useEffect(() => {
    if (isRunning) {
      setShowRunning(true);
      return;
    }
    const t = window.setTimeout(() => setShowRunning(false), 600);
    return () => window.clearTimeout(t);
  }, [isRunning]);

  return (
    <main className="flex h-screen items-stretch overflow-hidden bg-neutral-100 font-mono text-neutral-700">
      <CommandsPanel
        rows={commandRows}
        collapsed={commandsCollapsed}
        onToggle={() => setCommandsCollapsed((c) => !c)}
        onClear={() => setCommandRows([])}
      />
      <div
        className={
          "flex min-w-0 flex-1 items-start gap-4 p-4 transition-colors duration-500 " +
          (showRunning ? "bg-sky-100" : "bg-neutral-100")
        }
      >
        {deviceState.status === "streaming" ? (
          <>
            <div
              className={
                "relative shrink-0 overflow-hidden rounded-[2rem] bg-neutral-900 shadow-xl shadow-neutral-300/60 ring-4 transition-shadow duration-500 " +
                (showRunning ? "ring-sky-700" : "ring-transparent")
              }
            >
              <img className="block max-h-[calc(100vh-2rem)] w-auto" src={deviceState.streamUrl} draggable={false} />
              <div className="pointer-events-none absolute inset-0">
                <DeviceOverlayCanvas overlays={overlays} />
                {overlays
                  .filter((overlay): overlay is Extract<DeviceOverlay, { kind: "input_text" }> => overlay.kind === "input_text")
                  .map((overlay) => <InputTextOverlay key={overlay.id} overlay={overlay} />)}
              </div>
              <GestureLayer />
            </div>
            <HardwareRail platform={deviceState.platform} />
          </>
        ) : (
          <div className="grid h-[70vh] w-full max-w-sm shrink-0 place-items-center rounded-[2rem] border border-neutral-200 bg-white text-xs text-neutral-500 shadow-xl shadow-neutral-300/60">
            <div className="text-center">
              <div>no device stream</div>
              {deviceState.message && (
                <div className="mt-2 max-w-xs whitespace-pre-wrap text-neutral-400">{deviceState.message}</div>
              )}
            </div>
          </div>
        )}
      </div>
    </main>
  );
}

createRoot(document.getElementById("root")!).render(<App />);
