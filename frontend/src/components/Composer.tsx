import { useEffect, useRef, useState, type FormEvent, type KeyboardEvent } from "react";
import { MAX_QUESTION_LENGTH } from "../api/contract";
import { ArrowUpIcon, StopIcon } from "./icons";

interface ComposerProps {
  busy: boolean;
  onSubmit: (question: string) => void;
  onStop: () => void;
}

const MAX_HEIGHT_PX = 200;
const COUNTER_THRESHOLD = MAX_QUESTION_LENGTH * 0.8;

export function Composer({ busy, onSubmit, onStop }: ComposerProps) {
  const [text, setText] = useState("");
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const question = text.trim();
  const canSend = question.length > 0 && !busy;

  useEffect(() => {
    const element = textareaRef.current;
    if (!element) return;
    element.style.height = "auto";
    element.style.height = `${Math.min(element.scrollHeight, MAX_HEIGHT_PX)}px`;
  }, [text]);

  // The field stays editable during a run, so refocus once the run ends and the user can submit again.
  useEffect(() => {
    if (!busy) textareaRef.current?.focus();
  }, [busy]);

  function submit(event?: FormEvent) {
    event?.preventDefault();
    if (!canSend) return;
    onSubmit(question);
    setText("");
  }

  function handleKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (event.key === "Enter" && !event.shiftKey && !event.nativeEvent.isComposing) {
      event.preventDefault();
      submit();
    }
  }

  return (
    <form className="composer" onSubmit={submit}>
      <div className="composer__box">
        <label htmlFor="question" className="sr-only">Your question</label>
        <textarea
          id="question"
          ref={textareaRef}
          rows={1}
          value={text}
          maxLength={MAX_QUESTION_LENGTH}
          placeholder="Ask anything"
          onChange={(event) => setText(event.target.value)}
          onKeyDown={handleKeyDown}
        />
        {busy ? (
          <button type="button" className="composer__action composer__action--stop" onClick={onStop} aria-label="Stop generating">
            <StopIcon size={16} />
          </button>
        ) : (
          <button type="submit" className="composer__action" disabled={!canSend} aria-label="Send question">
            <ArrowUpIcon size={18} />
          </button>
        )}
      </div>
      <div className="composer__meta">
        <span>Enter to send · Shift+Enter for a new line</span>
        {text.length >= COUNTER_THRESHOLD && (
          <span className={text.length >= MAX_QUESTION_LENGTH ? "composer__count composer__count--limit" : "composer__count"}>
            {text.length} / {MAX_QUESTION_LENGTH}
          </span>
        )}
      </div>
    </form>
  );
}
