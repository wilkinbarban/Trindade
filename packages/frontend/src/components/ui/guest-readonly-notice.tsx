import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Dialog } from './dialog';
import { Button } from './button';
import { appUrl, consumeGuestReadonlyNotice, GUEST_READONLY_NOTICE_EVENT } from '../../api/client';

/**
 * El candado del badge, en línea.
 *
 * El proyecto no tiene librería de íconos y sumar una entera por un candado sería pagar para siempre por
 * un adorno; el SVG pesa menos que la dependencia y hereda el color del texto del contenedor.
 */
function LockIcon() {
  return (
    <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <rect x="4" y="10" width="16" height="10" rx="2" />
      <path d="M8 10V7a4 4 0 0 1 8 0v3" />
    </svg>
  );
}

/**
 * El aviso del invitado, en un solo lugar.
 *
 * El invitado puede intentar una mutación en cualquier pantalla, así que el aviso no puede vivir en una
 * página: el cliente lo marca y este host lo muestra, montado una vez junto a las rutas.
 *
 * Se atienden los dos momentos por la misma razón que el aviso de sesión vencida usa sessionStorage: el
 * evento cubre el caso normal -- el rechazo llega con la aplicación ya montada -- y el consumo al montar
 * cubre el otro, cuando el rechazo ocurrió antes de que este componente existiera. Sin ese segundo
 * camino, una mutación fallida durante el arranque se perdería en silencio.
 *
 * El texto del cuerpo es el del servidor, sin traducir: es el literal que la directiva fija y una sola
 * cadena para los dos lados; si el backend cambia, guest-readonly.test.ts lo dice. Lo que sí se traduce
 * es lo que decide la interfaz -- el título y los dos botones -- porque eso es de acá.
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

  // El aviso se limpia ANTES de navegar: si quedara marcado, reaparecería apenas se llegara al login.
  const goToLogin = () => {
    setMessage(null);
    window.location.href = appUrl('/login');
  };

  return (
    <Dialog
      open
      onClose={() => setMessage(null)}
      labelledBy="guest-readonly-title"
      describedBy="guest-readonly-message"
      panelTestId="guest-readonly-notice"
    >
      <div className="flex flex-col items-center text-center">
        <span className="flex h-12 w-12 items-center justify-center rounded-full bg-amber-100 text-amber-600">
          <LockIcon />
        </span>

        <h2 id="guest-readonly-title" className="mt-4 text-lg font-semibold tracking-tight text-gray-900">
          {t('guest.readonlyTitle')}
        </h2>

        <p id="guest-readonly-message" className="mt-3 w-full rounded-lg bg-gray-50 px-4 py-3 text-sm leading-relaxed text-gray-600">
          {message}
        </p>
      </div>

      <div className="mt-6 flex flex-col-reverse gap-2 sm:flex-row sm:justify-center">
        <Button variant="outline" size="sm" onClick={goToLogin}>
          {t('guest.readonlyGoToLogin')}
        </Button>
        <Button size="sm" onClick={() => setMessage(null)}>
          {t('guest.readonlyDismiss')}
        </Button>
      </div>
    </Dialog>
  );
}
