import { useEffect, useState } from "react";

export type Theme = "light" | "dark";

const STORAGE_KEY = "fsp-theme";

function preferredTheme(): Theme {
  const stored = localStorage.getItem(STORAGE_KEY);
  if (stored === "light" || stored === "dark") return stored;
  return window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
}

/**
 * Theme state, stamped onto the document root.
 *
 * Charts read their colours from CSS custom properties at draw time, so they are re-rendered on a
 * theme change rather than being left with the previous mode's palette.
 */
export function useTheme(): { theme: Theme; toggle: () => void } {
  const [theme, setTheme] = useState<Theme>(() =>
    typeof window === "undefined" ? "dark" : preferredTheme(),
  );

  useEffect(() => {
    document.documentElement.setAttribute("data-theme", theme);
    localStorage.setItem(STORAGE_KEY, theme);
  }, [theme]);

  return { theme, toggle: () => setTheme((current) => (current === "dark" ? "light" : "dark")) };
}
