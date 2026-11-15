import { useEffect, useState } from "react";
import { Composer } from "./components/Composer";
import { MessageList } from "./components/MessageList";
import { Sidebar } from "./components/Sidebar";
import { Welcome } from "./components/Welcome";
import { LogoMark, SidebarIcon } from "./components/icons";
import { isActive, useConversation } from "./hooks/useConversation";
import { useTheme } from "./hooks/useTheme";

const DESKTOP_QUERY = "(min-width: 900px)";

export function App() {
  const { turns, send, retry, stop, reset } = useConversation();
  const { preference, setPreference } = useTheme();
  const [sidebarOpen, setSidebarOpen] = useState(() => window.matchMedia(DESKTOP_QUERY).matches);

  const busy = isActive(turns[turns.length - 1]);
  const firstQuestion = turns[0]?.question;

  useEffect(() => {
    function closeOnEscape(event: KeyboardEvent) {
      if (event.key === "Escape" && !window.matchMedia(DESKTOP_QUERY).matches) setSidebarOpen(false);
    }
    window.addEventListener("keydown", closeOnEscape);
    return () => window.removeEventListener("keydown", closeOnEscape);
  }, []);

  function startNew() {
    reset();
    if (!window.matchMedia(DESKTOP_QUERY).matches) setSidebarOpen(false);
  }

  return (
    <div className="app" data-sidebar={sidebarOpen ? "open" : "closed"}>
      <Sidebar
        open={sidebarOpen}
        conversationTitle={firstQuestion}
        canStartNew={turns.length > 0 && !busy}
        theme={preference}
        onThemeChange={setPreference}
        onNewConversation={startNew}
        onToggle={() => setSidebarOpen((open) => !open)}
        onClose={() => setSidebarOpen(false)}
      />
      <main className="workspace">
        {/* Only the mobile drawer needs an opener here; on desktop the sidebar's own header and rail carry the toggle. */}
        <header className="topbar">
          <button type="button" className="icon-button" aria-label="Open sidebar" aria-controls="sidebar" onClick={() => setSidebarOpen(true)}>
            <SidebarIcon size={18} />
          </button>
          <span className="topbar__brand">
            <LogoMark size={20} />
            <span className="wordmark">Nexora</span>
          </span>
        </header>
        {turns.length === 0 ? <Welcome disabled={busy} onPick={send} /> : <MessageList turns={turns} onRetry={retry} />}
        <Composer busy={busy} onSubmit={send} onStop={stop} />
      </main>
    </div>
  );
}
