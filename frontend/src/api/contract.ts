// Mirrors docs/openapi/public.yaml. Responses are validated at runtime because the stream crosses a trust boundary.

export const MAX_QUESTION_LENGTH = 2000;

const RUN_ID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

export type RunStatus = "QUEUED" | "RUNNING" | "COMPLETED" | "FAILED" | "CANCELLED";

const RUN_STATUSES: readonly string[] = ["QUEUED", "RUNNING", "COMPLETED", "FAILED", "CANCELLED"];

export interface ApiError {
  code: string;
  message: string;
}

export interface RunSnapshot {
  run_id: string;
  status: RunStatus;
  output: string;
  steps: { message: string }[];
  created_at: string;
  updated_at: string;
  error?: ApiError;
}

interface EventBase {
  event_id: string;
  run_id: string;
  timestamp: string;
}

export type RunEvent =
  | (EventBase & { type: "step"; data: { message: string } })
  | (EventBase & { type: "token"; data: { text: string } })
  | (EventBase & { type: "done"; data: Record<string, never> })
  | (EventBase & { type: "heartbeat"; data: Record<string, never> })
  | (EventBase & { type: "error"; data: ApiError });

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

export function isRunId(value: unknown): value is string {
  return typeof value === "string" && RUN_ID_PATTERN.test(value);
}

export function parseApiError(value: unknown): ApiError | null {
  if (!isRecord(value)) return null;
  const { code, message } = value;
  return typeof code === "string" && typeof message === "string" ? { code, message } : null;
}

export function parseErrorResponse(value: unknown): ApiError | null {
  return isRecord(value) ? parseApiError(value.error) : null;
}

export function parseRunEvent(raw: string): RunEvent | null {
  let value: unknown;
  try {
    value = JSON.parse(raw);
  } catch {
    return null;
  }
  if (!isRecord(value)) return null;
  const { version, event_id, run_id, timestamp, type, data } = value;
  if (version !== "1" || typeof event_id !== "string" || typeof timestamp !== "string") return null;
  if (!isRunId(run_id) || !isRecord(data)) return null;
  const base = { event_id, run_id, timestamp };

  switch (type) {
    case "step":
      return typeof data.message === "string" && data.message
        ? { ...base, type, data: { message: data.message } }
        : null;
    case "token":
      return typeof data.text === "string" && data.text ? { ...base, type, data: { text: data.text } } : null;
    case "done":
    case "heartbeat":
      return { ...base, type, data: {} };
    case "error": {
      const error = parseApiError(data);
      return error ? { ...base, type, data: error } : null;
    }
    default:
      return null;
  }
}

export function parseRunSnapshot(value: unknown): RunSnapshot | null {
  if (!isRecord(value)) return null;
  const { run_id, status, output, steps, created_at, updated_at, error } = value;
  if (!isRunId(run_id) || typeof status !== "string" || !RUN_STATUSES.includes(status)) return null;
  if (typeof output !== "string" || !Array.isArray(steps)) return null;
  if (typeof created_at !== "string" || typeof updated_at !== "string") return null;

  const messages: { message: string }[] = [];
  for (const step of steps as unknown[]) {
    if (!isRecord(step) || typeof step.message !== "string") return null;
    messages.push({ message: step.message });
  }
  const parsedError = error === undefined ? undefined : (parseApiError(error) ?? undefined);
  return {
    run_id,
    status: status as RunStatus,
    output,
    steps: messages,
    created_at,
    updated_at,
    ...(parsedError ? { error: parsedError } : {}),
  };
}
