import { memo } from "react";
import { renderMarkdown } from "../lib/markdown";
import type { Turn } from "../hooks/useConversation";
import { AlertIcon, CheckIcon, RetryIcon } from "./icons";

const ERROR_TITLES: Record<string, string> = {
  RUN_CAPACITY_EXCEEDED: "Nexora is busy right now",
  RUN_TIMEOUT: "The run took too long",
  UPSTREAM_UNAVAILABLE: "The research service is unavailable",
  CONNECTION_LOST: "Connection lost",
};

interface TurnViewProps {
  turn: Turn;
  canRetry: boolean;
  onRetry: (turnId: string) => void;
}

function ResearchSteps({ steps, running }: { steps: string[]; running: boolean }) {
  if (steps.length === 0) return null;
  return (
    <details className="steps" open>
      <summary>Research steps</summary>
      <ol>
        {steps.map((step, index) => {
          const inProgress = running && index === steps.length - 1;
          return (
            <li key={index} data-state={inProgress ? "active" : "done"}>
              <span className="steps__marker" aria-hidden="true">
                {inProgress ? <span className="spinner" /> : <CheckIcon size={12} />}
              </span>
              {step}
            </li>
          );
        })}
      </ol>
    </details>
  );
}

function TurnViewBase({ turn, canRetry, onRetry }: TurnViewProps) {
  const running = turn.phase === "streaming" || turn.phase === "recovering";
  const waiting = running && turn.steps.length === 0 && !turn.output;

  return (
    <article className="turn" aria-busy={running}>
      <h2 className="turn__question">{turn.question}</h2>

      <ResearchSteps steps={turn.steps} running={running} />

      {waiting && (
        <p className="status" role="status">
          <span className="spinner" aria-hidden="true" /> Starting the run…
        </p>
      )}

      {turn.output && (
        <div className="prose" data-streaming={turn.phase === "streaming"}>
          {renderMarkdown(turn.output)}
        </div>
      )}

      {turn.phase === "recovering" && (
        <p className="status" role="status">
          <span className="spinner" aria-hidden="true" /> Connection interrupted. Checking the run's progress…
        </p>
      )}

      {turn.phase === "cancelled" && <p className="status status--muted" role="status">Generation stopped.</p>}

      {turn.phase === "failed" && turn.error && (
        <div className="error-banner" role="alert">
          <AlertIcon size={18} />
          <div className="error-banner__text">
            <strong>{ERROR_TITLES[turn.error.code] ?? "The run failed"}</strong>
            <span>{turn.error.message}</span>
            {turn.output && <span className="error-banner__note">The partial answer above is incomplete.</span>}
          </div>
          {canRetry && (
            <button type="button" className="button button--secondary" onClick={() => onRetry(turn.id)}>
              <RetryIcon size={14} />
              Retry
            </button>
          )}
        </div>
      )}
    </article>
  );
}

export const TurnView = memo(TurnViewBase);
