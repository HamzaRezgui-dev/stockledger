import "server-only";

/**
 * The StockLedger API client.
 *
 * <p>`server-only` is load-bearing. This module is the single door to the API,
 * and importing it from a client component must be a build error rather than a
 * runtime surprise: the API base URL and (later) service credentials belong on
 * the server, and a client-side fetch would also drag CORS into every call.
 *
 * The web app holds no database driver and no connection string — enforced by
 * its package.json, not by discipline. Everything it knows, it asks the API.
 */

const BASE_URL = process.env.STOCKLEDGER_API_URL ?? "http://localhost:3001";

/**
 * Who is acting.
 *
 * Every movement is attributable, so the API requires this header. Until auth
 * exists it comes from the environment; wiring it to a real session means
 * changing this one constant and nothing else.
 */
const ACTOR = process.env.STOCKLEDGER_ACTOR ?? "operator";

// ---------------------------------------------------------------------------
// Wire types — mirrors of the API's DTOs.
//
// Quantities are strings, never numbers. JSON.parse would turn "0.001" into an
// IEEE-754 double and reintroduce exactly the rounding error the ledger is
// designed to eliminate. See docs/adr/0002-exact-quantities.md.
// ---------------------------------------------------------------------------

export type ProductUnit = "PIECE" | "KG" | "LITRE" | "METRE";

export type LocationKind = "WAREHOUSE" | "SHELF" | "VAN" | "QUARANTINE" | "VIRTUAL";

export type MovementReason =
  | "RECEIPT"
  | "RETURN_IN"
  | "TRANSFER_IN"
  | "SALE"
  | "WRITE_OFF"
  | "TRANSFER_OUT"
  | "ADJUSTMENT"
  | "COUNT_CORRECTION";

/** Which way a reason moves stock — mirrors MovementReason.Direction in Java. */
export const REASON_DIRECTION: Record<MovementReason, "IN" | "OUT" | "EITHER"> = {
  RECEIPT: "IN",
  RETURN_IN: "IN",
  TRANSFER_IN: "IN",
  SALE: "OUT",
  WRITE_OFF: "OUT",
  TRANSFER_OUT: "OUT",
  ADJUSTMENT: "EITHER",
  COUNT_CORRECTION: "EITHER",
};

export interface Product {
  id: string;
  sku: string;
  name: string;
  barcode: string | null;
  unit: ProductUnit;
  tracksBatches: boolean;
  reorderPoint: string;
  archivedAt: string | null;
}

export interface Location {
  id: string;
  code: string;
  name: string;
  kind: LocationKind;
  allowsNegative: boolean;
  archivedAt: string | null;
}

export interface Batch {
  id: string;
  productId: string;
  lotCode: string;
  expiresOn: string | null;
}

export interface OnHand {
  productId: string;
  sku: string;
  productName: string;
  unit: ProductUnit;
  locationId: string;
  locationCode: string;
  batchId: string | null;
  lotCode: string | null;
  expiresOn: string | null;
  qty: string;
  lastMovementAt: string | null;
}

export interface MovementLine {
  id: number;
  occurredAt: string;
  recordedAt: string;
  reason: MovementReason;
  qtyDelta: string;
  /** The balance immediately after this line — what makes the ledger explain itself. */
  balanceAfter: string;
  actor: string;
  note: string | null;
  refType: string | null;
  refId: string | null;
  locationId: string;
  locationCode: string;
  batchId: string | null;
  lotCode: string | null;
  unitCost: string | null;
  reversalOfId: number | null;
  reversed: boolean;
}

export interface LowStock {
  productId: string;
  sku: string;
  productName: string;
  unit: ProductUnit;
  onHand: string;
  reorderPoint: string;
  shortfall: string;
}

export interface NearExpiry {
  productId: string;
  sku: string;
  productName: string;
  locationId: string;
  locationCode: string;
  batchId: string;
  lotCode: string;
  expiresOn: string;
  daysRemaining: number;
  expired: boolean;
  qty: string;
}

export interface Movement {
  id: number;
  productId: string;
  locationId: string;
  batchId: string | null;
  quantity: string;
  reason: MovementReason;
  actor: string;
  note: string | null;
  idempotencyKey: string | null;
  reversalOfId: number | null;
  occurredAt: string;
  recordedAt: string;
}

/** The API's structured error body. */
export interface ApiErrorBody {
  code: string;
  message: string;
  details?: unknown;
}

/**
 * An API call that failed, carrying the structured body.
 *
 * The `code` is what UI branches on — never the message, which is prose and can
 * be reworded. `INSUFFICIENT_STOCK` in particular arrives with available,
 * requested and shortfall so a form can show "you have 3, you asked for 5".
 */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly details?: unknown,
  ) {
    super(message);
    this.name = "ApiError";
  }

  get isInsufficientStock() {
    return this.code === "INSUFFICIENT_STOCK";
  }

  /** True when retrying the identical request could plausibly succeed. */
  get isRetryable() {
    return this.code === "CONCURRENCY_CONFLICT";
  }
}

interface RequestOptions {
  method?: "GET" | "POST";
  body?: unknown;
  /** Sent as Idempotency-Key, so a retried write cannot double-post. */
  idempotencyKey?: string;
  /**
   * Cache behaviour. Stock levels are the definition of volatile, so reads
   * default to no-store: a cached stock figure is a wrong stock figure, and
   * showing yesterday's count is worse than showing a spinner.
   */
  revalidate?: number | false;
}

async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = "GET", body, idempotencyKey, revalidate = false } = options;

  const headers: Record<string, string> = { "X-Actor": ACTOR };
  if (body !== undefined) headers["Content-Type"] = "application/json";
  if (idempotencyKey) headers["Idempotency-Key"] = idempotencyKey;

  // Built up rather than declared inline: with `exactOptionalPropertyTypes`, a
  // property explicitly set to `undefined` is not the same as an absent one, and
  // `fetch` rejects `body: undefined`.
  const init: RequestInit & { next?: { revalidate: number } } = { method, headers };
  if (body !== undefined) {
    init.body = JSON.stringify(body);
  }
  if (revalidate === false) {
    init.cache = "no-store";
  } else {
    init.next = { revalidate };
  }

  let response: Response;
  try {
    response = await fetch(`${BASE_URL}${path}`, init);
  } catch (cause) {
    // A connection failure is the most likely problem in development, so name
    // the actual cause rather than letting an opaque "fetch failed" bubble up.
    throw new ApiError(
      503,
      "API_UNREACHABLE",
      `Cannot reach the StockLedger API at ${BASE_URL}. Is it running? (pnpm api:dev)`,
      cause,
    );
  }

  if (response.status === 204) return undefined as T;

  const text = await response.text();
  const payload = text ? safeJson(text) : null;

  if (!response.ok) {
    const error = (payload ?? {}) as Partial<ApiErrorBody>;
    throw new ApiError(
      response.status,
      error.code ?? `HTTP_${response.status}`,
      error.message ?? `${method} ${path} failed with ${response.status}`,
      error.details,
    );
  }

  return payload as T;
}

function safeJson(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return { code: "MALFORMED_RESPONSE", message: text.slice(0, 500) };
  }
}

// ---------------------------------------------------------------------------
// Reads
// ---------------------------------------------------------------------------

export function getStock(params: { productId?: string; locationId?: string } = {}) {
  return request<OnHand[]>(`/api/v1/stock${query(params)}`);
}

export function getLowStock() {
  return request<LowStock[]>("/api/v1/stock/low");
}

export function getExpiring(withinDays = 90) {
  return request<NearExpiry[]>(`/api/v1/stock/expiring${query({ withinDays })}`);
}

export function getAvailableLots(productId: string, locationId: string) {
  return request<OnHand[]>(`/api/v1/stock/lots${query({ productId, locationId })}`);
}

export function getHistory(productId: string, locationId?: string, limit = 100) {
  return request<MovementLine[]>(`/api/v1/movements${query({ productId, locationId, limit })}`);
}

export function getProducts() {
  return request<Product[]>("/api/v1/products");
}

export function getProduct(id: string) {
  return request<Product>(`/api/v1/products/${id}`);
}

export function getProductByBarcode(barcode: string) {
  return request<Product>(`/api/v1/products/by-barcode${query({ barcode })}`);
}

export function getLocations() {
  return request<Location[]>("/api/v1/locations");
}

export function getBatches(productId: string) {
  return request<Batch[]>(`/api/v1/batches${query({ productId })}`);
}

// ---------------------------------------------------------------------------
// Writes
// ---------------------------------------------------------------------------

export interface PostMovementInput {
  productId: string;
  locationId: string;
  batchId?: string | null;
  /** Signed decimal string, as stored: "-5.000" issues, "5.000" receives. */
  quantity: string;
  reason: MovementReason;
  refType?: string | null;
  refId?: string | null;
  note?: string | null;
}

export function postMovement(input: PostMovementInput, idempotencyKey: string) {
  return request<Movement>("/api/v1/movements", {
    method: "POST",
    body: input,
    idempotencyKey,
  });
}

export function reverseMovement(id: number, note?: string) {
  return request<Movement>(`/api/v1/movements/${id}/reversal`, {
    method: "POST",
    body: { note: note ?? null },
  });
}

export function createProduct(input: {
  sku: string;
  name: string;
  barcode?: string | null;
  unit: ProductUnit;
  tracksBatches: boolean;
  reorderPoint?: string;
}) {
  return request<Product>("/api/v1/products", { method: "POST", body: input });
}

export function createLocation(input: {
  code: string;
  name: string;
  kind: LocationKind;
  allowsNegative: boolean;
}) {
  return request<Location>("/api/v1/locations", { method: "POST", body: input });
}

export function createBatch(input: {
  productId: string;
  lotCode: string;
  expiresOn?: string | null;
}) {
  return request<Batch>("/api/v1/batches", { method: "POST", body: input });
}

/** Builds a query string, dropping null/undefined/empty so params stay tidy. */
function query(params: Record<string, string | number | undefined | null>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null && value !== "") {
      search.set(key, String(value));
    }
  }
  const rendered = search.toString();
  return rendered ? `?${rendered}` : "";
}
