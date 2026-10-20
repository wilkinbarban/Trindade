import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { cleanPhone, filterPhoneInput, formatPhoneMask, toWhatsAppNumber } from './phone';

/**
 * The phone field's rules, kept pure so the form that uses them has no logic of its own.
 *
 * The number is stored as the operator typed it -- the report cards and the driver list show it back to
 * them, and a value that came back reformatted would read as the app having corrected them -- but the
 * characters that can only be punctuation are dropped before it is saved, so a paste that carries a
 * stray letter or a stray emoji does not become part of the number.
 */
describe('cleanPhone', () => {
  it('keeps the parts of a number an operator can write', () => {
    assert.equal(cleanPhone('+55 (11) 98888-7777'), '+55 (11) 98888-7777');
    assert.equal(cleanPhone('11 98888.7777'), '11 98888.7777');
  });

  it('trims what surrounds the number and collapses what is inside it', () => {
    assert.equal(cleanPhone('  11 98888-7777  '), '11 98888-7777');
    assert.equal(cleanPhone('11   98888-7777'), '11 98888-7777');
  });

  it('drops characters that cannot be part of a number', () => {
    assert.equal(cleanPhone('11 98888-7777 ramal 2'), '11 98888-7777 2');
    assert.equal(cleanPhone('📞 11 98888-7777'), '11 98888-7777');
    assert.equal(cleanPhone('abc'), '');
  });

  it('answers empty for empty input, so an optional field stays optional', () => {
    assert.equal(cleanPhone(''), '');
    assert.equal(cleanPhone('   '), '');
  });
});

/**
 * What the field keeps while the operator is still typing.
 *
 * The same rule as [cleanPhone] minus the tidying: a character that cannot belong to a number never
 * reaches the state, and the spacing is left exactly as typed, because collapsing or trimming on every
 * keystroke would eat the space an operator is in the middle of writing.
 */
describe('filterPhoneInput', () => {
  it('drops a character that cannot belong to a number', () => {
    assert.equal(filterPhoneInput('11abc98888'), '1198888');
    assert.equal(filterPhoneInput('📞11'), '11');
  });

  it('leaves the spacing alone, so a number can be typed with spaces', () => {
    assert.equal(filterPhoneInput('11 '), '11 ');
    assert.equal(filterPhoneInput('+55 (11) 98888-7777'), '+55 (11) 98888-7777');
  });
});

describe('formatPhoneMask', () => {
  it('formats 11 digits into (XX) 9XXXX-XXXX', () => {
    assert.equal(formatPhoneMask('11988887777'), '(11) 98888-7777');
  });

  it('formats partial input progressively', () => {
    assert.equal(formatPhoneMask('1'), '(1');
    assert.equal(formatPhoneMask('11'), '(11) ');
    assert.equal(formatPhoneMask('119'), '(11) 9');
    assert.equal(formatPhoneMask('119888'), '(11) 9888');
    assert.equal(formatPhoneMask('1198888'), '(11) 98888');
    assert.equal(formatPhoneMask('11988887777'), '(11) 98888-7777');
  });

  it('strips country code 55 when followed by full number', () => {
    assert.equal(formatPhoneMask('+55 (11) 98888-7777'), '(11) 98888-7777');
  });
});

describe('toWhatsAppNumber', () => {
  it('prepends 55 to an 11-digit Brazilian number if missing', () => {
    assert.equal(toWhatsAppNumber('(11) 98888-7777'), '5511988887777');
  });

  it('prepends 55 to a 10-digit Brazilian landline number if missing', () => {
    assert.equal(toWhatsAppNumber('(11) 3333-4444'), '551133334444');
  });

  it('keeps 55 if already present', () => {
    assert.equal(toWhatsAppNumber('+55 11 98888-7777'), '5511988887777');
  });

  it('returns empty string if no digits are provided', () => {
    assert.equal(toWhatsAppNumber(''), '');
  });
});
