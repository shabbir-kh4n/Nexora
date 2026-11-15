import { useEffect, useRef, useState } from "react";
import { Composer } from "./components/Composer";
import { MessageList } from "./components/MessageList";
import { Sidebar } from "./components/Sidebar";
import { Welcome } from "./components/Welcome";
import { LogoMark, SidebarIcon } from "./components/icons";
import { isActive, useConversation } from "./hooks/useConversation";
import { useMediaQuery } from "./hooks/useMediaQuery";
import { useTheme } from "./hooks/useTheme";

const DESKTOP_QUERY = "(min-width: 900px)";

export function App() {
  const { turns, send, retry, stop, reset } = useConversation();
  const { preference, setPreference } = useTheme();
  const isDesktop = useMediaQuery(DESKTOP_QUERY);
  const [sidebarOpen, setSidebarOpen] = useState(isDesktop);
  const [wasDesktop, setWasDesktop] = useState(isDesktop);
  // Crossing the breakpoint (resize, rotation) resets the sidebar: expanded on desktop, a closed drawer below it.
  if (isDesktop !== wasDesktop) {
    setWasDesktop(isDesktop);
    setSidebarOpen(isDesktop);
  }
  const openerRef = useRef<HTMLButtonElement>(null);
  const drawerWasOpen = useRef(false);
  // Below the desktop breakpoint the sidebar is a modal drawer: it takes focus, and the page behind is inert.
  const drawerOpen = !isDesktop && sidebarOpen;

  const busy = isActive(turns[turns.length - 1]);
  const firstQuestion = turns[0]?.question;

  useEffect(() => {
    function closeOnEscape(event: KeyboardEvent) {
      if (event.key === "Escape" && !isDesktop) setSidebarOpen(false);
    }
    window.addEventListener("keydown", closeOnEscape);
    return () => window.removeEventListener("keydown", closeOnEscape);
  }, [isDesktop]);

  useEffect(() => {
    if (drawerOpen) document.querySelector<HTMLElement>("#sidebar .icon-button")?.focus();
    else if (drawerWasOpen.current) openerRef.current?.focus();
    drawerWasOpen.current = drawerOpen;
  }, [drawerOpen]);

  function startNew() {
    reset();
    if (!isDesktop) setSidebarOpen(false);
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
      <main className="workspace" inert={drawerOpen}>
        {/* Only the mobile drawer needs an opener here; on desktop the sidebar's own header and rail carry the toggle. */}
        <header className="topbar">
          <button ref={openerRef} type="button" className="icon-button" aria-label="Open sidebar" aria-controls="sidebar" onClick={() => setSidebarOpen(true)}>
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
