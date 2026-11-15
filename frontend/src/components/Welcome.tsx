import { LogoMark } from "./icons";

const SUGGESTIONS = [
  { title: "Explain a concept", prompt: "Explain how retrieval-augmented generation works and where it breaks down." },
  { title: "Compare options", prompt: "Compare PostgreSQL and MySQL for a new multi-tenant SaaS product." },
  { title: "Track a topic", prompt: "Summarize recent developments in solid-state batteries." },
  { title: "Understand a change", prompt: "What changed for developers in the most recent Java release?" },
];

interface WelcomeProps {
  disabled: boolean;
  onPick: (prompt: string) => void;
}

export function Welcome({ disabled, onPick }: WelcomeProps) {
  return (
    <section className="welcome" aria-labelledby="welcome-title">
      <LogoMark size={40} />
      <h1 id="welcome-title">What would you like to research?</h1>
      <p className="welcome__intro">
        Ask a question and Nexora works through it step by step, showing its progress while the answer is written.
      </p>
      <ul className="suggestions">
        {SUGGESTIONS.map((suggestion) => (
          <li key={suggestion.title}>
            <button type="button" className="suggestion" disabled={disabled} onClick={() => onPick(suggestion.prompt)}>
              <span className="suggestion__title">{suggestion.title}</span>
              <span className="suggestion__prompt">{suggestion.prompt}</span>
            </button>
          </li>
        ))}
      </ul>
    </section>
  );
}
