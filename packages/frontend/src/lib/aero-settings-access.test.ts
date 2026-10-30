import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { DEFAULT_AERO_SETTINGS, aeroSettingsEditable } from './aero-integration';

/**
 * Who may touch what in the company integration.
 *
 * The address is the plant's, not the operator's: one wrong host sends every launch of the shift to nowhere,
 * so it is fixed and only the account that owns the integration may change it. The user and the password are
 * the opposite -- they belong to the person holding the phone, and nobody else can type them for him.
 */
describe('aeroSettingsEditable', () => {
  it('locks the address for everyone except the account that owns the integration', () => {
    assert.equal(aeroSettingsEditable({ username: 'wilkin', role: 'Administrador' }).wsUrl, true);
    assert.equal(aeroSettingsEditable({ username: 'wilkin', role: 'Trabalhador' }).wsUrl, true);
    assert.equal(aeroSettingsEditable({ username: 'alice', role: 'Administrador' }).wsUrl, false);
    assert.equal(aeroSettingsEditable({ username: 'bruno', role: 'Trabalhador' }).wsUrl, false);
    assert.equal(aeroSettingsEditable(null).wsUrl, false);
  });

  it('lets any operator keep his own credentials', () => {
    assert.equal(aeroSettingsEditable({ username: 'alice', role: 'Trabalhador' }).credentials, true);
    assert.equal(aeroSettingsEditable({ username: 'alice', role: 'Administrador' }).credentials, true);
    assert.equal(aeroSettingsEditable(null).credentials, false);
  });

  it('ships the plant address already written, so a fresh phone only asks who is launching', () => {
    assert.equal(DEFAULT_AERO_SETTINGS.wsUrl, 'http://192.168.25.250:1453/api');
  });
});
