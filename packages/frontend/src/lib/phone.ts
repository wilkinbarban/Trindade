/**
 * The driver's phone number, as the fleet keeps it.
 *
 * The value is stored the way the operator typed it -- the driver list shows it back to them, and a
 * number that came back reformatted reads as the app having corrected them -- so cleaning only removes
 * what cannot be part of a number: letters, emoji, and any other punctuation, each of which becomes a
 * space, with runs of spaces collapsed and the ends trimmed. A number the operator wrote inside a note
 * keeps its digits: nothing here guesses which digit was meant as an extension, and the field is theirs
 * to keep clean.
 *
 * The number is optional everywhere it appears. An operator who does not have it yet leaves the field
 * empty, the driver is saved the same way, and the actions that need a number simply do not appear.
 */
export function filterPhoneInput(raw: string): string {
  return raw.replace(/[^\d+().\-\s]/g, '');
}

/**
 * The number as it is stored: the same rule applied to a field the operator has finished with, tidied.
 *
 * Everything [filterPhoneInput] keeps is kept, and on top of that runs of spaces collapse and the ends
 * trim, so a pasted number is saved without the padding around it.
 */
export function cleanPhone(raw: string): string {
  return filterPhoneInput(raw).replace(/\s+/g, ' ').trim();
}
