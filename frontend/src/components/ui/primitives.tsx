import { cn } from "@/lib/utils";
import type { ButtonHTMLAttributes, HTMLAttributes, InputHTMLAttributes, ReactNode, SelectHTMLAttributes } from "react";

/**
 * The small set of building blocks the whole app is made of.
 *
 * Deliberately hand-written and few. A control library would bring far more than four screens
 * need, and everything here has to be styled against the design tokens anyway.
 */

// --- button ---------------------------------------------------------------------------------

type ButtonVariant = "primary" | "secondary" | "ghost" | "danger";
type ButtonSize = "sm" | "md" | "icon";

const BUTTON_VARIANTS: Record<ButtonVariant, string> = {
  primary: "bg-[var(--accent)] text-[var(--accent-contrast)] hover:opacity-90",
  secondary:
    "bg-[var(--surface-2)] text-[var(--text-primary)] border border-[var(--border)] hover:bg-[var(--surface-3)]",
  ghost: "text-[var(--text-secondary)] hover:bg-[var(--surface-2)] hover:text-[var(--text-primary)]",
  danger: "bg-[var(--status-critical)] text-white hover:opacity-90",
};

const BUTTON_SIZES: Record<ButtonSize, string> = {
  sm: "h-7 px-2.5 text-xs gap-1.5",
  md: "h-9 px-3.5 text-sm gap-2",
  icon: "h-8 w-8 justify-center",
};

export function Button({
  variant = "secondary",
  size = "md",
  className,
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: ButtonVariant; size?: ButtonSize }) {
  return (
    <button
      type="button"
      className={cn(
        "inline-flex items-center rounded-md font-medium transition-colors",
        "disabled:pointer-events-none disabled:opacity-40",
        BUTTON_VARIANTS[variant],
        BUTTON_SIZES[size],
        className,
      )}
      {...props}
    />
  );
}

// --- surfaces -------------------------------------------------------------------------------

export function Card({ className, ...props }: HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      className={cn(
        "rounded-lg border border-[var(--border)] bg-[var(--surface-1)]",
        className,
      )}
      {...props}
    />
  );
}

export function CardHeader({
  title,
  subtitle,
  actions,
  className,
}: {
  title: ReactNode;
  subtitle?: ReactNode;
  actions?: ReactNode;
  className?: string;
}) {
  return (
    <div
      className={cn(
        "flex items-start justify-between gap-3 border-b border-[var(--border)] px-4 py-3",
        className,
      )}
    >
      <div className="min-w-0">
        <h2 className="truncate text-sm font-semibold text-[var(--text-primary)]">{title}</h2>
        {subtitle ? (
          <p className="mt-0.5 truncate text-xs text-[var(--text-muted)]">{subtitle}</p>
        ) : null}
      </div>
      {actions ? <div className="flex shrink-0 items-center gap-1.5">{actions}</div> : null}
    </div>
  );
}

// --- form controls --------------------------------------------------------------------------

export function Input({ className, ...props }: InputHTMLAttributes<HTMLInputElement>) {
  return (
    <input
      className={cn(
        "h-9 w-full rounded-md border border-[var(--border)] bg-[var(--surface-0)] px-2.5 text-sm",
        "text-[var(--text-primary)] placeholder:text-[var(--text-muted)]",
        className,
      )}
      {...props}
    />
  );
}

export function Select({ className, children, ...props }: SelectHTMLAttributes<HTMLSelectElement>) {
  return (
    <select
      className={cn(
        "h-9 rounded-md border border-[var(--border)] bg-[var(--surface-0)] px-2 text-sm",
        "text-[var(--text-primary)]",
        className,
      )}
      {...props}
    >
      {children}
    </select>
  );
}

export function Field({
  label,
  hint,
  children,
  className,
}: {
  label: string;
  hint?: string;
  children: ReactNode;
  className?: string;
}) {
  return (
    <label className={cn("block", className)}>
      <span className="mb-1 block text-xs font-medium text-[var(--text-secondary)]">{label}</span>
      {children}
      {hint ? <span className="mt-1 block text-xs text-[var(--text-muted)]">{hint}</span> : null}
    </label>
  );
}

// --- indicators -----------------------------------------------------------------------------

type BadgeTone = "neutral" | "good" | "warning" | "critical" | "accent";

const BADGE_TONES: Record<BadgeTone, string> = {
  neutral: "bg-[var(--surface-2)] text-[var(--text-secondary)]",
  good: "bg-[var(--status-good)]/15 text-[var(--status-good)]",
  warning: "bg-[var(--status-warning)]/15 text-[var(--status-warning)]",
  critical: "bg-[var(--status-critical)]/15 text-[var(--status-critical)]",
  accent: "bg-[var(--accent)]/15 text-[var(--accent)]",
};

export function Badge({
  tone = "neutral",
  children,
  className,
  title,
}: {
  tone?: BadgeTone;
  children: ReactNode;
  className?: string;
  title?: string;
}) {
  return (
    <span
      title={title}
      className={cn(
        "inline-flex items-center gap-1 rounded px-1.5 py-0.5 text-xs font-medium",
        BADGE_TONES[tone],
        className,
      )}
    >
      {children}
    </span>
  );
}

/**
 * A labelled reading.
 *
 * Used instead of a chart wherever there is exactly one number to show: a sparkline of a value
 * that has not moved tells the reader less than the value itself.
 */
export function Stat({
  label,
  value,
  hint,
  tone,
  testId,
}: {
  label: string;
  value: ReactNode;
  hint?: ReactNode;
  tone?: BadgeTone;
  testId?: string;
}) {
  return (
    <div className="min-w-0">
      <div className="text-xs text-[var(--text-muted)]">{label}</div>
      <div
        data-testid={testId}
        className={cn(
          "tabular mt-0.5 truncate text-lg font-semibold",
          tone === "critical" && "text-[var(--status-critical)]",
          tone === "good" && "text-[var(--status-good)]",
        )}
      >
        {value}
      </div>
      {hint ? <div className="text-xs text-[var(--text-muted)]">{hint}</div> : null}
    </div>
  );
}

export function EmptyState({
  title,
  description,
  action,
}: {
  title: string;
  description?: string;
  action?: ReactNode;
}) {
  return (
    <div className="flex flex-col items-center justify-center gap-2 px-6 py-12 text-center">
      <p className="text-sm font-medium text-[var(--text-secondary)]">{title}</p>
      {description ? (
        <p className="max-w-md text-xs text-[var(--text-muted)]">{description}</p>
      ) : null}
      {action}
    </div>
  );
}

export function Spinner({ className }: { className?: string }) {
  return (
    <span
      role="status"
      aria-label="Loading"
      className={cn(
        "inline-block size-4 animate-spin rounded-full border-2 border-[var(--border-strong)] border-t-[var(--accent)]",
        className,
      )}
    />
  );
}

/** Inline error, used wherever a request can fail without taking the page down with it. */
export function ErrorNote({ message }: { message: string }) {
  return (
    <div className="rounded-md border border-[var(--status-critical)]/40 bg-[var(--status-critical)]/10 px-3 py-2 text-xs text-[var(--status-critical)]">
      {message}
    </div>
  );
}
