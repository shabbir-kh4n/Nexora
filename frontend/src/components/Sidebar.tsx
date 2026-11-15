import { useEffect, useState } from "react";
import { ThemeSelector } from "./ThemeSelector";
import type { ThemePreference } from "../lib/theme";
import { ChatIcon, LogoMark, PlusIcon, SidebarIcon } from "./icons";

interface SidebarProps {
  open: boolean;
  conversationTitle?: string;
  canStartNew: boolean;
  theme: ThemePreference;
  onThemeChange: (theme: ThemePreference) => void;
  onNewConversation: () => void;
  onToggle: () => void;
  onClose: () => void;
}

const LABEL_FADE_MS = 160;

// Labels stay mounted briefly after the sidebar starts closing so they can fade instead of vanishing.
function useLabelsPhase(open: boolean) {
  const [previousOpen, setPreviousOpen] = useState(open);
  const [leaving, setLeaving] = useState(false);
  // Adjusted during render, not in an effect, so the first closed frame already has the labels in "leaving".
  if (open !== previousOpen) {
    setPreviousOpen(open);
    setLeaving(!open);
  }
  useEffect(() => {
    if (!leaving) return;
    const timer = setTimeout(() => setLeaving(false), LABEL_FADE_MS);
    return () => clearTimeout(timer);
  }, [leaving]);
  return open ? "shown" : leaving ? "leaving" : "hidden";
}

// One markup for both states: when closed (an icon rail on desktop) the labels are dropped and
// tooltips stand in for them, so navigation behaviour is never duplicated.
export function Sidebar({ open, conversationTitle, canStartNew, theme, onThemeChange, onNewConversation, onToggle, onClose }: SidebarProps) {
  const labels = useLabelsPhase(open);
  const showLabels = labels !== "hidden";
  const tip = (label: string) => (showLabels ? undefined : label);

  return (
    <>
      <div className="sidebar-backdrop" data-open={open} onClick={onClose} aria-hidden="true" />
      <aside className="sidebar" id="sidebar" data-open={open} data-labels={labels} aria-label="Sidebar">
        <div className="sidebar__header">
          <span className="sidebar__brand">
            <LogoMark size={22} />
            {showLabels && <span className="wordmark">Nexora</span>}
          </span>
          <button
            type="button"
            className="icon-button"
            aria-label={open ? "Collapse sidebar" : "Expand sidebar"}
            aria-expanded={open}
            aria-controls="sidebar"
            data-tooltip={tip("Expand sidebar")}
            onClick={onToggle}
          >
            <SidebarIcon size={18} />
          </button>
        </div>

        <div className="sidebar__actions">
          <button
            type="button"
            className="nav-item"
            aria-label="New conversation"
            data-tooltip={tip("New conversation")}
            onClick={onNewConversation}
            disabled={!canStartNew}
          >
            <PlusIcon size={16} />
            {showLabels && <span>New conversation</span>}
          </button>
          {!showLabels && (
            <button
              type="button"
              className="nav-item"
              aria-label="Show conversations"
              data-tooltip="Conversations"
              onClick={onToggle}
            >
              <ChatIcon size={16} />
            </button>
          )}
        </div>

        {showLabels && (
          <nav className="sidebar__nav" aria-label="Conversations">
            <h2 className="sidebar__heading">Conversations</h2>
            {conversationTitle ? (
              <ul className="sidebar__list">
                <li>
                  <span className="sidebar__item" aria-current="page">
                    <ChatIcon size={15} />
                    <span className="sidebar__item-title">{conversationTitle}</span>
                  </span>
                </li>
              </ul>
            ) : (
              <p className="sidebar__empty">Nothing here yet. Conversations are not saved between visits.</p>
            )}
          </nav>
        )}

        <ThemeSelector preference={theme} onChange={onThemeChange} compact={!showLabels} />

        <div className="sidebar__profile" data-tooltip={tip("Local session · Not signed in")}>
          <span className="avatar" aria-hidden="true">L</span>
          {showLabels && (
            <div>
              <div className="sidebar__profile-name">Local session</div>
              <div className="sidebar__profile-note">Not signed in</div>
            </div>
          )}
        </div>
      </aside>
    </>
  );
}
