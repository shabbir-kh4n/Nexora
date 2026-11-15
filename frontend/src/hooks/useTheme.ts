import { useCallback, useEffect, useState } from "react";
import {
  DARK_QUERY,
  applyTheme,
  readPreference,
  resolveTheme,
  savePreference,
  type ThemePreference,
} from "../lib/theme";

export function useTheme() {
  const [preference, setPreference] = useState<ThemePreference>(readPreference);

  useEffect(() => {
    applyTheme(resolveTheme(preference));
    if (preference !== "system") return;
    const query = window.matchMedia(DARK_QUERY);
    const follow = () => applyTheme(resolveTheme("system"));
    query.addEventListener("change", follow);
    return () => query.removeEventListener("change", follow);
  }, [preference]);

  const choose = useCallback((next: ThemePreference) => {
    savePreference(next);
    setPreference(next);
  }, []);

  return { preference, setPreference: choose };
}
