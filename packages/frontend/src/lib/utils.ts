import { clsx, type ClassValue } from 'clsx';
import { twMerge } from 'tailwind-merge';

/**
 * Merges Tailwind CSS class names, resolving conflicts via tailwind-merge.
 * Standard shadcn/ui utility pattern.
 */
export function cn(...inputs: ClassValue[]): string {
  return twMerge(clsx(inputs));
}
