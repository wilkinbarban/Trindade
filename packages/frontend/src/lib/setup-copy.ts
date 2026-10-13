/**
 * Every string the setup flow shows, named once.
 *
 * The setup screen and its gate used to carry their Portuguese text inline, so the text was
 * invisible to the translation catalogue and could not be reviewed as copy. Naming the keys here
 * lets a test prove three things at once: both catalogues have the key, the text is intentional
 * rather than empty, and the Portuguese values still match the literals the end-to-end spec looks
 * for — both the heading and the button names — so refactoring the screen cannot silently change
 * what the operator reads and the spec asserts.
 */
export const SETUP_COPY_KEYS = [
  'setup.title',
  'setup.description',
  'setup.stillRequired',
  'setup.verifyFailed',
  'setup.verifying',
  'setup.verify',
  'setup.gateError',
  'setup.retry',
] as const;

/** Portuguese strings the existing end-to-end spec matches by accessible name or text. */
export const SETUP_SPEC_LITERALS = [
  'Configuração inicial',
  'Verificar configuração',
  'Tentar novamente',
  'Não foi possível verificar a configuração inicial.',
] as const;

/**
 * Keys whose Portuguese and Spanish text is legitimately identical.
 *
 * The progressive form "Verificando…" is spelled the same in both languages, so an equality
 * check there would report a translation gap that does not exist. Listing it here keeps the
 * "actually translated" assertion strict for every other key instead of loosening it for all.
 */
export const SETUP_SHARED_SPELLING_KEYS = ['setup.verifying'] as const;
