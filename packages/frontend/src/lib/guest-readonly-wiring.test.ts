import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

/**
 * El rechazo del invitado llega como 403 desde el backend y tiene que producir un aviso que el
 * operador vea, sin importar en qué pantalla intentó la mutación.
 *
 * Esta prueba mira el CÓDIGO FUENTE del cliente y no su comportamiento, y eso es deliberado: importar
 * el cliente en node arrastraría import.meta.env, que es exactamente la razón por la que el proyecto ya
 * tiene pruebas como api-messages.test.ts. La lógica que se puede probar de verdad -- reconocer el
 * rechazo -- vive en guest-readonly.ts, con su propia prueba importable.
 *
 * Lo que se verifica acá es el cableado: que el cliente reconozca el rechazo, marque el aviso con el
 * mismo idiom que ya usa para la sesión vencida, y que exista quién lo consuma.
 */

const clientPath = join(import.meta.dirname, '../api/client.ts');
const clientSource = readFileSync(clientPath, 'utf8');

test('el cliente reconoce el rechazo del invitado con la función pura', () => {
  assert.match(
    clientSource,
    /import\s*\{[^}]*isGuestMutationRefusal[^}]*\}\s*from\s*'\.\.\/lib\/guest-readonly'/,
    'el cliente tiene que importar el reconocedor del rechazo del invitado'
  );
  assert.match(
    clientSource,
    /isGuestMutationRefusal\s*\(\s*response\.status/,
    'tiene que preguntarlo con el estado de la respuesta'
  );
});

test('marca el aviso y lo deja consumible, igual que la sesión vencida', () => {
  assert.match(clientSource, /export function markGuestReadonlyNotice\s*\(/, 'falta marcar el aviso');
  assert.match(clientSource, /export function consumeGuestReadonlyNotice\s*\(/, 'falta poder consumirlo');
  assert.match(
    clientSource,
    /markGuestReadonlyNotice\s*\(\s*\)/,
    'el rechazo reconocido tiene que marcar el aviso'
  );
  assert.match(
    clientSource,
    /sessionStorage/,
    'el aviso tiene que sobrevivir a la navegación, como el de sesión vencida'
  );
});

test('existe el host que lo muestra, usa el modal del proyecto y está montado', () => {
  const hostPath = join(import.meta.dirname, '../components/ui/guest-readonly-notice.tsx');
  const host = readFileSync(hostPath, 'utf8');
  assert.match(host, /from '\.\/dialog'/, 'el aviso tiene que usar el modal accesible que ya existe');
  assert.match(host, /consumeGuestReadonlyNotice/, 'tiene que consumir el aviso, no inventarlo');
  assert.match(host, /GUEST_READONLY_NOTICE_EVENT/, 'tiene que reaccionar al rechazo que llega en vivo');

  const appSource = readFileSync(join(import.meta.dirname, '../App.tsx'), 'utf8');
  assert.match(appSource, /<GuestReadonlyNotice\s*\/>/, 'tiene que estar montado, o nadie lo vería');
});

// Los tres casos del rediseño, con includes y no con expresiones regulares: lo que se verifica es que
// ciertas cadenas estén en la fuente, y para eso una comparación de texto alcanza y no se escapa.

const HOST = join(import.meta.dirname, '../components/ui/guest-readonly-notice.tsx');

test('el aviso se ve como el sistema: badge con ícono, mensaje en su contenedor y botones del sistema', () => {
  const host = readFileSync(HOST, 'utf8');

  // El ícono es un SVG en línea: el proyecto no tiene librería de íconos, y sumar una dependencia por
  // un candado sería pagar para siempre por un adorno.
  assert.ok(host.includes('<svg'), 'el badge necesita su ícono');
  assert.ok(host.includes('rounded-full'), 'el ícono vive en un badge circular');
  assert.ok(host.includes('amber-'), 'y en el tono cálido que la directiva pide');
  assert.ok(host.includes('bg-gray-50'), 'el mensaje del servidor necesita su contenedor suave');

  // Los botones son los del sistema, no botones sueltos con clases a mano.
  assert.ok(host.includes("from './button'"), 'los botones tienen que ser los del sistema');
  assert.ok(host.includes('guest.readonlyDismiss'), 'el botón principal dice Entendi, traducido');
  assert.ok(host.includes('guest.readonlyGoToLogin'), 'y el secundario ofrece ir al login, traducido');
});

test('el secundario lleva al login por el mismo camino que ya usa el cliente', () => {
  const host = readFileSync(HOST, 'utf8');

  // No una ruta escrita a mano: el helper con el que el cliente resuelve la base de la app.
  assert.ok(host.includes('appUrl('), 'tiene que resolver la ruta como el resto del cliente');
  assert.ok(host.includes("'/login'"), 'y llevar al login');
  assert.ok(host.includes('setMessage(null)'), 'y limpiar el aviso para que no reaparezca al llegar');
  // Irse al login sin cerrar la sesión deja el token vivo: el botón promete cerrar, no sólo ir.
  assert.ok(host.includes('logout()'), 'y cerrar la sesión antes de navegar');
  assert.ok(host.includes('useAuth'), 'con el logout del contexto de autenticación');
});

test('el contrato del evento sigue intacto: es lo que la directiva manda preservar', () => {
  const host = readFileSync(HOST, 'utf8');
  assert.ok(host.includes('GUEST_READONLY_NOTICE_EVENT'), 'el aviso en vivo tiene que seguir escuchándose');
  assert.ok(host.includes('consumeGuestReadonlyNotice'), 'y el que esperó al montaje, consumirse');
  assert.ok(host.includes("from './dialog'"), 'y seguir usando el modal accesible del proyecto');

  const appSource = readFileSync(join(import.meta.dirname, '../App.tsx'), 'utf8');
  assert.ok(appSource.includes('<GuestReadonlyNotice'), 'el host tiene que seguir montado');
});
