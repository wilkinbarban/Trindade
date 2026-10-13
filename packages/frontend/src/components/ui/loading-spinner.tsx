import { useTranslation } from 'react-i18next';

/**
 * The app's single loading indicator.
 *
 * A bare spinning div is invisible to a screen reader: there is no text and no status role, so
 * a keyboard or AT user gets no signal that work is in progress. The label comes from the same
 * catalogue as the rest of the UI rather than a hard-coded string.
 *
 * The role is `status`, which is announced politely: it must not interrupt what the operator is
 * reading, and it disappears with the spinner, so no announcement is left behind.
 */
export function LoadingSpinner({ className }: { className?: string }) {
  const { t } = useTranslation();
  return (
    <div
      role="status"
      className={`animate-spin rounded-full h-8 w-8 border-b-2 border-blue-600 ${className || ''}`}
    >
      <span className="sr-only">{t('ui.loading')}</span>
    </div>
  );
}
