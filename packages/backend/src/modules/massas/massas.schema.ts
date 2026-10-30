import { z } from 'zod';

/**
 * Zod schemas for wire contracts of the Massas Launch endpoints.
 */
export const MassaProductTotalSchema = z
  .object({
    code: z.string(),
    name: z.string(),
    unitsPerBox: z.number().int(),
    totalBoxes: z.number().int(),
    totalUnits: z.number().int(),
  })
  .strict();

export const MassaIndividualLaunchSchema = z
  .object({
    id: z.number().int(),
    launch_date: z.string(),
    product_code: z.string(),
    product_name: z.string(),
    boxes: z.number().int(),
    units: z.number().int(),
    user_id: z.number().int(),
    username: z.string(),
    display_name: z.string(),
    /** The ERP seat this launch was imported from; null for a launch typed here. */
    ent_lan: z.number().int().nullable(),
    created_at: z.string(),
    updated_at: z.string(),
  })
  .strict();

export const MassasLaunchDayViewSchema = z
  .object({
    date: z.string(),
    products: z.array(MassaProductTotalSchema),
    launches: z.array(MassaIndividualLaunchSchema),
    totalBoxes: z.number().int(),
    totalUnits: z.number().int(),
  })
  .strict();

export const MassasLaunchCreateItemSchema = z
  .object({
    product_code: z.string().min(1),
    boxes: z.number().int().min(1),
    /** The ERP seat this line came from, when the launch is an import rather than a typed one. */
    ent_lan: z.number().int().positive().optional(),
    operator_name: z.string().min(1).optional(),
  })
  .strict();

export const MassasLaunchCreateBodySchema = z
  .object({
    date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/, 'date must be YYYY-MM-DD').optional(),
    product_code: z.string().min(1).optional(),
    boxes: z.number().int().min(1).optional(),
    items: z.array(MassasLaunchCreateItemSchema).optional(),
    /**
     * The seat and the operator the tablet wrote, for the import path. Accepted at the top level because
     * the screen imports one seat at a time; a request with several items must carry them per item, since
     * one seat is one product line.
     */
    ent_lan: z.number().int().positive().optional(),
    operator_name: z.string().min(1).optional(),
  })
  .strict();

export const MassasLaunchUpdateBodySchema = z
  .object({
    boxes: z.number().int().min(1),
  })
  .strict();

export const MassasLaunchCreateResponseSchema = z
  .object({
    created: z.array(MassaIndividualLaunchSchema),
  })
  .strict();

export const MassasLaunchDeleteResponseSchema = z
  .object({
    ok: z.literal(true),
  })
  .strict();

export const MassaHistoryDaySchema = z
  .object({
    date: z.string(),
    productsCount: z.number().int(),
    totalBoxes: z.number().int(),
    totalUnits: z.number().int(),
    operatorsCount: z.number().int(),
  })
  .strict();

export const MassasHistoryResponseSchema = z
  .object({
    days: z.array(MassaHistoryDaySchema),
  })
  .strict();

export const MassasErrorResponseSchema = z
  .object({
    error: z.string(),
    code: z.string(),
    details: z.unknown().optional(),
  })
  .strict();



/**
 * The reconciled day: what the plant really made, from both screens that record it.
 *
 * It is the shape the integral report and the unified table read, so the contract describes it instead of
 * letting the route answer a body nobody published.
 */
/**
 * The balance as both clients read it: the arithmetic and the seat counts together.
 *
 * `reconciledSeats` and `pendingSeats` are counts, not lists; the seats themselves travel per operator.
 */
export const MassasReportBalanceSchema = z.object({
  producedBoxes: z.number(),
  producedUnits: z.number(),
  registeredBoxes: z.number(),
  registeredUnits: z.number(),
  differenceUnits: z.number(),
  reconciledSeats: z.number(),
  pendingSeats: z.number(),
});

/** One product's share inside an operator's day. */
export const MassasReportOperatorProductSchema = z.object({
  code: z.string(),
  name: z.string(),
  boxes: z.number(),
  units: z.number(),
});

/**
 * One operator, with the two names each client already uses.
 *
 * `name`/`erpOnly` are the web's reading and `displayName`/`onlyErp` the generated Android contract's; the
 * answer carries both so neither client can be left with a day it cannot deserialize.
 */
export const MassasReportOperatorSchema = z.object({
  operatorKey: z.string(),
  name: z.string(),
  displayName: z.string(),
  username: z.string().nullable(),
  boxes: z.number(),
  units: z.number(),
  products: z.array(MassasReportOperatorProductSchema),
  erpOnly: z.boolean(),
  onlyErp: z.boolean(),
  entLans: z.array(z.number()),
});

export const MassasReportDayViewSchema = z.object({
  date: z.string(),
  operators: z.array(MassasReportOperatorSchema),
  products: z.array(MassaProductTotalSchema),
  totalBoxes: z.number(),
  totalUnits: z.number(),
  balance: MassasReportBalanceSchema,
  reconciledEntLans: z.array(z.number()),
});
