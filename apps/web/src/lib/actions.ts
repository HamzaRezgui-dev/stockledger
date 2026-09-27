"use server";

import { randomUUID } from "node:crypto";
import { revalidatePath } from "next/cache";
import {
  ApiError,
  REASON_DIRECTION,
  createBatch,
  createLocation,
  createProduct,
  getProductByBarcode,
  postMovement,
  reverseMovement,
  type LocationKind,
  type MovementReason,
  type Product,
  type ProductUnit,
} from "./api";
import { parseQty, negate, isPositive, toDecimalString, QtyError } from "@stockledger/qty";

/**
 * Server actions: every write the UI can perform.
 *
 * Running on the server keeps the API URL out of the browser and means writes
 * never touch CORS. It also puts quantity parsing on both sides of the wire —
 * the browser validates for instant feedback, this validates before spending a
 * request, and the API validates because it is the only layer that is
 * authoritative.
 */

/** What every action returns, so forms render errors uniformly. */
export type ActionResult<T = void> =
  | { ok: true; data: T }
  | { ok: false; code: string; message: string; details?: unknown };

function failure(error: unknown): ActionResult<never> {
  if (error instanceof ApiError) {
    return { ok: false, code: error.code, message: error.message, details: error.details };
  }
  if (error instanceof QtyError) {
    return { ok: false, code: "INVALID_QUANTITY", message: error.message };
  }
  return {
    ok: false,
    code: "UNEXPECTED",
    message: error instanceof Error ? error.message : "Something went wrong",
  };
}

/**
 * Post a movement.
 *
 * The form supplies a **positive** magnitude plus a reason, and this derives the
 * sign from the reason's direction. That is deliberate at the UI boundary even
 * though the API takes a signed quantity: an operator pressing "Sell" should
 * never have to think about minus signs, and a stray one would otherwise turn a
 * sale into a receipt. The API still validates the sign it receives.
 */
export async function postMovementAction(
  formData: FormData,
): Promise<ActionResult<{ movementId: number }>> {
  try {
    const productId = str(formData, "productId");
    const locationId = str(formData, "locationId");
    const reason = str(formData, "reason") as MovementReason;
    const rawQuantity = str(formData, "quantity");
    const batchId = optional(formData, "batchId");
    const note = optional(formData, "note");

    if (!productId) return bad("productId", "Pick a product");
    if (!locationId) return bad("locationId", "Pick a location");
    if (!REASON_DIRECTION[reason]) return bad("reason", "Pick a reason");
    if (!rawQuantity) return bad("quantity", "Enter a quantity");

    const magnitude = parseQty(rawQuantity);
    if (!isPositive(magnitude)) {
      return bad("quantity", "Enter a quantity greater than zero");
    }

    const direction = REASON_DIRECTION[reason];
    const signed = direction === "OUT" ? negate(magnitude) : magnitude;

    // A fresh key per submission. It protects against a network retry of *this*
    // submission double-posting; it deliberately does not deduplicate a
    // genuinely repeated action, because receiving the same goods twice is a
    // real thing that happens.
    const movement = await postMovement(
      {
        productId,
        locationId,
        batchId: batchId || null,
        quantity: toDecimalString(signed),
        reason,
        note: note || null,
      },
      randomUUID(),
    );

    revalidatePath("/");
    revalidatePath(`/products/${productId}`);
    return { ok: true, data: { movementId: movement.id } };
  } catch (error) {
    return failure(error);
  }
}

/** Correct a movement by appending its mirror image. Never edits history. */
export async function reverseMovementAction(
  formData: FormData,
): Promise<ActionResult<{ movementId: number }>> {
  try {
    const id = Number(str(formData, "movementId"));
    const productId = str(formData, "productId");
    const note = optional(formData, "note");
    if (!Number.isInteger(id) || id <= 0) return bad("movementId", "Invalid movement");

    const reversal = await reverseMovement(id, note || undefined);

    revalidatePath("/");
    revalidatePath(`/products/${productId}`);
    return { ok: true, data: { movementId: reversal.id } };
  } catch (error) {
    return failure(error);
  }
}

export async function createProductAction(formData: FormData): Promise<ActionResult<Product>> {
  try {
    const sku = str(formData, "sku");
    const name = str(formData, "name");
    const unit = str(formData, "unit") as ProductUnit;
    const barcode = optional(formData, "barcode");
    const reorderPoint = optional(formData, "reorderPoint");
    const tracksBatches = formData.get("tracksBatches") === "on";

    if (!sku) return bad("sku", "SKU is required");
    if (!name) return bad("name", "Name is required");

    // Validated here so a typo costs no round trip; the API checks again.
    if (reorderPoint) parseQty(reorderPoint);

    const product = await createProduct({
      sku,
      name,
      barcode: barcode || null,
      unit,
      tracksBatches,
      reorderPoint: reorderPoint || "0",
    });

    revalidatePath("/setup");
    revalidatePath("/");
    return { ok: true, data: product };
  } catch (error) {
    return failure(error);
  }
}

export async function createLocationAction(formData: FormData): Promise<ActionResult> {
  try {
    const code = str(formData, "code");
    const name = str(formData, "name");
    const kind = str(formData, "kind") as LocationKind;
    const allowsNegative = formData.get("allowsNegative") === "on";

    if (!code) return bad("code", "Code is required");
    if (!name) return bad("name", "Name is required");

    await createLocation({ code, name, kind, allowsNegative });
    revalidatePath("/setup");
    revalidatePath("/");
    return { ok: true, data: undefined };
  } catch (error) {
    return failure(error);
  }
}

export async function createBatchAction(formData: FormData): Promise<ActionResult> {
  try {
    const productId = str(formData, "productId");
    const lotCode = str(formData, "lotCode");
    const expiresOn = optional(formData, "expiresOn");

    if (!productId) return bad("productId", "Pick a product");
    if (!lotCode) return bad("lotCode", "Lot code is required");

    await createBatch({ productId, lotCode, expiresOn: expiresOn || null });
    revalidatePath("/setup");
    revalidatePath(`/products/${productId}`);
    return { ok: true, data: undefined };
  } catch (error) {
    return failure(error);
  }
}

/** Barcode lookup for the scanner. A miss is a normal outcome, not an error. */
export async function lookupBarcodeAction(
  barcode: string,
): Promise<ActionResult<Product | null>> {
  try {
    const trimmed = barcode.trim();
    if (!trimmed) return bad("barcode", "No barcode supplied");
    const product = await getProductByBarcode(trimmed);
    return { ok: true, data: product };
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) {
      // An unknown barcode is information, so the UI can offer to create it.
      return { ok: true, data: null };
    }
    return failure(error);
  }
}

function str(formData: FormData, key: string): string {
  const value = formData.get(key);
  return typeof value === "string" ? value.trim() : "";
}

function optional(formData: FormData, key: string): string {
  return str(formData, key);
}

function bad(field: string, message: string): ActionResult<never> {
  return { ok: false, code: "VALIDATION_FAILED", message, details: { field } };
}
