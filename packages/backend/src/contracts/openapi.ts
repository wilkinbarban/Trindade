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
  UpdateScheduleSchema,
  VehicleSchema,
  VehiclesResponseSchema,
} from '../modules/loading/loading.schema.js';
// Every admin schema is aliased, because the admin surface declares its own `Driver`, `Vehicle` and
// driver/vehicle envelope names as wider projections than the loading module's same-named schemas.
// Importing both unaliased would be a duplicate-identifier error; importing the loading one under a
// bare name and the admin one under an alias would work but read as though the two were the same
// shape, which is the confusion the Admin component prefix exists to prevent.
import {
  CategoriesResponseSchema as AdminCategoriesResponseSchema,
  CategoryResponseSchema as AdminCategoryResponseSchema,
  CategorySchema as AdminCategorySchema,
  CreateCategorySchema as CreateAdminCategorySchema,
  CreateDriverSchema as CreateAdminDriverSchema,
  CreateTaskSchema as CreateAdminTaskSchema,
  CreateUserSchema as CreateAdminUserSchema,
  CreateVehicleSchema as CreateAdminVehicleSchema,
  DriverResponseSchema as AdminDriverResponseSchema,
  DriverSchema as AdminDriverSchema,
  DriversResponseSchema as AdminDriversResponseSchema,
  TaskListItemSchema as AdminTaskListItemSchema,
  TaskResponseSchema as AdminTaskResponseSchema,
  TaskSchema as AdminTaskSchema,
  TasksResponseSchema as AdminTasksResponseSchema,
  UpdateCategorySchema as UpdateAdminCategorySchema,
  UpdateDriverSchema as UpdateAdminDriverSchema,
  UpdateTaskSchema as UpdateAdminTaskSchema,
  UpdateTimeSlotsSchema,
  UpdateUserSchema as UpdateAdminUserSchema,
  UpdateVehicleSchema as UpdateAdminVehicleSchema,
  UserResponseSchema as AdminUserResponseSchema,
  UserSchema as AdminUserSchema,
  UsersResponseSchema as AdminUsersResponseSchema,
  VehicleResponseSchema as AdminVehicleResponseSchema,
  VehicleSchema as AdminVehicleSchema,
  VehiclesResponseSchema as AdminVehiclesResponseSchema,
} from '../modules/admin/admin.schema.js';
import { AuditLogSchema, AuditQuerySchema, AuditResponseSchema } from '../modules/audit/audit.schema.js';
import {
  CategoriesResponseSchema,
  CategoryResponseSchema,
  CreateReportBodySchema,
  HistoryQuerySchema as ReportHistoryQuerySchema,
  PhotoResponseSchema,
  PhotoSchema,
  PhotosResponseSchema,
  ProductsResponseSchema,
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
import { DashboardSummarySchema } from '../modules/dashboard/dashboard.schema.js';
import {
  HealthResponseSchema,
  PaginationSchema,
  SuccessResponseSchema,
  TextResponseSchema,
  TimeSlotsResponseSchema,
  UserOptionsResponseSchema,
} from './common.schema.js';
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
 * The whole surface is described. The admin and audit routes -- all of them served under
 * `/api/admin` -- were the last exclusion, and they joined the registry when a mobile client needed
 * them. Nothing is out of scope now, and `route-coverage.test.ts` asserts that emptiness on purpose
 * so a future exclusion has to be written down with its reason rather than creeping back in.
 */
const API_TITLE = 'Trindade Massas Operações API';
const API_VERSION = '0.1.0';
const JSON_MEDIA_TYPE = 'application/json';

/**
 * The component name behind each registered schema, so a response can point at the component instead
 * of repeating it. See registerComponent for why this map exists.
 */
const COMPONENT_NAME_BY_SCHEMA = new Map<z.ZodTypeAny, string>();

/**
 * Register a schema as a named component, and remember the pairing.
 *
 * The remembering is the point. `registry.register` names a schema, but the generator does not turn
 * later uses of it into references: it expands the object inline at every use, which is exactly the
 * anonymous repetition the registrations exist to prevent. The pairing below is what lets
 * jsonResponse and jsonRequest emit a real reference, and it is required rather than optional so a
 * schema that is used but never named fails loudly instead of silently producing an unnamed model in
 * every generated client.
 */
function registerComponent(registry: OpenAPIRegistry, name: string, schema: z.ZodTypeAny): void {
  registry.register(name, schema);
  COMPONENT_NAME_BY_SCHEMA.set(schema, name);
}

/**
 * A reference to a registered component.
 *
 * Cast because the library types a schema position as a Zod type while accepting a plain reference
 * object at runtime, which is the form OpenAPI uses for reuse.
 */
function componentRef(schema: z.ZodTypeAny): z.ZodTypeAny {
  const name = COMPONENT_NAME_BY_SCHEMA.get(schema);
  if (!name) {
    // Naming the shape is what makes this actionable. A schema object has no name to report, so
    // without this the message would say a schema is unregistered and leave the reader to bisect
    // every call site to find which one.
    const shape = schema instanceof z.ZodObject ? Object.keys(schema.shape).join(', ') : '(not a plain object)';
    throw new Error(
      `A schema used in the contract was never registered as a component, so it would be inlined ` +
        `anonymously and every generated client would get an unnamed type for it. Its fields are: ` +
        `[${shape}]. Register it with registerComponent(registry, <Name>, <Schema>) in buildRegistry().`,
    );
  }
  return { $ref: `#/components/schemas/${name}` } as unknown as z.ZodTypeAny;
}

function jsonResponse(description: string, schema: z.ZodTypeAny): ResponseConfig {
  return { description, content: { [JSON_MEDIA_TYPE]: { schema: componentRef(schema) } } };
}

/**
 * A JSON request body, referencing its component for the same reason a response does.
 *
 * `required: false` marks a body a client may omit entirely, which is different from a body whose
 * fields are all optional.
 */
function jsonRequest(schema: z.ZodTypeAny, options: { required?: boolean } = {}) {
  return {
    body: {
      content: { [JSON_MEDIA_TYPE]: { schema: componentRef(schema) } },
      ...(options.required === false ? { required: false } : {}),
    },
  };
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
const ADMIN_ID_PARAMS = z.object({ id: z.string() });

/**
 * The role sentence every admin-only operation carries.
 *
 * OpenAPI has no vocabulary for roles, so a client author can only learn the guard split from the
 * text. There are exactly two roles in this API -- `Administrador` and `Trabalhador` -- and the two
 * guards in `admin.routes.ts` are what split the surface between them. `admin.contract.test.ts`
 * asserts that split against a running server, so these sentences cannot quietly stop being true.
 */
const ADMINISTRATOR_ONLY =
  'Administrador only: a Trabalhador is refused with 403, and a missing or invalid token with 401.';

/** The role sentence for the operations the `catalogGuard` admits both roles to. */
const ADMINISTRATOR_OR_WORKER =
  'Admits Administrador and Trabalhador: those are the only two roles this API defines, so the only ' +
  'refusal from the guard is a missing or invalid token, with 401.';

function buildRegistry(): OpenAPIRegistry {
  const registry = new OpenAPIRegistry();

  // Registered as named components so a generated client gets named models instead of
  // anonymous inline objects repeated at every reference.
  registerComponent(registry, 'ErrorEnvelope', ErrorEnvelopeSchema);
  registerComponent(registry, 'AuthUser', AuthUserSchema);
  registerComponent(registry, 'AuthProfile', AuthProfileSchema);
  registerComponent(registry, 'LoginResponse', LoginResponseSchema);
  registerComponent(registry, 'RefreshResponse', RefreshResponseSchema);
  registerComponent(registry, 'ProfileResponse', ProfileResponseSchema);
  registerComponent(registry, 'SuccessResponse', SuccessResponseSchema);
  registerComponent(registry, 'SetupStatusResponse', SetupStatusResponseSchema);
  registerComponent(registry, 'SetupResponse', SetupResponseSchema);
  registerComponent(registry, 'TextResponse', TextResponseSchema);
  registerComponent(registry, 'Pagination', PaginationSchema);
  registerComponent(registry, 'Schedule', ScheduleSchema);
  registerComponent(registry, 'LoadingBatchHistoryItem', LoadingBatchHistoryItemSchema);
  registerComponent(registry, 'Driver', DriverSchema);
  registerComponent(registry, 'Vehicle', VehicleSchema);
  registerComponent(registry, 'ReportCategory', CategoryResponseSchema);
  registerComponent(registry, 'ProductsResponse', ProductsResponseSchema);
  registerComponent(registry, 'ReportTask', TaskResponseSchema);
  registerComponent(registry, 'ReportListItem', ReportListItemSchema);
  registerComponent(registry, 'ReportDetail', ReportDetailSchema);
  registerComponent(registry, 'ReportItemDetail', ReportItemDetailSchema);
  registerComponent(registry, 'ReportTemperatureDetail', ReportTemperatureDetailSchema);
  registerComponent(registry, 'ReportUser', ReportUserSchema);
  registerComponent(registry, 'ReportPhoto', PhotoSchema);
  registerComponent(registry, 'DashboardSummary', DashboardSummarySchema);
  registerComponent(registry, 'HealthResponse', HealthResponseSchema);
  registerComponent(registry, 'UserOptionsResponse', UserOptionsResponseSchema);

  // Response envelopes. These existed as schemas and were used in the paths below, but were never
  // registered, so every one of them was expanded inline and a generated client got an unnamed
  // duplicate instead of the model it should have. Registering them changes no shape: each is used
  // here unchanged, and the artifact comparison proves the expansion was identical.
  registerComponent(registry, 'SchedulesResponse', SchedulesResponseSchema);
  registerComponent(registry, 'ScheduleResponse', ScheduleResponseSchema);
  registerComponent(registry, 'ScheduleHistoryResponse', ScheduleHistoryResponseSchema);
  registerComponent(registry, 'DriversResponse', DriversResponseSchema);
  registerComponent(registry, 'DriverResponse', DriverResponseSchema);
  registerComponent(registry, 'VehiclesResponse', VehiclesResponseSchema);
  registerComponent(registry, 'TimeSlotsResponse', TimeSlotsResponseSchema);
  registerComponent(registry, 'TurnoResponse', TurnoResponseSchema);
  registerComponent(registry, 'ReportsResponse', ReportsResponseSchema);
  registerComponent(registry, 'ReportResponse', ReportResponseSchema);
  registerComponent(registry, 'ReportHistoryResponse', ReportHistoryResponseSchema);
  registerComponent(registry, 'CategoriesResponse', CategoriesResponseSchema);
  registerComponent(registry, 'PhotosResponse', PhotosResponseSchema);
  registerComponent(registry, 'PhotoResponse', PhotoResponseSchema);

  // Request bodies. Named so a client gets a named input type rather than one invented from the
  // operation id, which is what produced names like ApiAuthLoginPostRequest.
  registerComponent(registry, 'LoginRequest', loginSchema);
  registerComponent(registry, 'RefreshRequest', refreshSessionSchema);
  registerComponent(registry, 'LogoutRequest', logoutSchema);
  registerComponent(registry, 'ChangePasswordRequest', changePasswordSchema);
  registerComponent(registry, 'UpdateProfileRequest', updateProfileSchema);
  registerComponent(registry, 'SetupRequest', setupSchema);
  registerComponent(registry, 'CreateScheduleRequest', CreateScheduleSchema);
  registerComponent(registry, 'UpdateScheduleRequest', UpdateScheduleSchema);
  registerComponent(registry, 'CreateDriverRequest', CreateDriverSchema);
  registerComponent(registry, 'CreateReportRequest', CreateReportBodySchema);
  registerComponent(registry, 'UpdateReportRequest', UpdateReportBodySchema);

  // The admin surface. Every name is prefixed `Admin` because the document already declares
  // narrower `Driver`, `Vehicle`, `DriversResponse`, `VehiclesResponse`, `CategoriesResponse`, and
  // `CreateDriverRequest` components from the loading and reports surfaces, and the admin endpoints
  // serve wider projections than those: a loading driver has no `is_active`, no
  // `created_by_user_id` and no `created_at`, and a loading vehicle has no `is_active` and no
  // `created_at`. Reusing the existing names would publish a shape the admin endpoints do not
  // serve, and the prefix makes the distinction visible in every generated client.
  //
  // `AdminTask` and `AdminTaskListItem` are two components because the admin task list joins the
  // category and carries its `category_name`, while the create and update responses select only the
  // task's own row; one schema with an optional field would describe neither.
  //
  // `TimeSlotsResponse` is deliberately NOT re-registered here. The admin time-slot route serves
  // the same setting under the same envelope as the loading route, so both reference the single
  // component registered above.
  registerComponent(registry, 'AdminCategory', AdminCategorySchema);
  registerComponent(registry, 'AdminCategoryResponse', AdminCategoryResponseSchema);
  registerComponent(registry, 'AdminCategoriesResponse', AdminCategoriesResponseSchema);
  registerComponent(registry, 'CreateAdminCategoryRequest', CreateAdminCategorySchema);
  registerComponent(registry, 'UpdateAdminCategoryRequest', UpdateAdminCategorySchema);
  registerComponent(registry, 'AdminTask', AdminTaskSchema);
  registerComponent(registry, 'AdminTaskListItem', AdminTaskListItemSchema);
  registerComponent(registry, 'AdminTaskResponse', AdminTaskResponseSchema);
  registerComponent(registry, 'AdminTasksResponse', AdminTasksResponseSchema);
  registerComponent(registry, 'CreateAdminTaskRequest', CreateAdminTaskSchema);
  registerComponent(registry, 'UpdateAdminTaskRequest', UpdateAdminTaskSchema);
  registerComponent(registry, 'AdminDriver', AdminDriverSchema);
  registerComponent(registry, 'AdminDriverResponse', AdminDriverResponseSchema);
  registerComponent(registry, 'AdminDriversResponse', AdminDriversResponseSchema);
  registerComponent(registry, 'CreateAdminDriverRequest', CreateAdminDriverSchema);
  registerComponent(registry, 'UpdateAdminDriverRequest', UpdateAdminDriverSchema);
  registerComponent(registry, 'AdminVehicle', AdminVehicleSchema);
  registerComponent(registry, 'AdminVehicleResponse', AdminVehicleResponseSchema);
  registerComponent(registry, 'AdminVehiclesResponse', AdminVehiclesResponseSchema);
  registerComponent(registry, 'CreateAdminVehicleRequest', CreateAdminVehicleSchema);
  registerComponent(registry, 'UpdateAdminVehicleRequest', UpdateAdminVehicleSchema);
  registerComponent(registry, 'AdminUser', AdminUserSchema);
  registerComponent(registry, 'AdminUserResponse', AdminUserResponseSchema);
  registerComponent(registry, 'AdminUsersResponse', AdminUsersResponseSchema);
  registerComponent(registry, 'CreateAdminUserRequest', CreateAdminUserSchema);
  registerComponent(registry, 'UpdateAdminUserRequest', UpdateAdminUserSchema);
  registerComponent(registry, 'UpdateTimeSlotsRequest', UpdateTimeSlotsSchema);
  registerComponent(registry, 'AuditLog', AuditLogSchema);
  registerComponent(registry, 'AuditResponse', AuditResponseSchema);

  // The failure envelopes every path reuses. Declared here rather than at module scope because
  // errorResponse resolves the schema to a component reference, and the map it consults is only
  // populated by the registrations above. At module scope these three were evaluated before any
  // registration had run, so they threw -- which is how the ordering requirement was discovered,
  // and why the failure named the envelope's fields instead of a name it did not have yet.
  const UNAUTHORIZED = errorResponse('Missing, invalid, or expired credentials');
  const INVALID_INPUT = errorResponse('The request body failed validation');
  const USER_NOT_FOUND = errorResponse('The authenticated user no longer exists');

  registry.registerPath({
    method: 'post',
    path: '/api/auth/login',
    summary: 'Exchange credentials for an access token and a refresh session',
    tags: ['auth'],
    request: { ...jsonRequest(loginSchema) },
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
    request: { ...jsonRequest(refreshSessionSchema) },
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
    request: { ...jsonRequest(logoutSchema, { required: false }) },
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
    request: { ...jsonRequest(updateProfileSchema) },
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
    request: { ...jsonRequest(changePasswordSchema) },
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
    request: { ...jsonRequest(setupSchema) },
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
    request: { ...jsonRequest(CreateScheduleSchema) },
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
      ...jsonRequest(UpdateScheduleSchema),
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
    request: { ...jsonRequest(CreateDriverSchema) },
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
    path: '/api/reports/products',
    summary: 'List the products each product-check element type offers',
    description:
      'The offer, not a constraint. A report stores any product name -- the server echoes what it is ' +
      'given -- so this is the list a client puts in front of an operator, served here so a second ' +
      'client does not have to duplicate the constants the SPA keeps.',
    tags: ['reports'],
    responses: {
      200: jsonResponse('The two lists, one per product-check element type', ProductsResponseSchema),
      401: UNAUTHORIZED,
    },
  });

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
    request: { ...jsonRequest(CreateReportBodySchema) },
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
      ...jsonRequest(UpdateReportBodySchema),
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
      'characters while the server issues twelve-hex-character tokens, so it answers 404 in practice. The ' +
      'short route `/p/{token}` is the one that works and is what `publicUrl` points at.',
    tags: ['reports'],
    request: { params: PHOTO_TOKEN_PARAMS },
    responses: {
      200: binaryResponse('The photo bytes'),
      404: errorResponse('No such photo token'),
    },
  });

  // ---- admin: catalog, fleet, users, time slots, and the audit log ----
  //
  // Mounted at `/api/admin` by `routes.ts`, which registers both the admin module and the audit
  // module under that prefix. The two guards in `admin.routes.ts` split the surface: `catalogGuard`
  // admits Administrador and Trabalhador, and `adminGuard` admits Administrador only. Each
  // description below states which one applies, because OpenAPI has no way to say it in the schema.

  registry.registerPath({
    method: 'get',
    path: '/api/admin/categories',
    summary: 'List every report category',
    description: `${ADMINISTRATOR_OR_WORKER} This is the management view, so inactive categories are included.`,
    tags: ['admin'],
    responses: {
      200: jsonResponse('Every category, active or not, in sort order', AdminCategoriesResponseSchema),
      401: UNAUTHORIZED,
    },
  });

  registry.registerPath({
    method: 'post',
    path: '/api/admin/categories',
    summary: 'Create a report category',
    description:
      `${ADMINISTRATOR_ONLY} A category needs a name in Portuguese or Spanish; when only one is sent the ` +
      'server translates the other.',
    tags: ['admin'],
    request: { ...jsonRequest(CreateAdminCategorySchema) },
    responses: {
      201: jsonResponse('The created category', AdminCategoryResponseSchema),
      400: INVALID_INPUT,
      401: UNAUTHORIZED,
      403: errorResponse('Administrador only'),
    },
  });

  registry.registerPath({
    method: 'patch',
    path: '/api/admin/categories/{id}',
    summary: 'Update a report category',
    description: `${ADMINISTRATOR_ONLY} Every field is optional; only the ones sent are changed.`,
    tags: ['admin'],
    request: {
      params: ADMIN_ID_PARAMS,
      ...jsonRequest(UpdateAdminCategorySchema),
    },
    responses: {
      200: jsonResponse('The updated category', AdminCategoryResponseSchema),
      400: errorResponse('The identifier is not a number, or the body failed validation'),
      401: UNAUTHORIZED,
      403: errorResponse('Administrador only'),
      404: errorResponse('No such category'),
    },
  });

  registry.registerPath({
    method: 'delete',
    path: '/api/admin/categories/{id}',
    summary: 'Delete a report category',
    description:
      `${ADMINISTRATOR_ONLY} Refused with 400 when something still references the category, which is ` +
      'why deactivating it is the safer edit.',
    tags: ['admin'],
    request: { params: ADMIN_ID_PARAMS },
    responses: {
      200: jsonResponse('The category was deleted', SuccessResponseSchema),
      400: errorResponse('The identifier is not a number, or the category is referenced elsewhere'),
      401: UNAUTHORIZED,
      403: errorResponse('Administrador only'),
      404: errorResponse('No such category'),
    },
  });

  registry.registerPath({
    method: 'get',
    path: '/api/admin/tasks',
    summary: 'List report tasks with their category name',
    description:
      `${ADMINISTRATOR_OR_WORKER} The tasks carry their category's ` +
      '`category_name`, which only this listing joins. A Trabalhador is shown only the active tasks; ' +
      'an Administrador sees every task, active or not.',
    tags: ['admin'],
    responses: {
      200: jsonResponse('The tasks, each with the name of its category', AdminTasksResponseSchema),
      401: UNAUTHORIZED,
    },
  });

  registry.registerPath({
    method: 'post',
    path: '/api/admin/tasks',
    summary: 'Create a report task',
    description:
      `${ADMINISTRATOR_OR_WORKER} A task created by a Trabalhador is recorded against that user, ` +
      'which is what lets them later edit and delete it; one created by an Administrador is ' +
      'unattributed. A task inherits its `task_type` from the category it is created under, so the ' +
      'request sends a `category_id` rather than a type.',
    tags: ['admin'],
    request: { ...jsonRequest(CreateAdminTaskSchema) },
    responses: {
      201: jsonResponse("The created task, without the category name that only the list carries", AdminTaskResponseSchema),
      400: INVALID_INPUT,
      401: UNAUTHORIZED,
    },
  });

  registry.registerPath({
    method: 'patch',
    path: '/api/admin/tasks/{id}',
    summary: 'Update a report task',
    description:
      `${ADMINISTRATOR_OR_WORKER} A Trabalhador may edit only a task they created, and may not set ` +
      '`is_active`; both are refused with 403. An Administrador has neither limit.',
    tags: ['admin'],
    request: {
      params: ADMIN_ID_PARAMS,
      ...jsonRequest(UpdateAdminTaskSchema),
    },
    responses: {
      200: jsonResponse("The updated task, without the category name that only the list carries", AdminTaskResponseSchema),
      400: errorResponse('The identifier is not a number, or the body failed validation'),
      401: UNAUTHORIZED,
      403: errorResponse('A Trabalhador tried to set is_active, or to edit another user\'s task'),
      404: errorResponse('No such task'),
    },
  });

  registry.registerPath({
    method: 'delete',
    path: '/api/admin/tasks/{id}',
    summary: 'Delete a report task',
    description:
      `${ADMINISTRATOR_OR_WORKER} A Trabalhador may delete only a task they created, and is refused ` +
      'with 403 otherwise. An Administrador may delete any task.',
    tags: ['admin'],
    request: { params: ADMIN_ID_PARAMS },
    responses: {
      200: jsonResponse('The task was deleted', SuccessResponseSchema),
      400: errorResponse('The identifier is not a number, or the task is referenced elsewhere'),
      401: UNAUTHORIZED,
      403: errorResponse("A Trabalhador tried to delete another user's task"),
      404: errorResponse('No such task'),
    },
  });

  registry.registerPath({
    method: 'get',
    path: '/api/admin/drivers',
    summary: 'List drivers',
    description:
      `${ADMINISTRATOR_OR_WORKER} A Trabalhador is shown only the active drivers; an Administrador ` +
      'sees every driver, active or not. Unlike every other admin resource, drivers have no delete ' +
      'route: a driver is deactivated instead.',
    tags: ['admin'],
    responses: {
      200: jsonResponse('The drivers, active and -- for an Administrador -- inactive', AdminDriversResponseSchema),
      401: UNAUTHORIZED,
    },
  });

  registry.registerPath({
    method: 'post',
    path: '/api/admin/drivers',
    summary: 'Create a driver',
    description:
      `${ADMINISTRATOR_OR_WORKER} A Trabalhador may create only a ` +
      '`fletero` driver and is refused with 403 for a `casa` one; an Administrador may create ' +
      'either. A driver created by a Trabalhador is recorded against them.',
    tags: ['admin'],
    request: { ...jsonRequest(CreateAdminDriverSchema) },
    responses: {
      201: jsonResponse('The created driver', AdminDriverResponseSchema),
      400: INVALID_INPUT,
      401: UNAUTHORIZED,
      403: errorResponse('A Trabalhador tried to create a casa driver'),
    },
  });

  registry.registerPath({
    method: 'patch',
    path: '/api/admin/drivers/{id}',
    summary: 'Update a driver',
    description:
      `${ADMINISTRATOR_OR_WORKER} A Trabalhador may edit only a ` +
      '`fletero` driver they created, may not set `is_active`, and may not change the `driver_type` ' +
      "to `casa`; each of those is refused with 403. An Administrador has none of those limits.",
    tags: ['admin'],
    request: {
      params: ADMIN_ID_PARAMS,
      ...jsonRequest(UpdateAdminDriverSchema),
    },
    responses: {
      200: jsonResponse('The updated driver', AdminDriverResponseSchema),
      400: errorResponse('The identifier is not a number, or the body failed validation'),
      401: UNAUTHORIZED,
      403: errorResponse("A Trabalhador tried to set is_active, change the type, or edit another user's driver"),
      404: errorResponse('No such driver'),
    },
  });

  registry.registerPath({
    method: 'get',
    path: '/api/admin/vehicles',
    summary: 'List company vehicles',
    description: `${ADMINISTRATOR_ONLY} This is the management view, so inactive vehicles are included.`,
    tags: ['admin'],
    responses: {
      200: jsonResponse('Every vehicle, active or not', AdminVehiclesResponseSchema),
      401: UNAUTHORIZED,
      403: errorResponse('Administrador only'),
    },
  });

  registry.registerPath({
    method: 'post',
    path: '/api/admin/vehicles',
    summary: 'Create a company vehicle',
    description: `${ADMINISTRATOR_ONLY} A description and a license plate are both required.`,
    tags: ['admin'],
    request: { ...jsonRequest(CreateAdminVehicleSchema) },
    responses: {
      201: jsonResponse('The created vehicle', AdminVehicleResponseSchema),
      400: INVALID_INPUT,
      401: UNAUTHORIZED,
      403: errorResponse('Administrador only'),
    },
  });

  registry.registerPath({
    method: 'patch',
    path: '/api/admin/vehicles/{id}',
    summary: 'Update a company vehicle',
    description: `${ADMINISTRATOR_ONLY} Every field is optional; only the ones sent are changed.`,
    tags: ['admin'],
    request: {
      params: ADMIN_ID_PARAMS,
      ...jsonRequest(UpdateAdminVehicleSchema),
    },
    responses: {
      200: jsonResponse('The updated vehicle', AdminVehicleResponseSchema),
      400: errorResponse('The identifier is not a number, or the body failed validation'),
      401: UNAUTHORIZED,
      403: errorResponse('Administrador only'),
      404: errorResponse('No such vehicle'),
    },
  });

  registry.registerPath({
    method: 'delete',
    path: '/api/admin/vehicles/{id}',
    summary: 'Delete a company vehicle',
    description:
      `${ADMINISTRATOR_ONLY} Refused with 400 when something still references the vehicle, which is ` +
      'why deactivating it is the safer edit.',
    tags: ['admin'],
    request: { params: ADMIN_ID_PARAMS },
    responses: {
      200: jsonResponse('The vehicle was deleted', SuccessResponseSchema),
      400: errorResponse('The identifier is not a number, or the vehicle is referenced elsewhere'),
      401: UNAUTHORIZED,
      403: errorResponse('Administrador only'),
      404: errorResponse('No such vehicle'),
    },
  });

  registry.registerPath({
    method: 'get',
    path: '/api/admin/time-slots',
    summary: 'Read the configured loading time slots',
    description:
      `${ADMINISTRATOR_ONLY} The same setting the loading module reads under ` +
      '`GET /api/loading/time-slots`, falling back to the built-in daily slots when no setting row ' +
      'exists.',
    tags: ['admin'],
    responses: {
      200: jsonResponse('The configured slots in ascending order', TimeSlotsResponseSchema),
      401: UNAUTHORIZED,
      403: errorResponse('Administrador only'),
    },
  });

  registry.registerPath({
    method: 'put',
    path: '/api/admin/time-slots',
    summary: 'Replace the configured loading time slots',
    description: `${ADMINISTRATOR_ONLY} Replaces the whole list; at least one slot is required.`,
    tags: ['admin'],
    request: { ...jsonRequest(UpdateTimeSlotsSchema) },
    responses: {
      200: jsonResponse('The slots as stored, in ascending order', TimeSlotsResponseSchema),
      400: INVALID_INPUT,
      401: UNAUTHORIZED,
      403: errorResponse('Administrador only'),
    },
  });

  registry.registerPath({
    method: 'get',
    path: '/api/admin/users',
    summary: 'List users with their role name',
    description:
      `${ADMINISTRATOR_ONLY} ` +
      'The projection omits `password_hash` and `updated_at`, and the `role_name` arrives from a ' +
      'join, so every listed user carries it.',
    tags: ['admin'],
    responses: {
      200: jsonResponse('Every user, with the joined role name', AdminUsersResponseSchema),
      401: UNAUTHORIZED,
      403: errorResponse('Administrador only'),
    },
  });

  registry.registerPath({
    method: 'post',
    path: '/api/admin/users',
    summary: 'Create a user',
    description:
      `${ADMINISTRATOR_ONLY} ` +
      'The password is stored hashed, and a duplicate username is refused with 400.',
    tags: ['admin'],
    request: { ...jsonRequest(CreateAdminUserSchema) },
    responses: {
      201: jsonResponse('The created user, without its password hash', AdminUserResponseSchema),
      400: errorResponse('The body failed validation, or the username is already taken'),
      401: UNAUTHORIZED,
      403: errorResponse('Administrador only'),
    },
  });

  registry.registerPath({
    method: 'patch',
    path: '/api/admin/users/{id}',
    summary: 'Update a user',
    description:
      `${ADMINISTRATOR_ONLY} ` +
      'An administrator editing their own account may change their details or password, but may not ' +
      'change their own `role_id` or `is_active`; that is refused with 403.',
    tags: ['admin'],
    request: {
      params: ADMIN_ID_PARAMS,
      ...jsonRequest(UpdateAdminUserSchema),
    },
    responses: {
      200: jsonResponse('The updated user, without its password hash', AdminUserResponseSchema),
      400: errorResponse('The identifier is not a number, the body failed validation, or the username is taken'),
      401: UNAUTHORIZED,
      403: errorResponse('Administrador only, or an administrator changing their own role or active flag'),
      404: errorResponse('No such user'),
    },
  });

  registry.registerPath({
    method: 'delete',
    path: '/api/admin/users/{id}',
    summary: 'Delete a user',
    description:
      `${ADMINISTRATOR_ONLY} ` +
      'An administrator may not delete their own account; that is refused with 403.',
    tags: ['admin'],
    request: { params: ADMIN_ID_PARAMS },
    responses: {
      200: jsonResponse('The user was deleted', SuccessResponseSchema),
      400: errorResponse('The identifier is not a number, or the user is referenced elsewhere'),
      401: UNAUTHORIZED,
      403: errorResponse('Administrador only, or an administrator deleting their own account'),
      404: errorResponse('No such user'),
    },
  });

  registry.registerPath({
    method: 'get',
    path: '/api/admin/audit',
    summary: 'Read the paginated audit log',
    description:
      `${ADMINISTRATOR_ONLY} ` +
      'Ordered newest first, and filterable by action, entity type and user id. A log row whose ' +
      'authoring user was deleted keeps its entry with a null `user_id` and `display_name`.',
    tags: ['admin'],
    request: { query: AuditQuerySchema },
    responses: {
      200: jsonResponse('One page of audit rows with its totals', AuditResponseSchema),
      400: errorResponse('The query parameters failed validation'),
      401: UNAUTHORIZED,
      403: errorResponse('Administrador only'),
    },
  });

  // ---- dashboard, and the routes that belong to no module ----

  registry.registerPath({
    method: 'get',
    path: '/api/dashboard/summary',
    summary: 'Read the operational dashboard summary',
    description:
      'Every count is computed server-side, and the date-based ones use the São Paulo day rather than ' +
      'the device clock.',
    tags: ['dashboard'],
    responses: {
      200: jsonResponse('The summary counts, and the progress pairs for two fixed categories', DashboardSummarySchema),
      401: UNAUTHORIZED,
    },
  });

  registry.registerPath({
    method: 'get',
    path: '/api/health',
    summary: 'Report that the process is serving',
    description:
      'Liveness only: it does not check the database, so a client uses it to tell a dead host from a ' +
      'failing request, and nothing more.',
    tags: ['system'],
    responses: {
      200: jsonResponse('The process is serving', HealthResponseSchema),
    },
  });

  registry.registerPath({
    method: 'get',
    path: '/api/users/options',
    summary: 'List the active users the history filters offer',
    tags: ['system'],
    responses: {
      200: jsonResponse('Active users, ordered by display name', UserOptionsResponseSchema),
      401: UNAUTHORIZED,
    },
  });

  registry.registerPath({
    method: 'get',
    path: '/p/{token}',
    summary: 'Fetch a photo by its short public link',
    description:
      'This is the route the WhatsApp exports point at, and the only public photo route that works: the ' +
      'token is the credential and needs no header, which is what makes it pasteable into a chat.',
    tags: ['reports'],
    request: { params: PHOTO_TOKEN_PARAMS },
    responses: {
      200: binaryResponse('The photo bytes'),
      404: errorResponse('No such photo token, or its file is missing from disk'),
    },
  });

  return registry;
}

/**
 * Collapse a union of numeric literals into one integer enum.
 *
 * The defect this prevents, stated exactly: `z.union([z.literal(0), z.literal(1)])` is emitted by
 * zod-to-openapi as `anyOf: [{ type: 'number', enum: [0] }, { type: 'number', enum: [1] }]`. The
 * Kotlin generator reads each branch as its own one-member number enum and, because the values are
 * whole numbers, renders the constants as `java.math.BigDecimal` built from String literals -- so a
 * generated client cannot assign `1` to the field without wrapping it, and the model stops
 * describing the JSON integer the server actually accepts. Collapsing the union into
 * `{ type: 'integer', enum: [0, 1] }` is the shape that generates a plain Kotlin `Int`.
 *
 * The rewrite is deliberately narrow. Every member must be a single-value enum of a NUMBER and
 * every collected value must be an integer, so a genuine enum such as `0.5, 1.5` is left exactly as
 * it is: this fixes the typing of wire integers, it does not coerce numbers. It is idempotent,
 * because the result carries no `anyOf`/`oneOf` for a second pass to match.
 *
 * It runs over the whole finished document from `buildOpenApiDocument` rather than over each schema
 * at registration, so no future schema author has to remember to opt in: a numeric-literal union
 * that reaches the document is normalized, or it is fractional and is deliberately kept.
 */
const NUMERIC_UNION_KEYS = ['anyOf', 'oneOf'] as const;

/**
 * A union member that is a single-value enum of a number, which is how zod-to-openapi renders a
 * numeric `z.literal`. A one-member `enum` is the whole tell: anything else -- a multi-value member,
 * a `const`, a different type -- makes the union something other than a list of numeric literals.
 */
function isSingleNumberLiteral(member: unknown): member is { type: 'number'; enum: [number] } {
  if (!member || typeof member !== 'object') return false;
  const candidate = member as Record<string, unknown>;
  return (
    candidate.type === 'number' &&
    Array.isArray(candidate.enum) &&
    candidate.enum.length === 1 &&
    typeof candidate.enum[0] === 'number'
  );
}

/**
 * The integer values a schema's union spells out, or null when the schema is not a union of integer
 * literals. Returned in member order so the enum keeps the order the schema declared.
 */
function integerLiteralUnionValues(schema: Record<string, unknown>): number[] | null {
  for (const key of NUMERIC_UNION_KEYS) {
    const members = schema[key];
    if (!Array.isArray(members) || members.length === 0) continue;

    const values: number[] = [];
    let everyMemberIsNumberLiteral = true;
    for (const member of members) {
      if (!isSingleNumberLiteral(member)) {
        everyMemberIsNumberLiteral = false;
        break;
      }
      values.push(member.enum[0]);
    }
    if (!everyMemberIsNumberLiteral) continue;
    // The guard that keeps this from being a blunt instrument: one fractional value means the union
    // is a real numeric enum, not a set of integer flags, and it is left for the generator to render.
    if (!values.every(Number.isInteger)) continue;

    return values;
  }
  return null;
}

/**
 * See the comment above `NUMERIC_UNION_KEYS` for the defect this prevents. Exported so a test can
 * prove the guard on a fractional enum, which the built document deliberately does not contain.
 */
export function normalizeNumericLiteralUnions<T>(document: T): T {
  const visit = (value: unknown): void => {
    if (Array.isArray(value)) {
      value.forEach(visit);
      return;
    }
    if (!value || typeof value !== 'object') return;

    const schema = value as Record<string, unknown>;
    const values = integerLiteralUnionValues(schema);
    if (values) {
      for (const key of NUMERIC_UNION_KEYS) delete schema[key];
      schema.type = 'integer';
      schema.enum = values;
    }

    for (const entry of Object.values(schema)) visit(entry);
  };

  visit(document);
  return document;
}

/** Build the OpenAPI 3.1 document. Pure: it reads schemas and returns an object. */
export function buildOpenApiDocument() {
  const registry = buildRegistry();
  const generator = new OpenApiGeneratorV31(registry.definitions);

  const document = generator.generateDocument({
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

  // Applied inside the builder, so `documentedPaths`, `documentedOperations` and the generator
  // entry point all return a normalized document: there is no path that reaches a client without it.
  return normalizeNumericLiteralUnions(document);
}

/** Every path this contract currently describes, in document order. */
export function documentedPaths(): string[] {
  const document = buildOpenApiDocument();
  return Object.keys(document.paths ?? {}).sort();
}

/** The HTTP methods an OpenAPI path item may carry, which is also every method this document uses. */
const OPERATION_METHODS = ['get', 'post', 'put', 'patch', 'delete'] as const;

/**
 * Every operation this contract describes, as a method and path pair.
 *
 * A path alone is not enough to compare against a server. A document can name the right path with
 * the wrong verb, and a comparison that looked only at paths would call that a match.
 */
export function documentedOperations(): { method: string; path: string }[] {
  const document = buildOpenApiDocument();
  return Object.entries(document.paths ?? {})
    .flatMap(([path, pathItem]) => {
      const item = (pathItem ?? {}) as Record<string, unknown>;
      return OPERATION_METHODS.filter((method) => method in item).map((method) => ({
        method: method.toUpperCase(),
        path,
      }));
    })
    .sort((a, b) => `${a.method} ${a.path}`.localeCompare(`${b.method} ${b.path}`));
}
