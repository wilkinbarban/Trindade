/**
 * Downloads plain text content as a UTF-8 BOM encoded `.txt` file.
 *
 * UTF-8 BOM (Byte Order Mark, `\xEF\xBB\xBF`) ensures applications like
 * Windows Notepad correctly detect the encoding and display Portuguese
 * characters (ç, ã, õ, etc.) without corruption.
 *
 * @param text     - The plain text content in pt-BR.
 * @param filename - The downloaded file name (`.txt` appended automatically).
 */
export function exportTxt(text: string, filename: string): void {
  // UTF-8 BOM bytes (EF BB BF)
  const bom = new Uint8Array([0xef, 0xbb, 0xbf]);

  // Encode the text as UTF-8
  const encoder = new TextEncoder();
  const textBytes = encoder.encode(text);

  // Combine BOM + text bytes
  const blob = new Blob([bom, textBytes], {
    type: 'text/plain;charset=utf-8',
  });

  // Trigger download
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = `${filename}.txt`;
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  URL.revokeObjectURL(url);
}
