import { delay, http, HttpResponse } from "msw";
import { MockEvent, ReplCommand } from "../helpers/models";
import { sampleElements } from "../helpers/sampleElements";
import { wait } from "./api";
import { setupWorker } from "msw/browser";

class SseController {
  private data?: string;
  private controllers: ReadableStreamDefaultController[] = [];

  newResponse(): HttpResponse {
    const _this = this;
    const stream = new ReadableStream({
      async start(controller) {
        _this.controllers.push(controller);
        _this.notifyController(controller);
      },
    });
    return new HttpResponse(stream, {
      headers: {
        "Content-Type": "text/event-stream",
      },
    });
  }

  sendEvent(data: string) {
    this.data = data;
    this.notifyControllers();
  }

  private notifyControllers() {
    this.controllers.forEach((controller) => {
      this.notifyController(controller);
    });
  }

  private notifyController(controller: ReadableStreamDefaultController) {
    if (!this.data) return;
    controller.enqueue(textEncoder.encode(`data: ${this.data}\n\n`));
  }
}

let nextId = 0;
let commands: ReplCommand[] = [];

const replSseController = new SseController();
const deviceScreenSseController = new SseController();

deviceScreenSseController.sendEvent(
  JSON.stringify({
    screenshot: "/sample-screenshot.png",
    width: 1080,
    height: 2340,
    elements: sampleElements,
  })
);

const refreshRepl = () =>
  replSseController.sendEvent(JSON.stringify({ commands }));

refreshRepl();

const replResponse = (init?: ResponseInit) => {
  return new Response(
    JSON.stringify({
      commands,
    }),
    init
  );
};

const runCommands = async (ids: string[]) => {
  for (const id of ids) {
    const command = commands.find((c) => c.id === id);
    if (command) command.status = "pending";
  }
  refreshRepl();
  for (const id of ids) {
    await runCommand(id);
  }
};

const runCommand = async (id: string) => {
  const command = commands.find((c) => c.id === id);
  if (!command) return;
  await wait(200);
  command.status = "running";
  refreshRepl();
  await wait(500);
  command.status = "success";
  refreshRepl();
};

const createCommand = (yaml: string) => {
  const command: ReplCommand = {
    id: `${nextId++}`,
    yaml,
    status: "pending",
  };
  commands.push(command);
  runCommand(command.id);
  refreshRepl();
};

const textEncoder = new TextEncoder();

const handlers = [
  http.get("/api/repl/sse", async () => {
    return replSseController.newResponse();
  }),
  http.post<any, any>("/api/repl/command", async ({ request }) => {
    const { yaml, ids }: { yaml?: string; ids?: string[] } =
      await request.json();
    if (yaml) {
      if (yaml.includes("error")) {
        return new Response("Invalid command", { status: 400 });
      }
      createCommand(yaml);
    } else if (ids) {
      runCommands(ids);
    } else {
      return new Response(null, { status: 400 });
    }
    return replResponse();
  }),
  http.delete<any, any>("/api/repl/command", async ({ request }) => {
    const { ids }: { ids: string[] } = await request.json();
    commands = commands.filter((c) => !ids.includes(c.id));
    refreshRepl();
    return replResponse();
  }),
  http.post<any, any>("/api/repl/command/reorder", async ({ request }) => {
    const { ids }: { ids: string[] } = await request.json();
    const newCommands: ReplCommand[] = [];
    ids.forEach((id) => {
      const command = commands.find((c) => c.id === id);
      command && newCommands.push(command);
    });
    commands.forEach((command) => {
      if (!newCommands.includes(command)) {
        newCommands.push(command);
      }
    });
    await wait(30);
    commands = newCommands;
    refreshRepl();
    return replResponse();
  }),
  http.get("/api/device-screen/sse", async () => {
    return deviceScreenSseController.newResponse();
  }),
  http.post<any, any>("/api/repl/command/format", async ({ request }) => {
    const { ids }: { ids: string[] } = await request.json();
    const contentString = commands
      .filter((c) => ids.includes(c.id))
      .map((c) => (c.yaml.endsWith("\n") ? c.yaml : `${c.yaml}\n`))
      .join("");
    return new Response(
      JSON.stringify({
        config: "appId: com.example.app",
        commands: contentString,
      })
    );
  }),
  http.get("/api/mock-server/data", async () => {
    const projectId = "1803cbd0-7258-4878-a16c-1ef0022d2f4a";
    const sessions = [
      "9c4e5640-eaa8-4c81-94b4-efa96192dfd5",
      "7d191905-7a0e-429c-a0d2-ed5c5744ff83",
    ];

    const events = [];
    for (const i of [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]) {
      const sessionId = sessions[i % 2];
      const simulateRuntimeError = i % 4 === 0;
      const response = simulateRuntimeError
        ? {
            runtimeError: "Something went wrong when evaluating rule",
          }
        : {
            index: i,
            title: `Post title ${i}`,
            body: "Lorem ipsum dolor sit amet",
          };

      const event: MockEvent = {
        timestamp: new Date().toISOString(),
        path:
          i === 2
            ? `/posts/${i}/alksudhjioash78902qbnpiasy091g089qg978§1gs0789gq078gw180hgb1s8008sg1078g1s08g1s08s1jkshipasha0ish0aisha908sh80ash0asha0shas90-ha`
            : `/posts/${i}`,
        matched: i % 3 !== 0,
        response,
        method: (i + 1) % 3 === 0 ? "POST" : "GET",
        statusCode: simulateRuntimeError ? 500 : i % 7 === 0 ? 401 : 200,
        sessionId: sessionId,
        projectId: projectId,
        headers: {
          "Content-Type": "application/json",
          Authorization: "Bearer REDACTED",
        },
      };

      if ((i + 1) % 3 === 0) {
        event.bodyAsString = JSON.stringify({ foo: "bar", body: true });
      }

      events.push(event);
    }

    await delay(500);

    return new Response(
      JSON.stringify({
        projectId,
        events,
      })
    );
  }),
  http.get("/api/banner-message", () => {
    return new Response(
      JSON.stringify({
        level: "warning",
        message:
          "Retrieving the hierarchy is taking longer than usual. This might be due to a deep hierarchy in the current view. Please wait a bit more to complete the operation.",
      })
    );
  }),
  http.get("/api/auth-token", () => {
    return new Response("faketoken123456");
  }),
  http.get("https://api.mobile.dev/mai/generate-command", () => {
    return new Response(
      JSON.stringify({
        command: '- tapOn: "Search"',
      })
    );
  }),
];

export const installMocks = () => {
  setupWorker(...handlers).start();
};
