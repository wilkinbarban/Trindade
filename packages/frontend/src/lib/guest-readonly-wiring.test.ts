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
