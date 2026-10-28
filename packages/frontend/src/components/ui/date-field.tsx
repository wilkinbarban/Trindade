import { Calendar } from 'lucide-react';
import { cn } from '../../lib/utils';
import { formatDateLabel } from '../../lib/date';

interface DateFieldProps {
  id: string;
  /** Accessible name. Never drawn: the trigger shows the day itself and a calendar icon. */
  label: string;
  value: string;
  onChange: (value: string) => void;
  min?: string;
  max?: string;
  testId?: string;
  className?: string;
}

/**
 * A date field that looks the same on every phone.
 *
 * A bare `<input type="date">` cannot be trusted with the layout: WebKit draws its text from the
 * operating system's locale, so a pt-BR iPhone renders "28 de out. de 2026" and the control claims an
 * intrinsic width of roughly 250px no matter what the flex container around it says. `min-w-0` cannot
 * shrink it and no CSS reaches the shadow text, so it spilled out of its slot and the next control was
 * painted on top of its right border.
 *
 * The fix is the pattern mobile web settled on: the box the operator sees is ours (a compact
 * `28/10/2026` with a calendar icon, ~135px, sized by our own text), and the native input sits on top
 * of it, transparent and stretched, so it owns the tap and opens the system wheel. Tapping anywhere on
 * the box is tapping the real control, which keeps `showPicker`, keyboard handling, min/max and every
 * accessibility behaviour of the native element.
 *
 * `overflow-hidden` is load-bearing: it clips the invisible input to the trigger box, so a residual
 * intrinsic width can never extend the tap target over the control that sits beside it.
 */
export function DateField({ id, label, value, onChange, min, max, testId, className }: DateFieldProps) {
  return (
    <div
      className={cn(
        'relative inline-flex h-11 shrink-0 items-center gap-1.5 overflow-hidden rounded-lg border border-gray-300 bg-white px-2.5 text-sm font-medium text-gray-900',
        'transition-colors focus-within:border-blue-500 focus-within:ring-2 focus-within:ring-blue-500/30 sm:h-10',
        className,
      )}
    >
      <Calendar className="h-4 w-4 shrink-0 text-gray-500" aria-hidden="true" />
      <span aria-hidden="true" className="tabular-nums">
        {formatDateLabel(value)}
      </span>
      <label htmlFor={id} className="sr-only">
        {label}
      </label>
      <input
        id={id}
        type="date"
        value={value}
        min={min}
        max={max}
        onChange={(event) => onChange(event.target.value)}
        data-testid={testId}
        className="absolute inset-0 h-full w-full min-w-0 cursor-pointer opacity-0"
      />
    </div>
  );
}
