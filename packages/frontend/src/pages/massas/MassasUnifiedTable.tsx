import { useTranslation } from 'react-i18next';
import { CloudUpload, Loader2, Smartphone } from 'lucide-react';
import type { AeroImportRequest, MassasRowState, MassasUnifiedRow } from '../../lib/massas-unified';

interface MassasUnifiedTableProps {
  rows: MassasUnifiedRow[];
  date: string;
  canSync?: boolean;
  onSync?: () => void;
  onImport?: (request: AeroImportRequest) => void;
  importingEntLan?: number | null;
  onToggleAck?: (row: MassasUnifiedRow) => void;
}

const STATE_LABEL: Record<MassasRowState, string> = {
  reconciled: 'massas.unified.stateReconciled',
  pendingErp: 'massas.unified.statePending',
  erpOnly: 'massas.unified.stateErpOnly',
};

const STATE_CLASS: Record<MassasRowState, string> = {
  reconciled: 'bg-green-100 text-green-800',
  pendingErp: 'bg-amber-100 text-amber-800',
  erpOnly: 'bg-sky-100 text-sky-800',
};

/**
 * The plant's day as one table.
 *
 * The two screens used to be two tabs, and the operator had to guess which list held the truth. Here every
 * box is one row with its real state, so the answer is on the row itself: paired, owed to Delphi, or typed on
 * the company tablet and waiting to be brought in.
 *
 * A row only offers what can actually be done: an importable seat gets the import, a pending launch can be
 * acknowledged on this phone (the mark belongs to the device that carried the boxes), and the day's sending
 * stays the header action because the shortcut travels the whole day at once.
 */
export function MassasUnifiedTable({
  rows,
  date,
  canSync = false,
  onSync,
  onImport,
  importingEntLan = null,
  onToggleAck,
}: MassasUnifiedTableProps) {
  const { t } = useTranslation();

  return (
    <section data-testid="massas-unified" className="space-y-3">
      <div>
        <h2 className="text-lg font-semibold text-gray-900">{t('massas.unified.title', 'Produção do dia')}</h2>
        <p className="text-xs text-gray-500">
          {t('massas.unified.subtitle', 'Trindade e tablet da empresa, cada caixa contada uma vez.')}
        </p>
      </div>

      {rows.length === 0 ? (
        <p className="text-sm text-gray-600" data-testid="massas-unified-empty">
          {t('massas.unified.empty', 'Nenhuma massa lançada neste dia.')}
        </p>
      ) : (
        <ul className="space-y-2" data-testid="massas-unified-rows">
          {rows.map((row) => {
            const busy = importingEntLan !== null && row.entLans.includes(importingEntLan);
            return (
              <li
                key={row.key}
                data-testid={`massas-unified-row-${row.key}`}
                data-state={row.state}
                className="rounded-lg border border-gray-200 p-3"
              >
                <div className="flex items-start justify-between gap-2">
                  <div className="min-w-0">
                    <p className="flex flex-wrap items-center gap-1.5 text-sm font-medium text-gray-900">
                      <span className="truncate">{row.operatorName}</span>
                      <span className={`shrink-0 rounded px-1.5 py-0.5 text-[11px] font-semibold ${STATE_CLASS[row.state]}`}>
                        {t(STATE_LABEL[row.state])}
                      </span>
                      {row.state === 'pendingErp' && row.tabletAcked && (
                        <span className="shrink-0 rounded bg-gray-100 px-1.5 py-0.5 text-[11px] font-semibold text-gray-700">
                          {t('massas.unified.acked', 'já na tablet')}
                        </span>
                      )}
                    </p>
                    <p className="mt-0.5 truncate text-sm text-gray-600">{row.productName}</p>
                    <p className="mt-0.5 text-xs tabular-nums text-gray-500">
                      {row.boxes} cx · {row.units} un
                    </p>
                  </div>

                  <div className="flex shrink-0 flex-col items-end gap-1.5">
                    {row.state === 'erpOnly' && onImport && (
                      row.importable ? (
                        <button
                          type="button"
                          onClick={() =>
                            onImport({
                              entLan: row.entLans[0],
                              productCode: row.productCode,
                              boxes: row.boxes,
                              date,
                            })
                          }
                          disabled={busy}
                          className="inline-flex min-h-[44px] items-center gap-1.5 rounded-lg bg-sky-600 px-3 text-xs font-semibold text-white disabled:opacity-50"
                          data-testid={`massas-import-${row.entLans[0]}`}
                        >
                          {busy ? <Loader2 className="h-4 w-4 animate-spin" /> : <CloudUpload className="h-4 w-4" />}
                          {t('massas.unified.import', 'Importar')}
                        </button>
                      ) : (
                        <span className="max-w-[9rem] text-right text-[11px] text-gray-500">
                          {t('massas.unified.notWholeBoxes', 'Não fecha caixas inteiras')}
                        </span>
                      )
                    )}

                    {row.state === 'pendingErp' && (
                      <>
                        {onSync && (
                          <button
                            type="button"
                            onClick={onSync}
                            disabled={!canSync}
                            className="inline-flex min-h-[44px] items-center gap-1.5 rounded-lg bg-amber-600 px-3 text-xs font-semibold text-white disabled:opacity-50"
                            data-testid="massas-unified-send"
                          >
                            <CloudUpload className="h-4 w-4" />
                            {t('massas.unified.send', 'Enviar ao ERP')}
                          </button>
                        )}
                        {onToggleAck && (
                          <button
                            type="button"
                            onClick={() => onToggleAck(row)}
                            aria-pressed={row.tabletAcked}
                            className="inline-flex min-h-[44px] items-center gap-1.5 rounded-lg border border-gray-300 px-3 text-xs font-semibold text-gray-700"
                            data-testid={`massas-ack-${row.launchIds[0]}`}
                          >
                            <Smartphone className="h-4 w-4" />
                            {row.tabletAcked
                              ? t('massas.unified.unack', 'Desfazer')
                              : t('massas.unified.ack', 'Já na tablet')}
                          </button>
                        )}
                      </>
                    )}
                  </div>
                </div>
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}
