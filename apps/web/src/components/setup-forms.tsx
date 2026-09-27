"use client";

import { useActionState } from "react";
import {
  createBatchAction,
  createLocationAction,
  createProductAction,
  type ActionResult,
} from "@/lib/actions";

/** Reference-data forms. Small, inline, and each reports its own outcome. */

export function CreateLocationForm() {
  const [result, submit, pending] = useActionState<ActionResult | null, FormData>(
    async (_previous, formData) => createLocationAction(formData),
    null,
  );

  return (
    <form action={submit} className="space-y-2">
      <div className="grid grid-cols-2 gap-2">
        <input name="code" placeholder="WH1" required className={`${input} num`} aria-label="Code" />
        <input name="name" placeholder="Main warehouse" required className={input} aria-label="Name" />
      </div>
      <div className="flex items-center gap-2">
        <select name="kind" defaultValue="WAREHOUSE" className={`${input} flex-1`} aria-label="Kind">
          <option value="WAREHOUSE">Warehouse</option>
          <option value="SHELF">Shelf</option>
          <option value="VAN">Van</option>
          <option value="QUARANTINE">Quarantine</option>
          <option value="VIRTUAL">Virtual (supplier/customer)</option>
        </select>
        <Submit pending={pending} label="Add" />
      </div>
      {/*
        Only offered for VIRTUAL in spirit, but left available with a warning:
        a supplier or shrinkage counterparty legitimately holds unbounded negative
        stock, and forcing users to fake a receipt into it would be worse.
      */}
      <label className="flex items-center gap-2 text-[11px] text-ink-subtle">
        <input type="checkbox" name="allowsNegative" className="accent-accent" />
        may hold a negative balance (virtual counterparties only)
      </label>
      <Outcome result={result} success="Location added." />
    </form>
  );
}

export function CreateProductForm() {
  const [result, submit, pending] = useActionState<ActionResult | null, FormData>(
    async (_previous, formData) => {
      const outcome = await createProductAction(formData);
      return outcome.ok ? { ok: true, data: undefined } : outcome;
    },
    null,
  );

  return (
    <form action={submit} className="space-y-2">
      <div className="grid grid-cols-2 gap-2">
        <input name="sku" placeholder="BOLT-M8" required className={`${input} num`} aria-label="SKU" />
        <input name="name" placeholder="Bolt M8 galvanised" required className={input} aria-label="Name" />
      </div>
      <div className="grid grid-cols-2 gap-2">
        <input name="barcode" placeholder="Barcode (optional)" className={`${input} num`} aria-label="Barcode" />
        <select name="unit" defaultValue="PIECE" className={input} aria-label="Unit">
          <option value="PIECE">Pieces</option>
          <option value="KG">Kilograms</option>
          <option value="LITRE">Litres</option>
          <option value="METRE">Metres</option>
        </select>
      </div>
      <div className="flex items-center gap-2">
        <input
          name="reorderPoint"
          placeholder="Reorder at (0 = off)"
          className={`${input} num flex-1`}
          aria-label="Reorder point"
        />
        <Submit pending={pending} label="Add" />
      </div>
      <label className="flex items-center gap-2 text-[11px] text-ink-subtle">
        <input type="checkbox" name="tracksBatches" className="accent-accent" />
        tracks batches and expiry — cannot be changed later
      </label>
      <Outcome result={result} success="Product added." />
    </form>
  );
}

export function CreateBatchForm({ products }: { products: { id: string; label: string }[] }) {
  const [result, submit, pending] = useActionState<ActionResult | null, FormData>(
    async (_previous, formData) => createBatchAction(formData),
    null,
  );

  return (
    <form action={submit} className="space-y-2">
      <div className="grid gap-2 sm:grid-cols-[2fr_1fr_1fr_auto]">
        <select name="productId" required defaultValue="" className={input} aria-label="Product">
          <option value="" disabled>
            Choose a product…
          </option>
          {products.map((p) => (
            <option key={p.id} value={p.id}>
              {p.label}
            </option>
          ))}
        </select>
        <input name="lotCode" placeholder="LOT-2027-03" required className={`${input} num`} aria-label="Lot code" />
        <input name="expiresOn" type="date" className={`${input} num`} aria-label="Expires on" />
        <Submit pending={pending} label="Add lot" />
      </div>
      <Outcome result={result} success="Lot added." />
    </form>
  );
}

function Submit({ pending, label }: { pending: boolean; label: string }) {
  return (
    <button
      type="submit"
      disabled={pending}
      className="shrink-0 rounded-md bg-accent px-3 py-2 text-sm font-medium text-white transition-colors hover:bg-accent-hover disabled:opacity-50"
    >
      {pending ? "…" : label}
    </button>
  );
}

function Outcome({ result, success }: { result: ActionResult | null; success: string }) {
  if (!result) return null;
  if (result.ok) {
    return <p className="text-[11px] text-positive">{success}</p>;
  }
  return <p className="text-[11px] text-danger">{result.message}</p>;
}

const input =
  "w-full rounded-md border border-border bg-surface px-2.5 py-2 text-sm text-ink outline-none transition-colors focus:border-accent";
