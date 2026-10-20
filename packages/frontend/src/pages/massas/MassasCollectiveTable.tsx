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
      {/* Resumen Colectivo de los 10 Productos */}
      <Card className="border-gray-200 shadow-sm">
        <CardContent className="pt-6">
          <div className="mb-4">
            <h2 className="text-lg font-semibold text-gray-900">{t('massas.collectiveTitle')}</h2>
            <p className="text-xs text-gray-500">{t('massas.collectiveSubtitle')}</p>
          </div>

          {/* VISTA ESCRITORIO (sm en adelante): Tabla Tabular Tradicional */}
          <div className="hidden sm:block overflow-x-auto rounded-lg border border-gray-200">
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

          {/* VISTA MÓVIL (< sm / 640px): Tarjetas/Filas Apiladas Compactas y Fluidas */}
          <div className="block sm:hidden space-y-2">
            {products.map((p) => {
              const hasProduction = p.totalBoxes > 0;
              return (
                <div
                  key={p.code}
                  className={`p-3 rounded-lg border flex items-center justify-between gap-3 ${
                    hasProduction
                      ? 'bg-amber-50/30 border-amber-200/80 shadow-xs'
                      : 'bg-white border-gray-100 text-gray-400'
                  }`}
                >
                  <div className="min-w-0 flex-1">
                    <p className={`text-sm font-semibold truncate ${hasProduction ? 'text-gray-900' : 'text-gray-600'}`}>
                      {p.name}
                    </p>
                    <p className="text-xs text-gray-400 mt-0.5">
                      {p.unitsPerBox} un/cx
                    </p>
                  </div>
                  <div className="text-right shrink-0">
                    <div className="text-xs text-gray-600 font-medium">
                      {p.totalBoxes} cx
                    </div>
                    <div className={`text-sm font-bold ${hasProduction ? 'text-amber-800' : 'text-gray-400'}`}>
                      {p.totalUnits} un
                    </div>
                  </div>
                </div>
              );
            })}

            {/* Total Geral Móvil */}
            <div className="mt-3 p-3.5 bg-amber-100/70 border border-amber-300 rounded-lg flex items-center justify-between">
              <span className="font-bold text-gray-900 text-sm">Total Geral</span>
              <div className="text-right">
                <span className="text-xs font-semibold text-gray-700 mr-2">{totalBoxes} cx</span>
                <span className="text-base font-extrabold text-amber-900">{totalUnits} un</span>
              </div>
            </div>
          </div>
        </CardContent>
      </Card>

      {/* Desglose Individual de Lanzamientos por Operario con RBAC */}
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
            <div className="space-y-2.5">
              {launches.map((launch) => {
                const isOwner = currentUserId === launch.user_id;
                const isEditing = editingLaunchId === launch.id;

                return (
                  <div
                    key={launch.id}
                    className={`p-3.5 rounded-lg border flex flex-col sm:flex-row sm:items-center justify-between gap-3 ${
                      isOwner ? 'bg-amber-50/40 border-amber-200' : 'bg-gray-50/60 border-gray-200'
                    }`}
                  >
                    <div className="flex-1 min-w-0">
                      <div className="flex items-center gap-2">
                        <span className="font-semibold text-gray-900 text-sm truncate">{launch.product_name}</span>
                        <span className="text-[11px] text-gray-400 shrink-0">#{launch.id}</span>
                      </div>
                      <div className="flex flex-wrap items-center gap-2 text-xs text-gray-500 mt-1">
                        <span>
                          {t('massas.user')}: <strong className="text-gray-700">{launch.display_name || launch.username}</strong>
                        </span>
                        <span>·</span>
                        <span>
                          {new Date(launch.created_at).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
                        </span>
                      </div>
                    </div>

                    {/* Cantidades y Acciones táctiles sin encimarse en móviles */}
                    <div className="flex items-center justify-between sm:justify-end gap-3 pt-2 sm:pt-0 border-t sm:border-t-0 border-gray-200/60">
                      {isEditing ? (
                        <div className="flex items-center gap-2 w-full sm:w-auto justify-end">
                          <Input
                            type="number"
                            min="1"
                            step="1"
                            inputMode="numeric"
                            value={editingBoxes}
                            onChange={(e) => onEditBoxesChange(e.target.value)}
                            className="h-9 w-20 text-center font-bold"
                          />
                          <span className="text-xs text-gray-500">cx</span>
                          <Button
                            size="sm"
                            onClick={() => onSaveEdit(launch.id)}
                            disabled={editingBusy || !editingBoxes || parseInt(editingBoxes, 10) <= 0}
                            className="h-9 px-3 bg-green-600 hover:bg-green-700 text-white"
                            title={t('massas.save')}
                          >
                            <Check className="h-4 w-4" />
                          </Button>
                          <Button
                            size="sm"
                            variant="outline"
                            onClick={onCancelEdit}
                            className="h-9 px-3"
                            title={t('massas.cancel')}
                          >
                            <X className="h-4 w-4" />
                          </Button>
                        </div>
                      ) : (
                        <>
                          <div className="text-left sm:text-right">
                            <span className="text-sm font-bold text-gray-900">
                              {launch.boxes} cx
                            </span>
                            <span className="text-xs text-gray-400 mx-1">·</span>
                            <span className="text-sm font-bold text-amber-800">
                              {launch.units} un
                            </span>
                          </div>

                          <div className="flex items-center gap-1 shrink-0 ml-2">
                            {isOwner ? (
                              <>
                                <Button
                                  size="sm"
                                  variant="ghost"
                                  onClick={() => onStartEdit(launch)}
                                  className="h-9 w-9 p-0 text-gray-600 hover:text-amber-700 active:bg-amber-100"
                                  title={t('massas.edit')}
                                >
                                  <Edit2 className="h-4 w-4" />
                                </Button>
                                <Button
                                  size="sm"
                                  variant="ghost"
                                  onClick={() => onDelete(launch.id)}
                                  disabled={deletingId === launch.id}
                                  className="h-9 w-9 p-0 text-gray-600 hover:text-red-700 active:bg-red-50"
                                  title={t('massas.delete')}
                                >
                                  <Trash2 className="h-4 w-4" />
                                </Button>
                              </>
                            ) : (
                              <span
                                className="inline-flex items-center gap-1 text-[11px] text-gray-500 bg-gray-100/90 px-2.5 py-1 rounded-md"
                                title="Lançamento feito por outro operador (somente leitura)"
                              >
                                <ShieldAlert className="h-3.5 w-3.5 text-gray-400" />
                                {launch.username}
                              </span>
                            )}
                          </div>
                        </>
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
