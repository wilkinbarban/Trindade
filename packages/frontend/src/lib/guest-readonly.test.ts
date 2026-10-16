import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { GUEST_READONLY_MESSAGE, isGuestMutationRefusal } from './guest-readonly.js';

/**
 * El invitado es de solo lectura y el backend lo rechaza con 403 y un mensaje fijo. La web tiene que
 * reconocer ESE rechazo -- y solo ese -- para mostrar el aviso en vez del error genérico de siempre.
 *
 * La última prueba lee la constante del backend y la compara con la de acá: si las dos cadenas se
 * separan, el cliente dejaría de reconocer el rechazo que el servidor manda, y nadie se enteraría
 * hasta que un operador viera un error críptico. Es la misma idea que api-messages.test.ts aplica
 * dentro del cliente, extendida al borde entre los dos paquetes.
 */

test('reconoce el rechazo del invitado: 403 con el mensaje de la directiva', () => {
  assert.equal(isGuestMutationRefusal(403, GUEST_READONLY_MESSAGE), true);
});

test('no confunde otros errores con el rechazo del invitado', () => {
  assert.equal(isGuestMutationRefusal(401, GUEST_READONLY_MESSAGE), false, 'un 401 es sesión, no invitado');
  assert.equal(isGuestMutationRefusal(403, 'Sem permissão'), false, 'un 403 ajeno no es el del invitado');
  assert.equal(isGuestMutationRefusal(200, GUEST_READONLY_MESSAGE), false, 'un 200 no es un rechazo');
  assert.equal(isGuestMutationRefusal(403, ''), false, 'sin mensaje no hay nada que mostrar');
});

test('la cadena del cliente y la del backend son la misma', () => {
  const backendSource = readFileSync(
    join(import.meta.dirname, '../../../backend/src/guest-user.ts'),
    'utf8'
  );
  // El literal del backend, tal como está escrito en su fuente: 'É necessário ... ações'.
  const match = backendSource.match(/export const GUEST_MUTATION_MESSAGE\s*=\s*'([^']+)'/);
  assert.ok(match, 'no encontré GUEST_MUTATION_MESSAGE en el backend');
  const backendMessage = match[1].replace(/\\u([0-9a-fA-F]{4})/g, (_, hex) =>
    String.fromCharCode(parseInt(hex, 16))
  );
  assert.equal(
    GUEST_READONLY_MESSAGE,
    backendMessage,
    'el mensaje del cliente y el del servidor tienen que ser la misma cadena'
  );
});
