import { useCallback, useEffect, useReducer, useRef } from "react";
import { cancelRun, getRun, streamRun, ApiRequestError } from "../api/runsClient";
import type { ApiError, RunEvent, RunSnapshot } from "../api/contract";

export type TurnPhase = "streaming" | "recovering" | "completed" | "failed" | "cancelled";

export interface Turn {
  id: string;
  question: string;
  phase: TurnPhase;
  runId?: string;
  steps: string[];
  output: string;
  error?: ApiError;
}

type Action =
  | { type: "start"; id: string; question: string }
  | { type: "event"; id: string; event: RunEvent }
  | { type: "snapshot"; id: string; snapshot: RunSnapshot }
  | { type: "recovering"; id: string }
  | { type: "fail"; id: string; error: ApiError }
  | { type: "cancelledLocally"; id: string }
  | { type: "reset" };

const RECOVERY_INTERVAL_MS = 2000;
// Longer than the 3-minute run deadline, so a live run is always seen reaching a terminal state.
const RECOVERY_MAX_ATTEMPTS = 100;

const CONNECTION_LOST: ApiError = {
  code: "CONNECTION_LOST",
  message: "The connection to Nexora was lost before the answer finished.",
};

function isTerminal(phase: TurnPhase): boolean {
  return phase === "completed" || phase === "failed" || phase === "cancelled";
}

export function isActive(turn: Turn | undefined): boolean {
  return turn !== undefined && !isTerminal(turn.phase);
}

function applyEvent(turn: Turn, event: RunEvent): Turn {
  const next = { ...turn, runId: event.run_id };
  switch (event.type) {
    case "step":
      return { ...next, steps: [...turn.steps, event.data.message] };
    case "token":
      return { ...next, output: turn.output + event.data.text };
    case "done":
      return { ...next, phase: "completed" };
    case "error":
      return { ...next, phase: "failed", error: event.data };
    case "heartbeat":
      return turn;
  }
}

function applySnapshot(turn: Turn, snapshot: RunSnapshot): Turn {
  const base = {
    ...turn,
    runId: snapshot.run_id,
    output: snapshot.output,
    steps: snapshot.steps.map((step) => step.message),
  };
  switch (snapshot.status) {
    case "COMPLETED":
      return { ...base, phase: "completed" };
    case "CANCELLED":
      return { ...base, phase: "cancelled" };
    case "FAILED":
      return { ...base, phase: "failed", error: snapshot.error ?? CONNECTION_LOST };
    default:
      return { ...base, phase: "recovering" };
  }
}

function reducer(turns: Turn[], action: Action): Turn[] {
  if (action.type === "reset") return [];
  if (action.type === "start") {
    const fresh: Turn = { id: action.id, question: action.question, phase: "streaming", steps: [], output: "" };
    return turns.some((turn) => turn.id === action.id)
      ? turns.map((turn) => (turn.id === action.id ? fresh : turn))
      : [...turns, fresh];
  }
  return turns.map((turn) => {
    // Terminal states are immutable, mirroring the backend.
    if (turn.id !== action.id || isTerminal(turn.phase)) return turn;
    switch (action.type) {
      case "event":
        return applyEvent(turn, action.event);
      case "snapshot":
        return applySnapshot(turn, action.snapshot);
      case "recovering":
        return { ...turn, phase: "recovering" };
      case "fail":
        return { ...turn, phase: "failed", error: action.error };
      case "cancelledLocally":
        return { ...turn, phase: "cancelled" };
    }
  });
}

function toApiError(error: unknown): ApiError {
  return error instanceof ApiRequestError ? { code: error.code, message: error.message } : CONNECTION_LOST;
}

function pause(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve) => {
    const timer = setTimeout(resolve, ms);
    signal.addEventListener("abort", () => { clearTimeout(timer); resolve(); }, { once: true });
  });
}

interface ActiveRun {
  controller: AbortController;
  turnId: string;
  runId?: string;
}

export function useConversation() {
  const [turns, dispatch] = useReducer(reducer, []);
  const activeRef = useRef<ActiveRun | null>(null);
  const counterRef = useRef(0);

  // The stream may end or break before a terminal event; the snapshot is the source of truth then.
  const recover = useCallback(async (active: ActiveRun) => {
    const { signal } = active.controller;
    const { turnId, runId } = active;
    if (!runId) {
      dispatch({ type: "fail", id: turnId, error: CONNECTION_LOST });
      return;
    }
    dispatch({ type: "recovering", id: turnId });
    for (let attempt = 0; attempt < RECOVERY_MAX_ATTEMPTS && !signal.aborted; attempt++) {
      try {
        const snapshot = await getRun(runId, signal);
        dispatch({ type: "snapshot", id: turnId, snapshot });
        if (snapshot.status !== "QUEUED" && snapshot.status !== "RUNNING") return;
      } catch (error) {
        if (signal.aborted) return;
        if (error instanceof ApiRequestError && error.code === "RUN_NOT_FOUND") {
          dispatch({ type: "fail", id: turnId, error: toApiError(error) });
          return;
        }
      }
      await pause(RECOVERY_INTERVAL_MS, signal);
    }
    if (!signal.aborted) dispatch({ type: "fail", id: turnId, error: CONNECTION_LOST });
  }, []);

  const execute = useCallback(
    async (turnId: string, question: string) => {
      const active: ActiveRun = { controller: new AbortController(), turnId };
      activeRef.current = active;
      dispatch({ type: "start", id: turnId, question });
      try {
        let terminal = false;
        await streamRun(question, active.controller.signal, (event) => {
          active.runId = event.run_id;
          terminal = event.type === "done" || event.type === "error";
          dispatch({ type: "event", id: turnId, event });
        });
        if (!terminal && !active.controller.signal.aborted) await recover(active);
      } catch (error) {
        if (active.controller.signal.aborted) return;
        if (error instanceof ApiRequestError || !active.runId) {
          dispatch({ type: "fail", id: turnId, error: toApiError(error) });
        } else {
          await recover(active);
        }
      } finally {
        if (activeRef.current === active) activeRef.current = null;
      }
    },
    [recover],
  );

  const send = useCallback(
    (question: string) => {
      if (activeRef.current) return;
      counterRef.current += 1;
      void execute(`turn-${counterRef.current}`, question);
    },
    [execute],
  );

  const retry = useCallback(
    (turnId: string) => {
      const turn = turns.find((candidate) => candidate.id === turnId);
      if (!turn || activeRef.current) return;
      void execute(turn.id, turn.question);
    },
    [execute, turns],
  );

  const stop = useCallback(async () => {
    const active = activeRef.current;
    if (!active) return;
    active.controller.abort();
    activeRef.current = null;
    if (!active.runId) {
      // No run ID has arrived yet, so the backend cannot be asked to cancel.
      dispatch({ type: "cancelledLocally", id: active.turnId });
      return;
    }
    try {
      dispatch({ type: "snapshot", id: active.turnId, snapshot: await cancelRun(active.runId) });
    } catch (error) {
      dispatch({
        type: "fail",
        id: active.turnId,
        error: {
          code: error instanceof ApiRequestError ? error.code : "CANCEL_FAILED",
          message: "Nexora could not confirm that the run was stopped.",
        },
      });
    }
  }, []);

  const reset = useCallback(() => {
    if (!activeRef.current) dispatch({ type: "reset" });
  }, []);

  useEffect(
    () => () => {
      activeRef.current?.controller.abort();
    },
    [],
  );

  return { turns, send, retry, stop, reset };
}
