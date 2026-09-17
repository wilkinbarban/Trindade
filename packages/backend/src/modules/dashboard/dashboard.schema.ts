import { z } from 'zod';

/**
 * The operational dashboard summary.
 *
 * Every count is computed server-side, and the two date-based ones use the São Paulo day rather
 * than the device's: `reportsToday` counts today's reports, `schedulesTomorrow` tomorrow's loading
 * entries. `latestReportId` is the newest active report for today, or null when there is none.
 *
 * `higieneDone`/`higieneTotal` and `recepcionDone`/`recepcionTotal` are progress pairs for two
 * fixed categories, so a client can render a fraction without knowing which category is which.
 */
export const DashboardSummarySchema = z
  .object({
    reportsToday: z.number().int(),
    schedulesTomorrow: z.number().int(),
    activeUsers: z.number().int(),
    reportsTotal: z.number().int(),
    schedulesTotal: z.number().int(),
    latestReportId: z.number().int().nullable(),
    higieneDone: z.number().int(),
    higieneTotal: z.number().int(),
    recepcionDone: z.number().int(),
    recepcionTotal: z.number().int(),
  })
  .strict();

export type DashboardSummary = z.infer<typeof DashboardSummarySchema>;
