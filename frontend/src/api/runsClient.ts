import { createParser } from "eventsource-parser";
import {
  parseErrorResponse,
  parseRunEvent,
  parseRunSnapshot,
  type RunEvent,
  type RunSnapshot,
} from "./contract";

const RUNS_URL = "/api/v1/runs";

export class ApiRequestError extends Error {
  readonly code: string;

  constructor(code: string, message: string) {
    super(message);
    this.name = "ApiRequestError";
    this.code = code;
  }
}

async function toRequestError(response: Response): Promise<ApiRequestError> {
  try {
    const error = parseErrorResponse(await response.json());
    if (error) return new ApiRequestError(error.code, error.message);
  } catch {
    // Fall through: the body was not the documented error envelope.
  }
  return new ApiRequestError("REQUEST_FAILED", `The server responded with status ${response.status}.`);
}

async function readSnapshot(response: Response): Promise<RunSnapshot> {
  if (!response.ok) throw await toRequestError(response);
  const snapshot = parseRunSnapshot(await response.json());
  if (!snapshot) throw new ApiRequestError("RESPONSE_INVALID", "The server returned an unexpected response.");
  return snapshot;
}

// Resolves when the stream ends. Throws ApiRequestError for a rejected request and a plain error for a broken connection.
export async function streamRun(
  question: string,
  signal: AbortSignal,
  onEvent: (event: RunEvent) => void,
): Promise<void> {
  const response = await fetch(RUNS_URL, {
    method: "POST",
    headers: { "Content-Type": "application/json", Accept: "text/event-stream" },
    body: JSON.stringify({ question }),
    signal,
  });
  if (!response.ok) throw await toRequestError(response);
  if (!response.body) throw new ApiRequestError("STREAM_UNAVAILABLE", "The server did not return a stream.");

  const seen = new Set<string>();
  let finished = false;
  const parser = createParser({
    onEvent(message) {
      if (finished) return;
      const event = parseRunEvent(message.data);
      if (!event) {
        console.warn("Ignored a malformed run event");
        return;
      }
      if (seen.has(event.event_id)) return;
      seen.add(event.event_id);
      // Nothing follows a terminal event, so anything after it is ignored.
      finished = event.type === "done" || event.type === "error";
      onEvent(event);
    },
  });

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  try {
    while (!finished) {
      const { done, value } = await reader.read();
      if (done) break;
      parser.feed(decoder.decode(value, { stream: true }));
    }
  } finally {
    reader.cancel().catch(() => undefined);
  }
}

export async function getRun(runId: string, signal: AbortSignal): Promise<RunSnapshot> {
  return readSnapshot(await fetch(`${RUNS_URL}/${runId}`, { signal }));
}

export async function cancelRun(runId: string, signal?: AbortSignal): Promise<RunSnapshot> {
  return readSnapshot(await fetch(`${RUNS_URL}/${runId}/cancel`, { method: "POST", signal }));
}
