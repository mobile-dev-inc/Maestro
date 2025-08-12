import { delay, http, HttpResponse } from "msw";
import { sampleElements } from "../helpers/sampleElements";
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

const deviceScreenSseController = new SseController();

deviceScreenSseController.sendEvent(
  JSON.stringify({
    screenshot: "/sample-screenshot.png",
    width: 1080,
    height: 2340,
    elements: sampleElements,
  })
);

const textEncoder = new TextEncoder();

const handlers = [
  http.post<any, any>("/api/run-command", async ({ request }) => {
    const { yaml, dryRun }: { yaml: string, dryRun?: boolean } = await request.json();
    if (yaml.includes("invalid")) return new Response("invalid command", { status: 400 })
    if (!dryRun) await delay(1000)
    return new Response(null, { status: 200 })
  }),
  http.get("/api/device-screen/sse", async () => {
    return deviceScreenSseController.newResponse();
  }),
  http.post<any, any>("/api/format-flow", async ({ request }) => {
    const { commands }: { commands: string[] } = await request.json();
    const contentString = commands.join("\n");
    return new Response(
      JSON.stringify({
        config: "appId: com.example.app",
        commands: contentString,
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
  http.get("/api/auth", () => {
    return new Response(
      JSON.stringify({
        authToken: "faketoken123456",
      })
    );
  }),
  http.get("https://api.copilot.mobile.dev/v2/maestro-studio/generate-command", () => {
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
