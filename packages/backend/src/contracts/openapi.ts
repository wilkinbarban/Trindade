import { OpenAPIRegistry, OpenApiGeneratorV31, extendZodWithOpenApi } from '@asteasolutions/zod-to-openapi';
import type { ResponseConfig } from '@asteasolutions/zod-to-openapi';
import { z } from 'zod';

// `registry.register(refId, schema)` names a schema by calling `.openapi(refId)` on it, so
// Zod's prototype has to be extended before anything is registered. This patches the
// process that builds the contract — the generator and the contract test — and nothing
// else, because nothing else imports this module.
extendZodWithOpenApi(z);
import {
  AuthProfileSchema,
  AuthUserSchema,
  LoginResponseSchema,
  ProfileResponseSchema,
  RefreshResponseSchema,
  SetupResponseSchema,
  SetupStatusResponseSchema,
  changePasswordSchema,
  loginSchema,
  logoutSchema,
  refreshSessionSchema,
  setupSchema,
  updateProfileSchema,
} from '../modules/auth/auth.schema.js';
import {
  CreateDriverSchema,
  CreateScheduleSchema,
  DriverResponseSchema,
  DriverSchema,
  DriversResponseSchema,
  ExportQuerySchema,
  HistoryQuerySchema,
  LoadingBatchHistoryItemSchema,
  ScheduleHistoryResponseSchema,
  ScheduleQuerySchema,
  ScheduleResponseSchema,
  ScheduleSchema,
  SchedulesResponseSchema,
  TimeSlotsResponseSchema,
  UpdateScheduleSchema,
  VehicleSchema,
  VehiclesResponseSchema,
} from '../modules/loading/loading.schema.js';
import {
  CategoriesResponseSchema,
  CategoryResponseSchema,
  CreateReportBodySchema,
  HistoryQuerySchema as ReportHistoryQuerySchema,
  PhotoResponseSchema,
  PhotoSchema,
  PhotosResponseSchema,
  ReportDetailSchema,
  ReportHistoryResponseSchema,
  ReportItemDetailSchema,
  ReportListItemSchema,
  ReportQuerySchema,
  ReportResponseSchema,
  ReportTemperatureDetailSchema,
  ReportUserSchema,
  ReportsResponseSchema,
  TaskResponseSchema,
  TurnoResponseSchema,
  UpdateReportBodySchema,
} from '../modules/reports/reports.schema.js';
import { PaginationSchema, SuccessResponseSchema, TextResponseSchema } from './common.schema.js';
import { ErrorEnvelopeSchema } from './error.schema.js';

/**
 * The machine-readable contract for the API surface the mobile client consumes.
 *
 * This registry is declared by hand, so the risk is that a route exists and is never added here.
 * The generator is a build step and `scripts/verify-openapi-artifact.sh` fails when the committed
 * artifact is stale, which keeps the document truthful about what it covers. It does not keep the
 * document complete: nothing yet compares the routes actually registered against this document,
 * so a route added without a registry entry still escapes the contract silently.
 *
 * The surface is deliberately scoped to field operations. Admin and audit routes are
 * excluded because no mobile client calls them; they join the same registry when one does.
 */
const API_TITLE = 'Trindade Massas Operações API';
const API_VERSION = '0.1.0';
const JSON_MEDIA_TYPE = 'application/json';

function jsonResponse(description: string, schema: z.ZodTypeAny): ResponseConfig {
  return { description, content: { [JSON_MEDIA_TYPE]: { schema } } };
}

/** The envelope shared by every failure, so a client can parse one error shape. */
function errorResponse(description: string): ResponseConfig {
  return jsonResponse(description, ErrorEnvelopeSchema);
}

/**
 * A stored photo served as raw bytes rather than JSON. The three media types are the ones the
 * upload endpoint accepts, and the content type of any given response comes from the stored MIME
 * type rather than from a header this document can pin.
 */
function binaryResponse(description: string): ResponseConfig {
  // Annotated so the literals below stay 'string' and 'binary' instead of widening, which is what
  // the schema type requires.
  const content: NonNullable<ResponseConfig['content']> = {
    'image/jpeg': { schema: { type: 'string', format: 'binary' } },
    'image/png': { schema: { type: 'string', format: 'binary' } },
    'image/webp': { schema: { type: 'string', format: 'binary' } },
  };
  return { description, content };
}

const UNAUTHORIZED = errorResponse('Missing, invalid, or expired credentials');
const INVALID_INPUT = errorResponse('The request body failed validation');
const USER_NOT_FOUND = errorResponse('The authenticated user no longer exists');

// Path parameters. Declared as plain strings because that is what the router hands the
// handler; each handler decides for itself whether the value is usable. The pattern is not
// repeated here on purpose: the regex lives in the route, and duplicating it would create a
// second source of truth that can disagree with the code that actually rejects the request.
//
// Paths below use the OpenAPI `{param}` form. Fastify routes spell these `:param`, and the
// library does NOT translate between the two, so this is a deliberate conversion: `:id` is not
// a valid OpenAPI path template and a client generator would not recognise the parameter. The
// route-coverage test normalises `{param}` back to `:param` when comparing against the routes
// Fastify actually registered.
const BATCH_DATE_PARAMS = z.object({ date: z.string() });
const SCHEDULE_ID_PARAMS = z.object({ id: z.string() });
const REPORT_ID_PARAMS = z.object({ id: z.string() });
const PHOTO_ID_PARAMS = z.object({ photoId: z.string() });
const PHOTO_TOKEN_PARAMS = z.object({ token: z.string() });

function buildRegistry(): OpenAPIRegistry {
  const registry = new OpenAPIRegistry();

  // Registered as named components so a generated client gets named models instead of
  // anonymous inline objects repeated at every reference.
  registry.register('ErrorEnvelope', ErrorEnvelopeSchema);
  registry.register('AuthUser', AuthUserSchema);
  registry.register('AuthProfile', AuthProfileSchema);
  registry.register('LoginResponse', LoginResponseSchema);
  registry.register('RefreshResponse', RefreshResponseSchema);
  registry.register('ProfileResponse', ProfileResponseSchema);
  registry.register('SuccessResponse', SuccessResponseSchema);
  registry.register('SetupStatusResponse', SetupStatusResponseSchema);
  registry.register('SetupResponse', SetupResponseSchema);
  registry.register('TextResponse', TextResponseSchema);
  registry.register('Pagination', PaginationSchema);
  registry.register('Schedule', ScheduleSchema);
  registry.register('LoadingBatchHistoryItem', LoadingBatchHistoryItemSchema);
  registry.register('Driver', DriverSchema);
  registry.register('Vehicle', VehicleSchema);
  registry.register('ReportCategory', CategoryResponseSchema);
  registry.register('ReportTask', TaskResponseSchema);
  registry.register('ReportListItem', ReportListItemSchema);
  registry.register('ReportDetail', ReportDetailSchema);
  registry.register('ReportItemDetail', ReportItemDetailSchema);
  registry.register('ReportTemperatureDetail', ReportTemperatureDetailSchema);
  registry.register('ReportUser', ReportUserSchema);
  registry.register('ReportPhoto', PhotoSchema);

  registry.registerPath({
    method: 'post',
    path: '/api/auth/login',
    summary: 'Exchange credentials for an access token and a refresh session',
    tags: ['auth'],
    request: { body: { content: { [JSON_MEDIA_TYPE]: { schema: loginSchema } } } },
    responses: {
      200: jsonResponse('Authenticated. The refresh token is the session and is rotated on every refresh.', LoginResponseSchema),
      400: INVALID_INPUT,
      401: errorResponse('The credentials are wrong, or the account is inactive'),
    },
  });

  registry.registerPath({
    method: 'post',
    path: '/api/auth/refresh',
    summary: 'Rotate a refresh session into a new token pair',
    description:
      'Unauthenticated by design: a client refreshes precisely because its access token has expired. ' +
      'Every failure answers identically so a caller cannot probe whether a token existed, had expired, ' +
      'or had been revoked. Presenting a token that was already rotated away is treated as a leak and ' +
      'revokes the whole session family.',
    tags: ['auth'],
    request: { body: { content: { [JSON_MEDIA_TYPE]: { schema: refreshSessionSchema } } } },
    responses: {
      200: jsonResponse('A new token pair. The presented refresh token is no longer valid.', RefreshResponseSchema),
      400: INVALID_INPUT,
      401: errorResponse('The refresh token is unknown, expired, revoked, or its family was revoked'),
    },
  });

  registry.registerPath({
    method: 'post',
    path: '/api/auth/logout',
    summary: 'End the session this client is holding',
    description:
      'Ends only the session whose refresh token is presented, so logging out on one device leaves other ' +
      'devices signed in. The body is optional: the web client sends none.',
    tags: ['auth'],
    request: { body: { content: { [JSON_MEDIA_TYPE]: { schema: logoutSchema } }, required: false } },
    responses: {
      200: jsonResponse('The session was ended. Idempotent: an unknown or already-ended token still succeeds.', SuccessResponseSchema),
      400: INVALID_INPUT,
      401: UNAUTHORIZED,
    },
  });

  registry.registerPath({
    method: 'get',
    path: '/api/auth/me',
    summary: 'Read the authenticated user',
    tags: ['auth'],
    responses: {
      200: jsonResponse('The authenticated user', ProfileResponseSchema),
      401: UNAUTHORIZED,
      404: USER_NOT_FOUND,
    },
  });

  registry.registerPath({
    method: 'get',
    path: '/api/auth/profile',
    summary: 'Read the authenticated user profile',
    tags: ['auth'],
    responses: {
      200: jsonResponse('The authenticated user profile', ProfileResponseSchema),
      401: UNAUTHORIZED,
      404: USER_NOT_FOUND,
    },
  });

  registry.registerPath({
    method: 'patch',
    path: '/api/auth/profile',
    summary: 'Update the authenticated user profile',
    tags: ['auth'],
    request: { body: { content: { [JSON_MEDIA_TYPE]: { schema: updateProfileSchema } } } },
    responses: {
      200: jsonResponse('The updated profile', ProfileResponseSchema),
      400: INVALID_INPUT,
      401: UNAUTHORIZED,
      404: USER_NOT_FOUND,
    },
  });

  registry.registerPath({
    method: 'post',
    path: '/api/auth/change-password',
    summary: 'Change the authenticated user password',
    description: 'Ends every session of that user, so other devices must authenticate again.',
    tags: ['auth'],
    request: { body: { content: { [JSON_MEDIA_TYPE]: { schema: changePasswordSchema } } } },
    responses: {
      200: jsonResponse('The password was changed and all sessions were ended', SuccessResponseSchema),
      400: errorResponse('The body failed validation, or the current password is wrong'),
      401: UNAUTHORIZED,
      404: USER_NOT_FOUND,
    },
  });

  registry.registerPath({
    method: 'get',
    path: '/api/auth/setup/status',
    summary: 'Report whether the one-time administrator setup still has to run',
    tags: ['auth'],
    responses: {
      200: jsonResponse('Setup is required only while no user exists at all', SetupStatusResponseSchema),
    },
  });

  registry.registerPath({
    method: 'post',
    path: '/api/auth/setup',
    summary: 'Create the first administrator',
    description: 'Available only while no user exists. Refused once the installation has an administrator.',
    tags: ['auth'],
    request: { body: { content: { [JSON_MEDIA_TYPE]: { schema: setupSchema } } } },
    responses: {
      201: jsonResponse('The administrator was created', SetupResponseSchema),
      400: INVALID_INPUT,
      409: errorResponse('Initial setup is already complete, or could not create the administrator'),
    },
  });

  // ---- loading: schedules ----

  registry.registerPath({
    method: 'get',
    path: '/api/loading/schedules/history',
    summary: 'List loading batches newest first, one item per creation date',
    tags: ['loading'],
    request: { query: HistoryQuerySchema },
    responses: {
      200: jsonResponse('One item per batch date, with server-computed lifecycle flags', ScheduleHistoryResponseSchema),
      400: errorResponse('The query parameters failed validation'),
      401: UNAUTHORIZED,
    },
  });

  registry.registerPath({
    method: 'get',
    path: '/api/loading/schedules',
    summary: "List one date's active schedules",
    tags: ['loading'],
    request: { query: ScheduleQuerySchema },
    responses: {
      200: jsonResponse('The active schedules for that date, ordered by time slot', SchedulesResponseSchema),
      400: errorResponse('The query parameters failed validation'),
      401: UNAUTHORIZED,
    },
  });

  registry.registerPath({
    method: 'post',
    path: '/api/loading/schedules',
    summary: 'Assign a driver to a time slot',
    description:
      'The fletero limit is deliberately NOT enforced: exceeding three fleteros in a rolling hour is ' +
      'informational, and the server accepts the assignment. The only uniqueness rules the server ' +
      'enforces are that one driver and one vehicle cannot appear twice on the same date.',
    tags: ['loading'],
    request: { body: { content: { [JSON_MEDIA_TYPE]: { schema: CreateScheduleSchema } } } },
    responses: {
      201: jsonResponse('The created schedule, projected like a listed one', ScheduleResponseSchema),
      400: errorResponse('The body failed validation, the driver is missing or inactive, or the date already has a deactivated batch'),
      401: UNAUTHORIZED,
      403: errorResponse('The date is read-only for this user'),
      404: errorResponse('The referenced driver or vehicle does not exist'),
      409: errorResponse('That driver or that vehicle is already assigned on this date'),
    },
  });

  registry.registerPath({
    method: 'patch',
    path: '/api/loading/schedules/{id}',
    summary: 'Move or reassign a schedule entry',
    tags: ['loading'],
    request: {
      params: SCHEDULE_ID_PARAMS,
      body: { content: { [JSON_MEDIA_TYPE]: { schema: UpdateScheduleSchema } } },
    },
    responses: {
      200: jsonResponse('The updated schedule, projected like a listed one', ScheduleResponseSchema),
      400: errorResponse('The identifier is not a number, or the body failed validation'),
      401: UNAUTHORIZED,
      403: errorResponse('Only the creator may edit, and only during the first hour'),
      404: errorResponse('No such schedule entry'),
      409: errorResponse('That driver or that vehicle is already assigned on this date'),
    },
  });

  registry.registerPath({
    method: 'delete',
    path: '/api/loading/schedules/{id}',
    summary: 'Delete one schedule entry',
    description: 'An Administrador may delete any entry; a Trabalhador only their own.',
    tags: ['loading'],
    request: { params: SCHEDULE_ID_PARAMS },
    responses: {
      204: { description: 'The entry was deleted' },
      400: errorResponse('The identifier is not a number'),
      401: UNAUTHORIZED,
      403: errorResponse('A Trabalhador may only delete their own entry'),
      404: errorResponse('No such schedule entry'),
    },
  });

  registry.registerPath({
    method: 'patch',
    path: '/api/loading/schedules/{id}/deactivate',
    summary: 'Deactivate one schedule entry',
    tags: ['loading'],
    request: { params: SCHEDULE_ID_PARAMS },
    responses: {
      200: jsonResponse('The entry was deactivated', SuccessResponseSchema),
      400: errorResponse('The identifier is not a number'),
      401: UNAUTHORIZED,
      403: errorResponse('Administrador only'),
      404: errorResponse('No such schedule entry'),
    },
  });

  registry.registerPath({
    method: 'patch',
    path: '/api/loading/schedules/batch/{date}/deactivate',
    summary: 'Deactivate every active entry of one date',
    tags: ['loading'],
    request: { params: BATCH_DATE_PARAMS },
    responses: {
      200: jsonResponse('The batch was deactivated transactionally', SuccessResponseSchema),
      400: errorResponse('The date is not in YYYY-MM-DD form'),
      401: UNAUTHORIZED,
      403: errorResponse('Administrador only'),
      404: errorResponse('No active batch on that date'),
    },
  });

  registry.registerPath({
    method: 'delete',
    path: '/api/loading/schedules/batch/{date}',
    summary: 'Delete every entry of one date',
    tags: ['loading'],
    request: { params: BATCH_DATE_PARAMS },
    responses: {
      204: { description: 'The batch was deleted transactionally' },
      400: errorResponse('The date is not in YYYY-MM-DD form'),
      401: UNAUTHORIZED,
      403: errorResponse('Administrador only'),
      404: errorResponse('No batch on that date'),
    },
  });

  // ---- loading: export and reference data ----

  registry.registerPath({
    method: 'get',
    path: '/api/loading/export',
    summary: 'Render the WhatsApp text for one date',
    description:
      'Formatted on the server in Brazilian Portuguese, ascending by time slot. A client must ' +
      'fetch this text rather than reimplement the format.',
    tags: ['loading'],
    request: { query: ExportQuerySchema },
    responses: {
      200: jsonResponse('The ready-to-send text', TextResponseSchema),
      400: errorResponse('The query parameters failed validation'),
      401: UNAUTHORIZED,
    },
  });

  registry.registerPath({
    method: 'get',
    path: '/api/loading/drivers',
    summary: 'List active drivers of both types',
    tags: ['loading'],
    responses: {
      200: jsonResponse('Active drivers, ordered by name', DriversResponseSchema),
      401: UNAUTHORIZED,
    },
  });

  registry.registerPath({
    method: 'post',
    path: '/api/loading/drivers',
    summary: 'Quick-add an external driver',
    description: 'Always creates a `fletero`; company drivers are managed from the admin surface.',
    tags: ['loading'],
    request: { body: { content: { [JSON_MEDIA_TYPE]: { schema: CreateDriverSchema } } } },
    responses: {
      201: jsonResponse('The created driver', DriverResponseSchema),
      400: INVALID_INPUT,
      401: UNAUTHORIZED,
    },
  });

  registry.registerPath({
    method: 'get',
    path: '/api/loading/vehicles',
    summary: 'List active company vehicles',
    tags: ['loading'],
    responses: {
      200: jsonResponse('Active vehicles, ordered by description', VehiclesResponseSchema),
      401: UNAUTHORIZED,
    },
  });

  registry.registerPath({
    method: 'get',
    path: '/api/loading/time-slots',
    summary: 'List the configured loading time slots',
    description: 'Read from the settings table, falling back to the built-in daily slots.',
    tags: ['loading'],
    responses: {
      200: jsonResponse('The configured slots in ascending order', TimeSlotsResponseSchema),
      401: UNAUTHORIZED,
    },
  });

  // ---- reports: catalogs, reports, photos ----

  registry.registerPath({
    method: 'get',
    path: '/api/reports/categories',
    summary: 'List the active report categories with their tasks',
    tags: ['reports'],
    responses: {
      200: jsonResponse('Active categories, each carrying its active tasks', CategoriesResponseSchema),
      401: UNAUTHORIZED,
    },
  });

  registry.registerPath({
    method: 'get',
    path: '/api/reports',
    summary: 'List reports within a period',
    tags: ['reports'],
    request: { query: ReportQuerySchema },
    responses: {
      200: jsonResponse(
        'Reports in the period, newest first. This endpoint passes no actor, so its items carry no lifecycle flags.',
        ReportsResponseSchema,
      ),
      400: errorResponse('The query parameters failed validation'),
      401: UNAUTHORIZED,
    },
  });

  registry.registerPath({
    method: 'post',
    path: '/api/reports',
    summary: 'Create a report',
    tags: ['reports'],
    request: { body: { content: { [JSON_MEDIA_TYPE]: { schema: CreateReportBodySchema } } } },
    responses: {
      201: jsonResponse('The created report', ReportResponseSchema),
      400: errorResponse('The body failed validation, or a report already exists for that shift and date'),
      401: UNAUTHORIZED,
    },
  });

  registry.registerPath({
    method: 'get',
    path: '/api/reports/history',
    summary: 'List report history with pagination and filters',
    tags: ['reports'],
    request: { query: ReportHistoryQuerySchema },
    responses: {
      200: jsonResponse('History items, each with its server-computed lifecycle flags', ReportHistoryResponseSchema),
      400: errorResponse('The query parameters failed validation'),
      401: UNAUTHORIZED,
    },
  });

  registry.registerPath({
    method: 'get',
    path: '/api/reports/turno',
    summary: 'Report the shift the server detects from its own clock',
    description:
      'Detected in São Paulo time: 18:30 onwards is the tarde shift, and 06:00 onwards the noite shift. A ' +
      'client must ask rather than compute this, so a device with a wrong clock still agrees with the server.',
    tags: ['reports'],
    responses: {
      200: jsonResponse('The detected shift', TurnoResponseSchema),
      401: UNAUTHORIZED,
    },
  });

  registry.registerPath({
    method: 'get',
    path: '/api/reports/{id}',
    summary: 'Read one report with its items and temperature readings',
    tags: ['reports'],
    request: { params: REPORT_ID_PARAMS },
    responses: {
      200: jsonResponse('The report, with lifecycle flags when the caller is allowed to change it', ReportResponseSchema),
      400: errorResponse('The identifier is not a number'),
      401: UNAUTHORIZED,
      404: errorResponse('No such report'),
    },
  });

  registry.registerPath({
    method: 'patch',
    path: '/api/reports/{id}',
    summary: 'Replace the contents of a report',
    tags: ['reports'],
    request: {
      params: REPORT_ID_PARAMS,
      body: { content: { [JSON_MEDIA_TYPE]: { schema: UpdateReportBodySchema } } },
    },
    responses: {
      200: jsonResponse('The updated report', ReportResponseSchema),
      400: errorResponse('The identifier is not a number, or the body failed validation'),
      401: UNAUTHORIZED,
      403: errorResponse('The report is outside its edit window, or belongs to another user'),
      404: errorResponse('No such report'),
    },
  });

  registry.registerPath({
    method: 'delete',
    path: '/api/reports/{id}',
    summary: 'Delete a report',
    description: 'Administrador only.',
    tags: ['reports'],
    request: { params: REPORT_ID_PARAMS },
    responses: {
      204: { description: 'The report was deleted' },
      400: errorResponse('The identifier is not a number'),
      401: UNAUTHORIZED,
      403: errorResponse('Administrador only'),
      404: errorResponse('No such report'),
    },
  });

  registry.registerPath({
    method: 'patch',
    path: '/api/reports/{id}/deactivate',
    summary: 'Deactivate a report',
    description: 'Administrador only.',
    tags: ['reports'],
    request: { params: REPORT_ID_PARAMS },
    responses: {
      200: jsonResponse('The report was deactivated', SuccessResponseSchema),
      400: errorResponse('The identifier is not a number'),
      401: UNAUTHORIZED,
      403: errorResponse('Administrador only'),
      404: errorResponse('No such report'),
    },
  });

  registry.registerPath({
    method: 'get',
    path: '/api/reports/{id}/export',
    summary: 'Render the WhatsApp text for one report',
    description:
      'Formatted on the server in Brazilian Portuguese. A client must fetch this text rather than ' +
      'reimplement the format.',
    tags: ['reports'],
    request: { params: REPORT_ID_PARAMS },
    responses: {
      200: jsonResponse('The ready-to-send text', TextResponseSchema),
      400: errorResponse('The report does not carry every temperature reading its catalog requires'),
      401: UNAUTHORIZED,
      404: errorResponse('No such report'),
    },
  });

  registry.registerPath({
    method: 'get',
    path: '/api/reports/{id}/photos',
    summary: 'List the photos attached to a report',
    tags: ['reports'],
    request: { params: REPORT_ID_PARAMS },
    responses: {
      200: jsonResponse('The attached photos', PhotosResponseSchema),
      401: UNAUTHORIZED,
      404: errorResponse('No such report'),
    },
  });

  registry.registerPath({
    method: 'post',
    path: '/api/reports/{id}/photos',
    summary: 'Attach a photo to a report',
    description:
      'Multipart with the file in a field named "file". Only JPEG, PNG and WebP are accepted, the declared ' +
      'type must match the file content, and a report accepts at most five photos.',
    tags: ['reports'],
    request: {
      params: REPORT_ID_PARAMS,
      body: {
        content: {
          'multipart/form-data': {
            schema: {
              type: 'object',
              properties: { file: { type: 'string', format: 'binary' } },
              required: ['file'],
            },
          },
        },
      },
    },
    responses: {
      201: jsonResponse('The stored photo, with its authenticated and its public URL', PhotoResponseSchema),
      400: errorResponse('No file was sent, or the file was empty'),
      401: UNAUTHORIZED,
      403: errorResponse('The report is outside its edit window'),
      404: errorResponse('No such report'),
      409: errorResponse('The report already carries five photos'),
      413: errorResponse('The file exceeds the 5 MB limit'),
      415: errorResponse('The file is not JPEG, PNG or WebP, or its content does not match the declared type'),
    },
  });

  registry.registerPath({
    method: 'delete',
    path: '/api/reports/photos/{photoId}',
    summary: 'Delete one attached photo',
    tags: ['reports'],
    request: { params: PHOTO_ID_PARAMS },
    responses: {
      200: jsonResponse('The photo was deleted', SuccessResponseSchema),
      401: UNAUTHORIZED,
      403: errorResponse('The report is outside its edit window'),
      404: errorResponse('No such photo'),
    },
  });

  registry.registerPath({
    method: 'get',
    path: '/api/reports/photos/{photoId}',
    summary: 'Fetch one photo',
    description:
      'Requires the Bearer token, so a native client has to attach it. Prefer the public link when the ' +
      'photo is meant to be pasted into a chat.',
    tags: ['reports'],
    request: { params: PHOTO_ID_PARAMS },
    responses: {
      200: binaryResponse('The photo bytes'),
      401: UNAUTHORIZED,
      404: errorResponse('No such photo, or its file is missing from disk'),
    },
  });

  registry.registerPath({
    method: 'get',
    path: '/api/reports/photos/public/{token}',
    summary: 'Fetch a photo by its public token, without authentication',
    description:
      'Documented as implemented, not as intended: this route requires a token of at least sixteen ' +
      + 'characters while the server issues twelve-hex-character tokens, so it answers 404 in practice. The ' +
      'short route `/p/{token}` is the one that works and is what `publicUrl` points at.',
    tags: ['reports'],
    request: { params: PHOTO_TOKEN_PARAMS },
    responses: {
      200: binaryResponse('The photo bytes'),
      404: errorResponse('No such photo token'),
    },
  });

  return registry;
}
/** Build the OpenAPI 3.1 document. Pure: it reads schemas and returns an object. */
export function buildOpenApiDocument() {
  const registry = buildRegistry();
  const generator = new OpenApiGeneratorV31(registry.definitions);

  return generator.generateDocument({
    openapi: '3.1.0',
    info: {
      title: API_TITLE,
      version: API_VERSION,
      description:
        'Generated from the Zod schemas the routes validate with. Never edit the artifact by hand: ' +
        'change the schema and regenerate.',
    },
    servers: [{ url: 'https://trindademasas.duckdns.org', description: 'Production' }],
  });
}

/** Every path this contract currently describes, in document order. */
export function documentedPaths(): string[] {
  const document = buildOpenApiDocument();
  return Object.keys(document.paths ?? {}).sort();
}
