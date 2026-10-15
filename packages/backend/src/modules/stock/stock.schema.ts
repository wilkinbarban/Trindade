import { z } from 'zod';

/**
 * The shapes the counting endpoints answer with and accept, which is what the published contract
 * describes. Kept next to the module rather than in the document, because a document that describes a
 * shape nobody declared is a document that drifts.
 */
export const StockProductSchema = z
  .object({
    code: z.string(),
    name: z.string(),
    category: z.string(),
    unitsPerBox: z.number().int(),
    boxes: z.number().int(),
    units: z.number().int(),
  })
  .strict();

export const StockDaySchema = z
  .object({
    date: z.string(),
    products: z.array(StockProductSchema),
    totalBoxes: z.number().int(),
    totalUnits: z.number().int(),
  })
  .strict();

export const StockHistoryDaySchema = z
  .object({
    date: z.string(),
    products: z.number().int(),
    boxes: z.number().int(),
    units: z.number().int(),
  })
  .strict();

export const StockHistorySchema = z.object({ days: z.array(StockHistoryDaySchema) }).strict();

export const StockAddEntryBodySchema = z
  .object({
    date: z.string(),
    product_code: z.string(),
    boxes_added: z.number(),
  })
  .strict();

export const StockResetBodySchema = z
  .object({
    date: z.string(),
    product_code: z.string(),
  })
  .strict();
