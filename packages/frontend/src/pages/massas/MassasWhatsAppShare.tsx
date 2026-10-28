import { useTranslation } from 'react-i18next';
import { Card, CardContent } from '../../components/ui/card';
import { Button } from '../../components/ui/button';
import { Input } from '../../components/ui/input';
import { Send, Copy } from 'lucide-react';
import { formatPhoneMask } from '../../lib/phone';

interface MassasWhatsAppShareProps {
  phone: string;
  onPhoneChange: (val: string) => void;
  previewMessage: string;
  onCopy: () => void;
  onOpenWhatsApp: () => void;
  copyNote: string;
}

export function MassasWhatsAppShare({
  phone,
  onPhoneChange,
  previewMessage,
  onCopy,
  onOpenWhatsApp,
  copyNote,
}: MassasWhatsAppShareProps) {
  const { t } = useTranslation();

  return (
    <Card className="border-gray-200 shadow-sm">
      <CardContent className="pt-6 space-y-4">
        <div>
          <h2 className="text-lg font-semibold text-gray-900">{t('massas.shareTitle')}</h2>
          <p className="text-xs text-gray-500">{t('massas.shareSubtitle')}</p>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-2 gap-6 items-start">
          {/* Phone input and actions */}
          <div className="space-y-4">
            <div>
              <label htmlFor="massa-whatsapp-phone" className="block text-xs font-semibold uppercase text-gray-600 mb-1">
                {t('massas.phoneLabel')}
              </label>
              <Input
                id="massa-whatsapp-phone"
                type="tel"
                inputMode="tel"
                placeholder={t('massas.phonePlaceholder')}
                value={phone}
                onChange={(e) => onPhoneChange(formatPhoneMask(e.target.value))}
                className="font-medium text-base"
              />
              <span className="text-[11px] text-gray-500 mt-1 block">
                Ex: (11) 98765-4321 · O código +55 será adicionado automaticamente.
              </span>
            </div>

            <div className="flex flex-col sm:flex-row gap-3 pt-2">
              <Button
                type="button"
                onClick={onOpenWhatsApp}
                className="flex-1 bg-green-600 hover:bg-green-700 text-white font-semibold"
              >
                <Send className="h-4 w-4 mr-2" />
                {t('massas.openWhatsApp')}
              </Button>
              <Button
                type="button"
                variant="outline"
                onClick={onCopy}
                className="flex-1 font-semibold"
              >
                <Copy className="h-4 w-4 mr-2" />
                {t('massas.copyReport')}
              </Button>
            </div>

            {copyNote && (
              <div role="status" className="p-2.5 bg-green-50 text-green-800 text-xs rounded border border-green-200">
                {copyNote}
              </div>
            )}
          </div>

          {/* Live Message Preview */}
          <div className="space-y-1">
            <span className="text-xs font-semibold uppercase text-gray-500 block">
              {t('massas.previewTitle')}
            </span>
            <div className="bg-gray-50 p-4 rounded-lg border border-gray-200 text-xs sm:text-sm font-mono whitespace-pre-wrap leading-relaxed text-gray-800 max-h-64 overflow-y-auto">
              {previewMessage}
            </div>
          </div>
        </div>
      </CardContent>
    </Card>
  );
}
