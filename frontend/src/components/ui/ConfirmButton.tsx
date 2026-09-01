import { Button } from "@/components/ui/primitives";
import { useEffect, useState } from "react";

/**
 * A destructive action that asks first.
 *
 * Deleting a run destroys its samples, its log and its checkpoints, and there is no undo for it.
 * The button was previously a single click away from that.
 *
 * Confirmation is inline rather than a modal dialog: these buttons sit in list rows, and a modal
 * would have to name the row it came from to make sense, which is a sentence the user has to read
 * to learn something they already knew. Arming reverts on a timer so a half-pressed button does
 * not stay dangerous.
 */
export function ConfirmButton({
  onConfirm,
  children = "Delete",
  confirmLabel = "Really?",
  title,
  size = "sm",
}: {
  onConfirm: () => void;
  children?: React.ReactNode;
  confirmLabel?: string;
  title?: string;
  size?: "sm" | "md";
}) {
  const [armed, setArmed] = useState(false);

  useEffect(() => {
    if (!armed) return undefined;
    const timer = window.setTimeout(() => setArmed(false), 4000);
    return () => window.clearTimeout(timer);
  }, [armed]);

  return (
    <Button
      variant={armed ? "primary" : "ghost"}
      size={size}
      title={armed ? "Click again to confirm" : title}
      onClick={(event) => {
        event.stopPropagation();
        if (armed) {
          setArmed(false);
          onConfirm();
        } else {
          setArmed(true);
        }
      }}
    >
      {armed ? confirmLabel : children}
    </Button>
  );
}
