"use client";

import { useActionState } from "react";
import { postMovementAction, type ActionResult } from "@/lib/actions";
import { parseQty, isPositive } from "@stockledger/qty";

/**
 * Post a movement.
 *
 * The operator picks an action ("Receive", "Sell", …) and types a **positive**
 * quantity; the server derives the sign from the reason. Asking a warehouse
 * worker to type a minus sign to remove stock is how you get stock added by
 * accident.
 */

interface LocationOption {
  id: string;
  code: string;
  name: string;
  allowsNegative: boolean;
}

interface BatchOption {
  id: string;
  lotCode: string;
  expiresOn: string | null;
}

const REASONS = [
  { value: "RECEIPT", label: "Receive", direction: "in" },
  { value: "RETURN_IN", label: "Customer return", direction: "in" },
  { value: "TRANSFER_IN", label: "Transfer in", direction: "in" },
  { value: "SALE", label: "Sell", direction: "out" },
  { value: "WRITE_OFF", label: "Write off", direction: "out" },
  { value: "TRANSFER_OUT", label: "Transfer out", direction: "out" },
  { value: "COUNT_CORRECTION", label: "Count correction", direction: "either" },
] as const;

export function MoveForm({
  productId,
  unit,
  tracksBatches,
  locations,
  batches,
}: {
  productId: string;
  unit: string;
  tracksBatches: boolean;
  locations: LocationOption[];
  batches: BatchOption[];
}) {
  const [result, submit, pending] = useActionState<ActionResult<{ movementId: number }> | null, FormData>(
    async (_previous, formData) => postMovementAction(formData),
    null,
  );

  // Lots are offered earliest-expiry-first so the default pick is the FEFO one:
  // the stock that would otherwise be written off leaves first.
  const sortedBatches = [...batches].sort((a, b) => {
    if (a.expiresOn === b.expiresOn) return a.lotCode.localeCompare(b.lotCode);
    if (!a.expiresOn) return 1;
    if (!b.expiresOn) return -1;
    return a.expiresOn.localeCompare(b.expiresOn);
  });

  const blockedOnBatches = tracksBatches && sortedBatches.length === 0;

  return (
    <form action={submit} className="space-y-3">
      <input type="hidden" name="productId" value={productId} />

      <Field label="Action">
        <select name="reason" defaultValue="RECEIPT" className={selectClass} required>
          <optgroup label="Stock in">
            {REASONS.filter((r) => r.direction === "in").map((r) => (
              <option key={r.value} value={r.value}>
                {r.label}
              </option>
            ))}
          </optgroup>
          <optgroup label="Stock out">
            {REASONS.filter((r) => r.direction === "out").map((r) => (
              <option key={r.value} value={r.value}>
                {r.label}
              </option>
            ))}
          </optgroup>
          <optgroup label="Correction">
            {REASONS.filter((r) => r.direction === "either").map((r) => (
              <option key={r.value} value={r.value}>
                {r.label}
              </option>
            ))}
          </optgroup>
        </select>
      </Field>

      <Field label="Location">
        <select name="locationId" className={selectClass} required defaultValue="">
          <option value="" disabled>
            Choose…
          </option>
          {locations.map((l) => (
            <option key={l.id} value={l.id}>
              {l.code} — {l.name}
              {l.allowsNegative ? " (virtual)" : ""}
            </option>
          ))}
        </select>
      </Field>

      {tracksBatches && (
        <Field
          label="Lot"
          hint={
            blockedOnBatches
              ? "This product tracks batches but has no lots yet — create one in Setup."
              : "Earliest expiry first (FEFO)"
          }
        >
          <select name="batchId" className={selectClass} required defaultValue="">
            <option value="" disabled>
              Choose…
            </option>
            {sortedBatches.map((b) => (
              <option key={b.id} value={b.id}>
                {b.lotCode}
                {b.expiresOn ? ` — exp ${b.expiresOn}` : " — no expiry"}
              </option>
            ))}
          </select>
        </Field>
      )}

      <Field label={`Quantity (${unit.toLowerCase()})`} hint="Positive — the action sets direction">
        <input
          name="quantity"
          type="text"
          inputMode="decimal"
          placeholder="0.000"
          required
          onBlur={(e) => {
            // Instant feedback using the same rules the server enforces, so a
            // typo is caught before it costs a request. The server validates
            // again regardless — this is UX, not the guarantee.
            const raw = e.currentTarget.value.trim();
            if (!raw) return;
            try {
              const qty = parseQty(raw);
              e.currentTarget.setCustomValidity(
                isPositive(qty) ? "" : "Enter a quantity greater than zero",
              );
            } catch (error) {
              e.currentTarget.setCustomValidity(
                error instanceof Error ? error.message : "Invalid quantity",
              );
            }
            e.currentTarget.reportValidity();
          }}
          onInput={(e) => e.currentTarget.setCustomValidity("")}
          className={`${inputClass} num`}
        />
      </Field>

      <Field label="Note" hint="optional">
        <input name="note" type="text" placeholder="Delivery note 4417" className={inputClass} />
      </Field>

      <button
        type="submit"
        disabled={pending || blockedOnBatches}
        className="w-full rounded-md bg-accent px-3 py-2 text-sm font-medium text-white transition-colors hover:bg-accent-hover disabled:cursor-not-allowed disabled:opacity-50"
      >
        {pending ? "Posting…" : "Post movement"}
      </button>

      {result && <Result result={result} />}
    </form>
  );
}

function Result({ result }: { result: ActionResult<{ movementId: number }> }) {
  if (result.ok) {
    return (
      <p className="rounded-md bg-positive-soft px-3 py-2 text-xs text-positive">
        Posted as movement #{result.data.movementId}.
      </p>
    );
  }

  // INSUFFICIENT_STOCK arrives with the numbers, so say them rather than making
  // the operator go and look.
  const details = result.details as
    | { available?: string; requested?: string; shortfall?: string }
    | undefined;

  return (
    <div className="rounded-md bg-danger-soft px-3 py-2 text-xs text-danger">
      <p className="font-medium">{result.message}</p>
      {result.code === "INSUFFICIENT_STOCK" && details?.available !== undefined && (
        <p className="num mt-1">
          available {details.available} · requested {details.requested} · short {details.shortfall}
        </p>
      )}
      {result.code === "CONCURRENCY_CONFLICT" && (
        <p className="mt-1">Another terminal is writing to this item. Try again.</p>
      )}
    </div>
  );
}

function Field({
  label,
  hint,
  children,
}: {
  label: string;
  hint?: string;
  children: React.ReactNode;
}) {
  return (
    <label className="block">
      <span className="mb-1 block text-[11px] font-medium uppercase tracking-wide text-ink-subtle">
        {label}
      </span>
      {children}
      {hint && <span className="mt-1 block text-[11px] text-ink-subtle">{hint}</span>}
    </label>
  );
}

const inputClass =
  "w-full rounded-md border border-border bg-surface px-3 py-2 text-sm text-ink outline-none transition-colors focus:border-accent";

const selectClass = `${inputClass} appearance-none`;
