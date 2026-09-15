import html2canvas from 'html2canvas';
import { jsPDF } from 'jspdf';

/**
 * Captures a DOM element and downloads it as a PDF file.
 *
 * Uses html2canvas to render the element to a canvas image,
 * then embeds it into a jsPDF document sized to fit.
 *
 * @param elementId - The `id` attribute of the DOM element to capture.
 * @param filename  - The downloaded file name (`.pdf` appended automatically).
 */
export async function exportPdf(elementId: string, filename: string): Promise<void> {
  const element = document.getElementById(elementId);

  if (!element) {
    throw new Error(`Element with id "${elementId}" not found`);
  }

  // Render the DOM node to a canvas
  const canvas = await html2canvas(element, {
    scale: 2, // retina-friendly
    useCORS: true,
    logging: false,
  });

  const imgData = canvas.toDataURL('image/png');

  // Calculate PDF dimensions to fit the canvas
  const imgWidth = canvas.width;
  const imgHeight = canvas.height;

  // A4 portrait in pt at 72 DPI
  const pdfWidth = 210; // mm
  const pdfHeight = (imgHeight * pdfWidth) / imgWidth;

  const orientation = pdfWidth > pdfHeight ? 'landscape' : 'portrait';
  const doc = new jsPDF({
    orientation,
    unit: 'mm',
    format: [pdfWidth, pdfHeight],
  });

  doc.addImage(imgData, 'PNG', 0, 0, pdfWidth, pdfHeight);

  doc.save(`${filename}.pdf`);
}
