import { useTranslation } from 'react-i18next';
import { Card, CardContent } from '../../components/ui/card';
import { Button } from '../../components/ui/button';
import { Input } from '../../components/ui/input';
import { Edit2, Trash2, Check, X, ShieldAlert } from 'lucide-react';
import type { MassaProductTotal, MassaIndividualLaunch } from '../../lib/massas';

interface MassasCollectiveTableProps {
  products: MassaProductTotal[];
  launches: MassaIndividualLaunch[];
  totalBoxes: number;
  totalUnits: number;
  currentUserId?: number;
  editingLaunchId: number | null;
  editingBoxes: string;
  editingBusy: boolean;
  onStartEdit: (launch: MassaIndividualLaunch) => void;
  onCancelEdit: () => void;
  onSaveEdit: (launchId: number) => void;
  onEditBoxesChange: (val: string) => void;
  onDelete: (launchId: number) => void;
  deletingId: number | null;
}

export function MassasCollectiveTable({
  products,
  launches,
  totalBoxes,
  totalUnits,
  currentUserId,
  editingLaunchId,
  editingBoxes,
  editingBusy,
  onStartEdit,
  onCancelEdit,
  onSaveEdit,
  onEditBoxesChange,
  onDelete,
  deletingId,
}: MassasCollectiveTableProps) {
  const { t } = useTranslation();

  return (
    <div className="space-y-6">
      {/* Summary Table of the 10 Products */}
      <Card className="border-gray-200 shadow-sm">
        <CardContent className="pt-6">
          <div className="mb-4">
            <h2 className="text-lg font-semibold text-gray-900">{t('massas.collectiveTitle')}</h2>
            <p className="text-xs text-gray-500">{t('massas.collectiveSubtitle')}</p>
          </div>

          <div className="overflow-x-auto rounded-lg border border-gray-200">
            <table className="w-full text-left text-sm border-collapse">
              <thead>
                <tr className="bg-gray-100 text-gray-700 text-xs font-semibold uppercase">
                  <th className="py-3 px-4">{t('massas.product')}</th>
                  <th className="py-3 px-4 text-center">Un/Cx</th>
                  <th className="py-3 px-4 text-right">{t('massas.boxes')}</th>
                  <th className="py-3 px-4 text-right">{t('massas.units')}</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-200 bg-white">
                {products.map((p) => (
                  <tr
                    key={p.code}
                    className={p.totalBoxes > 0 ? 'bg-amber-50/20 font-medium' : 'text-gray-500'}
                  >
                    <td className="py-2.5 px-4 text-gray-900">{p.name}</td>
                    <td className="py-2.5 px-4 text-center text-xs text-gray-500">{p.unitsPerBox}</td>
                    <td className="py-2.5 px-4 text-right font-semibold text-gray-900">
                      {p.totalBoxes > 0 ? `${p.totalBoxes} cx` : '0'}
                    </td>
                    <td className="py-2.5 px-4 text-right font-bold text-amber-800">
                      {p.totalUnits > 0 ? `${p.totalUnits} un` : '0'}
                    </td>
                  </tr>
                ))}
              </tbody>
              <tfoot>
                <tr className="bg-gray-100 text-gray-900 font-bold border-t-2 border-gray-300">
                  <td colSpan={2} className="py-3 px-4 text-base">
                    Total Geral
                  </td>
                  <td className="py-3 px-4 text-right text-base text-gray-900">
                    {totalBoxes} cx
                  </td>
                  <td className="py-3 px-4 text-right text-base text-amber-900">
                    {totalUnits} un
                  </td>
                </tr>
              </tfoot>
            </table>
          </div>
        </CardContent>
      </Card>

      {/* Individual Launches by Operator with RBAC Edit/Delete */}
      <Card className="border-gray-200 shadow-sm">
        <CardContent className="pt-6">
          <div className="mb-4">
            <h2 className="text-lg font-semibold text-gray-900">{t('massas.userLaunchesTitle')}</h2>
            <p className="text-xs text-gray-500">{t('massas.userLaunchesSubtitle')}</p>
          </div>

          {launches.length === 0 ? (
            <div className="py-8 text-center text-gray-400 bg-gray-50 rounded-lg border border-dashed border-gray-200 text-sm">
              {t('massas.noLaunches')}
            </div>
          ) : (
            <div className="space-y-2">
              {launches.map((launch) => {
                const isOwner = currentUserId === launch.user_id;
                const isEditing = editingLaunchId === launch.id;

                return (
                  <div
                    key={launch.id}
                    className={`p-3.5 rounded-lg border flex flex-col sm:flex-row sm:items-center justify-between gap-3 ${
                      isOwner ? 'bg-amber-50/40 border-amber-200' : 'bg-gray-50/50 border-gray-200'
                    }`}
                  >
                    <div className="flex-1 min-w-0">
                      <div className="flex items-center gap-2">
                        <span className="font-semibold text-gray-900 text-sm">{launch.product_name}</span>
                        <span className="text-xs text-gray-400">#{launch.id}</span>
                      </div>
                      <div className="flex items-center gap-3 text-xs text-gray-500 mt-0.5">
                        <span>
                          {t('massas.user')}: <strong className="text-gray-700">{launch.display_name || launch.username}</strong>
                        </span>
                        <span>·</span>
                        <span>
                          {new Date(launch.created_at).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
                        </span>
                      </div>
                    </div>

                    {/* Quantities & Actions */}
                    <div className="flex items-center justify-between sm:justify-end gap-4 shrink-0">
                      {isEditing ? (
                        <div className="flex items-center gap-2">
                          <Input
                            type="number"
                            min="1"
                            step="1"
                            inputMode="numeric"
                            value={editingBoxes}
                            onChange={(e) => onEditBoxesChange(e.target.value)}
                            className="h-8 w-20 text-center font-bold"
                          />
                          <span className="text-xs text-gray-500">cx</span>
                          <Button
                            size="sm"
                            onClick={() => onSaveEdit(launch.id)}
                            disabled={editingBusy || !editingBoxes || parseInt(editingBoxes, 10) <= 0}
                            className="h-8 px-2 bg-green-600 hover:bg-green-700 text-white"
                            title={t('massas.save')}
                          >
                            <Check className="h-4 w-4" />
                          </Button>
                          <Button
                            size="sm"
                            variant="outline"
                            onClick={onCancelEdit}
                            className="h-8 px-2"
                            title={t('massas.cancel')}
                          >
                            <X className="h-4 w-4" />
                          </Button>
                        </div>
                      ) : (
                        <div className="text-right">
                          <div className="text-sm font-bold text-gray-900">
                            {launch.boxes} cx · <span className="text-amber-800">{launch.units} un</span>
                          </div>
                        </div>
                      )}

                      {!isEditing && (
                        <div className="flex items-center gap-1.5 ml-2">
                          {isOwner ? (
                            <>
                              <Button
                                size="sm"
                                variant="ghost"
                                onClick={() => onStartEdit(launch)}
                                className="h-8 w-8 p-0 text-gray-600 hover:text-amber-700"
                                title={t('massas.edit')}
                              >
                                <Edit2 className="h-4 w-4" />
                              </Button>
                              <Button
                                size="sm"
                                variant="ghost"
                                onClick={() => onDelete(launch.id)}
                                disabled={deletingId === launch.id}
                                className="h-8 w-8 p-0 text-gray-600 hover:text-red-700"
                                title={t('massas.delete')}
                              >
                                <Trash2 className="h-4 w-4" />
                              </Button>
                            </>
                          ) : (
                            <span
                              className="inline-flex items-center gap-1 text-[11px] text-gray-400 bg-gray-100 px-2 py-0.5 rounded-full"
                              title="Lançamento feito por outro operador (somente leitura)"
                            >
                              <ShieldAlert className="h-3 w-3" />
                              {launch.username}
                            </span>
                          )}
                        </div>
                      )}
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
