import { createContext, useContext, useMemo, type ReactNode } from 'react';
import { currentDevice, type Device } from '../lib/device-detection';

/**
 * The device, read once per mount and shared.
 *
 * Every screen that touches the Aero32 integration needs the same answer, and asking `navigator` in three
 * places is three chances to disagree -- which is exactly the bug where the Admin panel is read-only and the
 * launch screen is not. The user agent cannot change while the tab is open, so one read is enough.
 *
 * The default is the real device, so a component used outside the provider still gets a truthful answer
 * rather than a hardcoded "supported".
 */
const DeviceContext = createContext<Device | null>(null);

export function DeviceProvider({
  children,
  device,
}: {
  children: ReactNode;
  /** Injected by tests and by the gate; production reads the browser once. */
  device?: Device;
}) {
  const value = useMemo(() => device ?? currentDevice(), [device]);
  return <DeviceContext.Provider value={value}>{children}</DeviceContext.Provider>;
}

export function useDevice(): Device {
  return useContext(DeviceContext) ?? currentDevice();
}
