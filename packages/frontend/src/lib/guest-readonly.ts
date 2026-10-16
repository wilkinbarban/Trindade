/**
 * El invitado es la cuenta de solo lectura: mira todo y no toca nada. El backend rechaza cualquiera de
 * sus mutaciones con 403 y un mensaje fijo, y la web tiene que reconocer ESE rechazo -- y solo ese --
 * para mostrar el aviso informativo en vez del error genérico de siempre.
 *
 * El mensaje es el de la directiva y su única fuente de verdad es el backend; acá vive una copia
 * porque el cliente no puede importar del servidor. Que las dos no se separen es lo que cuida
 * guest-readonly.test.ts, que lee la constante del backend y la compara con esta.
 */

export const GUEST_READONLY_MESSAGE =
  '\u00c9 necess\u00e1rio cadastrar um usu\u00e1rio na p\u00e1gina de login para realizar a\u00e7\u00f5es';

/**
 * Reconoce el rechazo del invitado.
 *
 * Pide las dos cosas a la vez a propósito: el 403 solo no alcanza -- un permiso denegado cualquiera
 * también lo es, y tratarlo como "invitado" le mentiría al operador -- y el mensaje solo tampoco,
 * porque un error de otra capa podría repetir el texto.
 */
export function isGuestMutationRefusal(status: number, message: string): boolean {
  return status === 403 && message === GUEST_READONLY_MESSAGE;
}
