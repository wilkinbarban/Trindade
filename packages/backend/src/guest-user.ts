/**
 * El invitado: la cuenta de solo lectura que la directiva pide.
 *
 * El nombre y el mensaje viven aca y en un solo lugar, porque varios puntos del backend deciden con
 * ellos -- la semilla, la guarda de mutaciones, la invisibilidad en los listados y las compuertas de
 * primer usuario -- y una cadena repetida en los sitios que deciden seguridad es una que puede
 * driftear. La prueba de la guarda conserva su propio literal a proposito: si importara esta
 * constante, pasaria aunque la constante cambiara, y lo que hay que fijar es el texto de la directiva.
 */

export const GUEST_USERNAME = 'invitado';
export const GUEST_DISPLAY_NAME = 'Invitado';
export const GUEST_MUTATION_MESSAGE =
  '\u00c9 necess\u00e1rio cadastrar um usu\u00e1rio na p\u00e1gina de login para realizar a\u00e7\u00f5es';

/**
 * El invitado tampoco puede ser objeto de cambios: ni el Administrador puede desactivarlo,
 * editarlo ni borrarlo. Si se lo pudiera borrar, la cuenta de solo lectura que la directiva pide
 * desapareceria, y si se lo pudiera desactivar, dejaria de poder entrar.
 */
export const GUEST_IMMUTABLE_MESSAGE =
  'O usu\u00e1rio convidado \u00e9 de somente leitura e n\u00e3o pode ser alterado nem exclu\u00eddo.';
