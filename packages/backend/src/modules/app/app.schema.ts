import { z } from 'zod';

export const LatestReleaseResponseSchema = z.object({
  version: z.string(),
  apkUrl: z.string(),
  apkName: z.string(),
  apkSize: z.number().nullable(),
  publishedAt: z.string().nullable(),
  webUrl: z.string(),
});

export type LatestReleaseResponse = z.infer<typeof LatestReleaseResponseSchema>;
