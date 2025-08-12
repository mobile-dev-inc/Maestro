import _ from "lodash";
import {
  AiResponseType,
  AuthType,
  BannerMessage,
  DeviceScreen,
  FormattedFlow,
  ViewHierarchyType,
} from "../helpers/models";
import useSWR, { SWRConfiguration, SWRResponse } from "swr";
import useSWRSubscription, { SWRSubscriptionResponse } from "swr/subscription";

export class HttpError extends Error {
  constructor(public status: number, public message: string) {
    super(message);
  }
}

export const wait = async (durationMs: number) => {
  return new Promise((resolve) => setTimeout(resolve, durationMs));
};
const makeRequest = async <T>(
  method: string,
  path: string,
  body?: Object | undefined,
  type?: "json" | "text"
): Promise<T> => {
  const options: RequestInit = {
    method,
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json",
    },
  };
  // Ensure body isn't set for GET or HEAD requests
  if (body && method !== "GET" && method !== "HEAD") {
    options.body = JSON.stringify(body);
  }
  const response = await fetch(path, options);
  if (!response.ok) {
    const responseBody = await response.text();
    throw new HttpError(response.status, responseBody);
  }
  const contentLength = response.headers.get("Content-Length");
  if (contentLength === "0") {
    return null as any as T;
  }
  if (type === "text") {
    return (await response.text()) as any as T;
  }
  try {
    return (await response.json()) as T;
  } catch (error: any) {
    throw new Error("Failed to parse JSON: " + _.get(error, "message"));
  }
};

const useSse = <T>(url: string): SWRSubscriptionResponse<T> => {
  return useSWRSubscription<T, any, string>(url, (key, { next }) => {
    const eventSource = new EventSource(key);
    eventSource.onmessage = (e) => {
      const repl: T = JSON.parse(e.data);
      next(null, repl);
    };
    eventSource.onerror = (error) => {
      next(error);
    };
    return () => eventSource.close();
  });
};

const useDeviceScreen = (): { deviceScreen?: DeviceScreen; error?: any } => {
  const { data: deviceScreen, error } = useSse<DeviceScreen>(
    "/api/device-screen/sse"
  );
  return { deviceScreen, error };
};

export const API = {
  useAuth: (config?: SWRConfiguration<AuthType>): SWRResponse<AuthType> => {
    return useSWR(
      "/api/auth-token",
      () => makeRequest("GET", "/api/auth"),
      config
    );
  },
  useDeviceScreen,
  useBannerMessage: (
    config?: SWRConfiguration<BannerMessage>
  ): SWRResponse<BannerMessage> => {
    return useSWR(
      "/api/banner-message",
      (url) => makeRequest("GET", url),
      config
    );
  },
  runCommand: async (yaml: string, dryRun?: boolean): Promise<void> => {
    await makeRequest("POST", "/api/run-command", { yaml, dryRun });
  },
  formatFlow: async (commands: string[]): Promise<FormattedFlow> => {
    return makeRequest("POST", "/api/format-flow", { commands });
  },
  lastViewHierarchy: async (): Promise<ViewHierarchyType> => {
    return makeRequest("GET", "/api/last-view-hierarchy");
  },
  saveOpenAiToken: async (token: string) => {
    return makeRequest("POST", "/api/auth/openai-token", { token: token });
  },
  deleteOpenAiToken: async () => {
    return makeRequest("DELETE", "/api/auth/openai-token");
  },
  generateCommandWithAI: async ({
    screen,
    userInput,
    token,
    openAiToken,
    signal,
  }: {
    screen: any;
    userInput: string;
    token: string | null | undefined;
    openAiToken: string | null | undefined;
    signal?: AbortSignal;
  }): Promise<AiResponseType> => {
    const response = await fetch(
      "https://api.copilot.mobile.dev/v2/maestro-studio/generate-command",
      {
        method: "POST",
        headers: {
          Accept: "application/json",
          "Content-Type": "application/json",
          Authorization: `Bearer ${token}`,
        },
        body: JSON.stringify({ screen, userInput, openAiToken }),
        signal,
      }
    );
    if (!response.ok) {
      const body = await response.text();
      throw new HttpError(response.status, body);
    }
    return await response.json();
  },
};
