import { useTranslation } from 'react-i18next';
import { CloudUpload, Loader2, Smartphone, UserPlus } from 'lucide-react';
import { MassasBalanceBar } from './MassasBalanceBar';
import {
  canCreateOperatorRow,
  canImportRow,
  massasBalance,
  type AeroImportRequest,
  type MassasRowState,
  type MassasUnifiedRow,
} from '../../lib/massas-unified';

interface MassasUnifiedTableProps {
  rows: MassasUnifiedRow[];
  date: string;
  /** Who is reading, so the import action follows the same rule the backend enforces. */
  currentUserId?: number;
  isAdmin?: boolean;
  canSync?: boolean;
  /**
   * True on a system where the integration cannot run. The actions stay visible and disabled, with the
   * banner above explaining which devices do work: a button that disappears reads as a feature that does
   * not exist, and the operator has no way to learn otherwise.
   */
  deviceLimited?: boolean;
  /**
   * Sends this row's launch to the ERP.
   *
   * It takes the row because the envelope is built from its product, its boxes and its operator: the day's
   * movements read is a different trip with a different endpoint, and the header keeps that one.
   */
  onSendToErp?: (row: MassasUnifiedRow) => void;
  onImport?: (request: AeroImportRequest) => void;
  importingEntLan?: number | null;
  onCreateOperator?: (row: MassasUnifiedRow) => void;
  creatingOperatorEntLan?: number | null;
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
  currentUserId,
  isAdmin = false,
  canSync = false,
  deviceLimited = false,
  onSendToErp,
  onImport,
  importingEntLan = null,
  onCreateOperator,
  creatingOperatorEntLan = null,
  onToggleAck,
}: MassasUnifiedTableProps) {
  const { t } = useTranslation();
  const balance = massasBalance(rows);

  return (
    <section data-testid="massas-unified" className="space-y-3">
      <div>
        <h2 className="text-lg font-semibold text-gray-900">{t('massas.unified.title', 'Produção do dia')}</h2>
        <p className="text-xs text-gray-500">
          {t('massas.unified.subtitle', 'Trindade e tablet da empresa, cada caixa contada uma vez.')}
        </p>
      </div>

      <MassasBalanceBar
        produced={balance.produced}
        registered={balance.registered}
        difference={balance.difference}
      />

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
                    {row.state === 'erpOnly' &&
                      onImport &&
                      canImportRow(row, { userId: currentUserId, isAdmin }) && (
                        <button
                          type="button"
                          onClick={() =>
                            onImport({
                              entLan: row.entLans[0],
                              operatorName: row.operatorName,
                              productCode: row.productCode,
                              boxes: row.boxes,
                              date,
                            })
                          }
                          disabled={busy || deviceLimited}
                          className="inline-flex min-h-[44px] items-center gap-1.5 rounded-lg bg-sky-600 px-3 text-xs font-semibold text-white disabled:opacity-50"
                          data-testid={`massas-import-${row.entLans[0]}`}
                        >
                          {busy ? <Loader2 className="h-4 w-4 animate-spin" /> : <CloudUpload className="h-4 w-4" />}
                          {t('massas.unified.import', 'Importar')}
                        </button>
                      )}
                    {row.state === 'erpOnly' &&
                      onCreateOperator &&
                      canCreateOperatorRow(row, { isAdmin }) && (
                        <button
                          type="button"
                          onClick={() => onCreateOperator(row)}
                          disabled={creatingOperatorEntLan === row.entLans[0] || deviceLimited}
                          className="inline-flex min-h-[44px] items-center gap-1.5 rounded-lg bg-emerald-600 px-3 text-xs font-semibold text-white hover:bg-emerald-700 disabled:opacity-50"
                          data-testid={`massas-create-operator-${row.entLans[0]}`}
                        >
                          {creatingOperatorEntLan === row.entLans[0] ? (
                            <Loader2 className="h-4 w-4 animate-spin" />
                          ) : (
                            <UserPlus className="h-4 w-4" />
                          )}
                          {t('massas.unified.createOperator', { name: row.operatorName })}
                        </button>
                      )}
                    {row.state === 'erpOnly' && !isAdmin && row.ownerUserId === null && row.importable && (
                      <span className="max-w-[9rem] text-right text-[11px] text-gray-500">
                        {t('massas.unified.operatorNotLinked', 'Operador não vinculado')}
                      </span>
                    )}
                    {/* The seat itself says why it cannot come in, whoever is reading: the reason is the
                        ERP's boxes, not the viewer's role. */}
                    {row.state === 'erpOnly' && !row.importable && (
                      <span className="max-w-[9rem] text-right text-[11px] text-gray-500">
                        {t('massas.unified.notWholeBoxes', 'Não fecha caixas inteiras')}
                      </span>
                    )}

                    {row.state === 'pendingErp' && (
                      <>
                        {onSendToErp && (
                          <button
                            type="button"
                            onClick={() => onSendToErp(row)}
                            disabled={!canSync || deviceLimited}
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

