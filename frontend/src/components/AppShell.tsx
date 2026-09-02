import { Button } from "@/components/ui/primitives";
import { ShortcutsOverlay } from "@/components/ui/ShortcutsOverlay";
import { useTheme } from "@/lib/theme";
import { cn } from "@/lib/utils";
import { Link, Outlet, useRouterState } from "@tanstack/react-router";

const NAV = [
  { to: "/runs", label: "Runs" },
  { to: "/systems", label: "Systems" },
  { to: "/objects", label: "Objects" },
  { to: "/compare", label: "Compare" },
] as const;

/** Application chrome: navigation, theme switch, and the routed page beneath. */
export function AppShell() {
  const { theme, toggle } = useTheme();
  const pathname = useRouterState({ select: (state) => state.location.pathname });

  return (
    <div className="flex h-full flex-col bg-[var(--surface-0)]">
      <header className="flex h-12 shrink-0 items-center gap-6 border-b border-[var(--border)] px-4">
        <Link to="/runs" className="flex items-center gap-2">
          <span
            aria-hidden
            className="size-4 rounded-sm bg-[var(--accent)]"
            style={{ boxShadow: "inset 0 0 0 2px var(--surface-0)" }}
          />
          <span className="text-sm font-semibold tracking-tight">Feedback Systems Playground</span>
        </Link>

        <nav className="flex items-center gap-1">
          {NAV.map((item) => {
            const active = pathname.startsWith(item.to);
            return (
              <Link
                key={item.to}
                to={item.to}
                className={cn(
                  "rounded-md px-2.5 py-1 text-sm transition-colors",
                  active
                    ? "bg-[var(--surface-2)] text-[var(--text-primary)]"
                    : "text-[var(--text-secondary)] hover:bg-[var(--surface-2)]",
                )}
              >
                {item.label}
              </Link>
            );
          })}
        </nav>

        <div className="ml-auto flex items-center gap-1">
          <ShortcutsOverlay />
          <Button
            variant="ghost"
            size="icon"
            onClick={toggle}
            aria-label={theme === "dark" ? "Switch to light theme" : "Switch to dark theme"}
            title={theme === "dark" ? "Switch to light theme" : "Switch to dark theme"}
          >
            {theme === "dark" ? "☾" : "☀"}
          </Button>
        </div>
      </header>

      <main className="min-h-0 flex-1 overflow-auto">
        <Outlet />
      </main>
    </div>
  );
}
