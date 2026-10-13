import { useEffect, useRef, type ReactNode } from 'react';

/** Everything a keyboard user can land on inside the dialog, in document order. */
const FOCUSABLE =
  'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

/**
 * Where Tab or Shift+Tab lands next inside a dialog.
 *
 * Kept as a pure function because the wrap-around is the part that is easy to get wrong and
 * the only part that can be tested without a browser: from the last element forward returns to
 * the first, backward from the first returns to the last, a single element stays put, and an
 * empty dialog reports no target so the caller can keep focus on the panel itself instead of
 * letting it escape behind the overlay.
 */
export function nextFocusIndex(current: number, count: number, shift: 1 | -1): number {
  if (count <= 0) return -1;
  if (current < 0 || current >= count) return shift > 0 ? 0 : count - 1;
  return (current + shift + count) % count;
}

interface DialogProps {
  open: boolean;
  onClose: () => void;
  /** Id of the element naming the dialog; the title inside `children`. */
  labelledBy: string;
  /** Id of a short, neutral sentence explaining what the dialog is for, when one exists. */
  describedBy?: string;
  panelTestId?: string;
  children: ReactNode;
}

/**
 * The one accessible modal used by the export previews.
 *
 * It owns the behaviours the overlays used to lack: dialog semantics, an accessible name, an
 * optional accessible description, an initial focus target inside the panel, Escape to close, Tab
 * that cycles inside instead of reaching the page behind, and focus returned to whatever opened
 * it. Background scroll is not locked, which is not required for the keyboard and screen-reader
 * behaviour this exists for.
 *
 * A dialog that only asks a question needs no description; one that explains or warns should pass
 * `describedBy`, so the sentence is announced once as context instead of being read as a name.
 */
export function Dialog({ open, onClose, labelledBy, describedBy, panelTestId, children }: DialogProps) {
  const panelRef = useRef<HTMLDivElement>(null);
  const restoreRef = useRef<HTMLElement | null>(null);
  // Kept in a ref so the effect does not depend on a callback the caller recreates on every
  // render: re-running it while the dialog is open would recapture the opener and could restore
  // focus to a node that has since been replaced.
  const closeRef = useRef(onClose);
  closeRef.current = onClose;

  useEffect(() => {
    if (!open) return;
    const panel = panelRef.current;
    restoreRef.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;

    const focusable = () => Array.from(panel?.querySelectorAll<HTMLElement>(FOCUSABLE) ?? []);
    (focusable()[0] ?? panel)?.focus();

    function onKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        event.preventDefault();
        closeRef.current();
        return;
      }
      if (event.key !== 'Tab') return;
      const items = focusable();
      if (items.length === 0) {
        event.preventDefault();
        panel?.focus();
        return;
      }
      event.preventDefault();
      const current = items.indexOf(document.activeElement as HTMLElement);
      items[nextFocusIndex(current, items.length, event.shiftKey ? -1 : 1)]?.focus();
    }

    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('keydown', onKeyDown);
      const restore = restoreRef.current;
      if (restore && document.contains(restore)) restore.focus();
    };
  }, [open]);

  if (!open) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center">
      <div className="absolute inset-0 bg-black/50" onClick={onClose} aria-hidden="true" />
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={labelledBy}
        aria-describedby={describedBy}
        tabIndex={-1}
        className="relative bg-white rounded-lg shadow-xl max-w-lg w-full mx-4 max-h-[85vh] flex flex-col"
        data-testid={panelTestId}
      >
        {children}
      </div>
    </div>
  );
}
