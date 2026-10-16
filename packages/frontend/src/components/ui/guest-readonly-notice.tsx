import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Dialog } from './dialog';
import { consumeGuestReadonlyNotice, GUEST_READONLY_NOTICE_EVENT } from '../../api/client';

/**
 * El aviso del invitado, en un solo lugar.
 *
 * El invitado puede intentar una mutación en cualquier pantalla, así que el aviso no puede vivir en una
 * página: el cliente lo marca y este host lo muestra, montado una vez junto a las rutas.
 *
 * Se atienden los dos momentos por la misma razón que el aviso de sesión vencida usa sessionStorage: el
 * evento cubre el caso normal -- el rechazo llega con la aplicación ya montada -- y el consumo al
 * montar cubre el otro, cuando el rechazo ocurrió antes de que este componente existiera. Sin ese
 * segundo camino, una mutación fallida durante el arranque se perdería en silencio.
 */
export function GuestReadonlyNotice() {
  const { t } = useTranslation();
  const [message, setMessage] = useState<string | null>(null);

  useEffect(() => {
    const show = () => setMessage(consumeGuestReadonlyNotice());
    show();
    window.addEventListener(GUEST_READONLY_NOTICE_EVENT, show);
    return () => window.removeEventListener(GUEST_READONLY_NOTICE_EVENT, show);
  }, []);

  if (!message) return null;

  return (
    <Dialog open onClose={() => setMessage(null)} labelledBy="guest-readonly-title" panelTestId="guest-readonly-notice">
      <h2 id="guest-readonly-title" className="text-lg font-semibold text-gray-800">
        {t('guest.readonlyTitle')}
      </h2>
      {/* El texto es el del servidor, sin traducir: es el literal que la directiva fija y una sola
          cadena para los dos lados; si el backend cambia, guest-readonly.test.ts lo dice. */}
      <p className="mt-2 text-sm text-gray-600">{message}</p>
      <div className="mt-4 flex justify-end">
        <button
          type="button"
          onClick={() => setMessage(null)}
          className="rounded-md bg-gray-800 px-4 py-2 text-sm font-medium text-white hover:bg-gray-700"
        >
          OK
        </button>
      </div>
    </Dialog>
  );
}
