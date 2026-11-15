export type ThemePreference = "light" | "dark" | "system";
export type ResolvedTheme = "light" | "dark";

export const THEME_OPTIONS: readonly ThemePreference[] = ["light", "dark", "system"];
export const DARK_QUERY = "(prefers-color-scheme: dark)";

// The inline script in index.html repeats this key and the resolve rule so the theme is set before first paint.
const STORAGE_KEY = "nexora-theme";

export function readPreference(): ThemePreference {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    return stored === "light" || stored === "dark" ? stored : "system";
  } catch {
    return "system";
  }
}

export function savePreference(preference: ThemePreference): void {
  try {
    localStorage.setItem(STORAGE_KEY, preference);
  } catch {
    // Storage can be blocked; the choice then lasts for this visit only.
  }
}

export function resolveTheme(preference: ThemePreference): ResolvedTheme {
  if (preference === "system") return window.matchMedia(DARK_QUERY).matches ? "dark" : "light";
  return preference;
}

let transitionTimer: number | undefined;
const TRANSITION_MS = 300;

// The class enables colour transitions on every element for the duration of a switch only, so ordinary
// hover and layout transitions are never slowed or overridden.
export function applyTheme(theme: ResolvedTheme): void {
  const root = document.documentElement;
  if (root.dataset.theme === theme) return;
  root.classList.add("theme-transition");
  root.dataset.theme = theme;
  window.clearTimeout(transitionTimer);
  transitionTimer = window.setTimeout(() => root.classList.remove("theme-transition"), TRANSITION_MS);
}
