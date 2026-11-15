import { THEME_OPTIONS, type ThemePreference } from "../lib/theme";
import { MonitorIcon, MoonIcon, SunIcon } from "./icons";

const LABELS: Record<ThemePreference, string> = { light: "Light", dark: "Dark", system: "System" };
const ICONS = { light: SunIcon, dark: MoonIcon, system: MonitorIcon };

interface ThemeSelectorProps {
  preference: ThemePreference;
  onChange: (preference: ThemePreference) => void;
  // The collapsed rail has room for one icon, so it cycles through the same options.
  compact: boolean;
}

export function ThemeSelector({ preference, onChange, compact }: ThemeSelectorProps) {
  if (compact) {
    const next = THEME_OPTIONS[(THEME_OPTIONS.indexOf(preference) + 1) % THEME_OPTIONS.length] ?? "system";
    const Icon = ICONS[preference];
    return (
      <button
        type="button"
        className="nav-item nav-item--theme"
        aria-label={`Theme: ${LABELS[preference]}. Switch to ${LABELS[next]}`}
        data-tooltip={`Theme: ${LABELS[preference]}`}
        onClick={() => onChange(next)}
      >
        <Icon size={16} />
      </button>
    );
  }

  return (
    <div className="theme-selector" role="group" aria-label="Theme">
      {THEME_OPTIONS.map((option) => {
        const Icon = ICONS[option];
        return (
          <button key={option} type="button" aria-pressed={preference === option} onClick={() => onChange(option)}>
            <Icon size={14} />
            {LABELS[option]}
          </button>
        );
      })}
    </div>
  );
}
