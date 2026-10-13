import { useTranslation } from 'react-i18next';
import { Button } from './button';
import { Dialog } from './dialog';

interface ConfirmDialogProps {
  open: boolean;
  onClose: () => void;
  onConfirm: () => void;
  /** Visible title naming the action and its target, e.g. "Excluir relatório de 15/06/2026". */
  title: string;
  panelTestId?: string;
}

/**
 * The confirmation step for a destructive action.
 *
 * The confirmation is the dialog itself: cancel is the initial focus target because it sits
 * first, Escape and the backdrop both close without acting, and only the destructive button
 * proceeds. It exists so a delete is never one stray click away.
 *
 * The title names the action and its record, while the description carries the neutral warning
 * from the catalogue and is wired through `aria-describedby`, so a screen reader announces what
 * will happen once — as context, not folded into the name. The record appears only in the title:
 * repeating it in the described element would make the same fact arrive twice and would turn a
 * fixed, reviewable sentence into a template.
 */
export function ConfirmDialog({ open, onClose, onConfirm, title, panelTestId }: ConfirmDialogProps) {
  const { t } = useTranslation();
  return (
    <Dialog
      open={open}
      onClose={onClose}
      labelledBy="confirm-dialog-title"
      describedBy="confirm-dialog-description"
      panelTestId={panelTestId}
    >
      <div className="p-6">
        <h3 id="confirm-dialog-title" className="text-lg font-semibold text-gray-900 mb-2">{title}</h3>
        <p id="confirm-dialog-description" className="text-sm text-gray-600 mb-4">
          {t('ui.deleteConfirm')}
        </p>
        <div className="flex justify-end gap-2">
          <Button type="button" variant="outline" size="sm" onClick={onClose}>
            {t('ui.cancel')}
          </Button>
          <Button type="button" variant="destructive" size="sm" onClick={onConfirm}>
            {t('ui.deletePermanently')}
          </Button>
        </div>
      </div>
    </Dialog>
  );
}
