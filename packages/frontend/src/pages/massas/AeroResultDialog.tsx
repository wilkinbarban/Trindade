import { AlertTriangle, CheckCircle2, XCircle } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { Button } from '../../components/ui/button';
import { Dialog } from '../../components/ui/dialog';
import type { AeroResultTone } from '../../lib/aero-feedback';

interface AeroResultDialogProps {
  open: boolean;
  tone: AeroResultTone;
  title: string;
  message: string;
  /** A second line the operator needs before closing, such as where the new seat will appear. */
  hint?: string | null;
  onClose: () => void;
  testId?: string;
}

const TONE = {
  ok: {
    Icon: CheckCircle2,
    icon: 'text-green-600',
    panel: 'border-green-200',
    button: 'bg-green-600 hover:bg-green-700 text-white',
  },
  warn: {
    Icon: AlertTriangle,
    icon: 'text-amber-600',
    panel: 'border-amber-200',
    button: 'bg-amber-600 hover:bg-amber-700 text-white',
  },
  error: {
    Icon: XCircle,
    icon: 'text-red-600',
    panel: 'border-red-200',
    button: 'bg-red-600 hover:bg-red-700 text-white',
  },
} as const;

/**
 * The verdict of an action that left the app and came back.
 *
 * The phone runs the Atajos app, so the answer arrives as a URL, not as a screen: without this dialog the
 * operator returns to a page that looks exactly like the one they left. It is one dialog for the connection
 * test and for the movements sync, because both ask the same thing -- what did the company say -- and the
 * close target is the same thumb-sized button either way.
 */
export function AeroResultDialog({
  open,
  tone,
  title,
  message,
  hint = null,
  onClose,
  testId = 'aero-result-modal',
}: AeroResultDialogProps) {
  const { t } = useTranslation();
  const { Icon, icon, panel, button } = TONE[tone];

  return (
    <Dialog open={open} onClose={onClose} labelledBy="aero-result-title" panelTestId={testId}>
      <div className={`p-6 space-y-4 max-w-sm w-full text-center border-t-4 ${panel}`}>
        <Icon className={`mx-auto h-10 w-10 ${icon}`} aria-hidden="true" />
        <h2 id="aero-result-title" className="text-lg font-bold text-gray-900">
          {title}
        </h2>
        <p className="text-sm leading-relaxed text-gray-600" data-testid="aero-result-message">
          {message}
        </p>
        {hint && (
          <p
            className="rounded-lg border border-amber-200 bg-amber-50 p-2.5 text-xs leading-relaxed text-amber-900"
            data-testid="aero-result-hint"
          >
            {hint}
          </p>
        )}
        <div className="pt-2">
          <Button
            onClick={onClose}
            className={`min-h-[44px] w-full font-semibold ${button}`}
            data-testid="aero-result-close"
          >
            {t('massas.close', 'Fechar')}
          </Button>
        </div>
      </div>
    </Dialog>
  );
}
