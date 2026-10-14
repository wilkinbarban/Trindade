import { z } from 'zod';

/**
 * The operational dashboard summary.
 *
 * Every count is computed server-side, and the date-based ones use the São Paulo day rather than the
 * device's: `reportsToday` counts today's reports, `schedulesTomorrow` tomorrow's loading entries.
 *
 * `activeScheduleDate` and `activeScheduleCount` are the operational window's answer -- which loading day
 * an operator is working on right now and how many entries it has -- which is today's schedule until ten
 * in the morning and the next loading day from then on. A batch is stored under the day it is prepared and
 * loaded on the following loading day, so the count is of the batch's entries and
 * `activeScheduleBatchDate` names that batch: it is the date to hand to the loading export to get the text
 * for the day the card is showing, and null on a day no batch announces (a Saturday).
 *
 * `schedulesTomorrow` is the older field and counts what it always counted: the entries stored for
 * tomorrow's date, which under the convention above is tomorrow's batch, loaded the day after.
 * `latestReportId` is the newest active report for today, or null when there is none.
 *
 * `higieneDone`/`higieneTotal` and `recepcionDone`/`recepcionTotal` are progress pairs for two
 * fixed categories, so a client can render a fraction without knowing which category is which.
 */
export const DashboardSummarySchema = z
  .object({
    reportsToday: z.number().int(),
    schedulesTomorrow: z.number().int(),
    activeScheduleDate: z.string(),
    activeScheduleBatchDate: z.string().nullable(),
    activeScheduleCount: z.number().int(),
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
