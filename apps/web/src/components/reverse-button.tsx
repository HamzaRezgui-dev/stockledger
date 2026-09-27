"use client";

import { useActionState } from "react";
import { reverseMovementAction, type ActionResult } from "@/lib/actions";

/**
 * Correct a movement by appending its mirror image.
 *
 * Labelled "Reverse", never "Delete" or "Edit" — the vocabulary should match what
 * actually happens, because an operator who believes they deleted something will
 * be confused when both lines stay on screen.
 *
 * Asks for a reason before posting. A correction without an explanation is the
 * thing that makes an audit trail useless six months later.
 */
export function ReverseButton({
  movementId,
  productId,
}: {
  movementId: number;
  productId: string;
}) {
  const [result, submit, pending] = useActionState<
    ActionResult<{ movementId: number }> | null,
    FormData
  >(async (_previous, formData) => reverseMovementAction(formData), null);

  if (result?.ok) {
    return <span className="text-[11px] text-ink-subtle">reversed</span>;
  }

  return (
    <form
      action={submit}
      onSubmit={(event) => {
        const note = window.prompt(
          `Reverse movement #${movementId}?\n\nThis appends a correcting entry — the original stays in the ledger.\n\nWhy?`,
        );
        if (note === null) {
          event.preventDefault();
          return;
        }
        const field = event.currentTarget.elements.namedItem("note");
        if (field instanceof HTMLInputElement) field.value = note;
      }}
      className="inline"
    >
      <input type="hidden" name="movementId" value={movementId} />
      <input type="hidden" name="productId" value={productId} />
      <input type="hidden" name="note" value="" />
      <button
        type="submit"
        disabled={pending}
        title="Append a reversing entry"
        className="rounded px-1.5 py-0.5 text-[11px] text-ink-subtle transition-colors hover:bg-danger-soft hover:text-danger disabled:opacity-50"
      >
        {pending ? "…" : "reverse"}
      </button>
      {result && !result.ok && (
        <span className="ml-1 text-[11px] text-danger" title={result.message}>
          failed
        </span>
      )}
    </form>
  );
}
