import Link from "next/link";
import { format, parseQty, isNegative, isZero } from "@stockledger/qty";

/**
 * Shared presentational pieces.
 *
 * Server components with no state — they exist so the semantics of a quantity or
 * an alert are decided in exactly one place. A negative balance should look
 * alarming on every screen without each screen remembering to make it so.
 */

export function Card({
  title,
  subtitle,
  action,
  children,
}: {
  title?: string;
  subtitle?: string;
  action?: React.ReactNode;
  children: React.ReactNode;
}) {
  return (
    <section className="overflow-hidden rounded-[--radius-card] border border-border bg-surface-raised">
      {title && (
        <header className="flex flex-wrap items-center justify-between gap-2 border-b border-border px-4 py-3">
          <div>
            <h2 className="text-sm font-semibold tracking-tight">{title}</h2>
            {subtitle && <p className="mt-0.5 text-xs text-ink-subtle">{subtitle}</p>}
          </div>
          {action}
        </header>
      )}
      {children}
    </section>
  );
}

/**
 * A quantity.
 *
 * Tabular figures so columns align, and colour carries meaning: a negative
 * balance should never be mistaken for a positive one at a glance, because it
 * means the ledger has been bypassed somewhere.
 */
export function Quantity({
  value,
  unit,
  signed = false,
  className = "",
}: {
  value: string;
  unit?: string;
  /** Show an explicit + for inbound, as on a ledger line. */
  signed?: boolean;
  className?: string;
}) {
  let tone = "text-ink";
  let rendered = value;

  try {
    const qty = parseQty(value);
    rendered = format(qty);
    if (isNegative(qty)) {
      tone = "text-danger font-semibold";
    } else if (isZero(qty)) {
      tone = "text-ink-subtle";
    } else if (signed) {
      tone = "text-positive";
      rendered = `+${rendered}`;
    }
  } catch {
    // An unparseable quantity means the API sent something unexpected. Show it
    // verbatim rather than hiding it — silence would be worse.
    tone = "text-warning";
  }

  return (
    <span className={`num ${tone} ${className}`}>
      {rendered}
      {unit && <span className="ml-1 text-xs font-normal text-ink-subtle">{unitLabel(unit)}</span>}
    </span>
  );
}

function unitLabel(unit: string): string {
  switch (unit) {
    case "PIECE":
      return "pc";
    case "KG":
      return "kg";
    case "LITRE":
      return "L";
    case "METRE":
      return "m";
    default:
      return unit.toLowerCase();
  }
}

export function Badge({
  tone = "neutral",
  children,
}: {
  tone?: "neutral" | "positive" | "warning" | "danger" | "accent";
  children: React.ReactNode;
}) {
  const tones = {
    neutral: "bg-surface-sunken text-ink-muted",
    positive: "bg-positive-soft text-positive",
    warning: "bg-warning-soft text-warning",
    danger: "bg-danger-soft text-danger",
    accent: "bg-accent-soft text-accent",
  } as const;

  return (
    <span
      className={`inline-flex items-center rounded px-1.5 py-0.5 text-[11px] font-medium ${tones[tone]}`}
    >
      {children}
    </span>
  );
}

/** An expiry, coloured by urgency — the pharmacy's whole reason for buying this. */
export function ExpiryBadge({ expiresOn, daysRemaining }: { expiresOn: string; daysRemaining: number }) {
  if (daysRemaining < 0) {
    return <Badge tone="danger">expired {Math.abs(daysRemaining)}d ago</Badge>;
  }
  if (daysRemaining <= 30) {
    return <Badge tone="danger">{daysRemaining}d left</Badge>;
  }
  if (daysRemaining <= 90) {
    return <Badge tone="warning">{daysRemaining}d left</Badge>;
  }
  return <Badge tone="neutral">{expiresOn}</Badge>;
}

export function EmptyState({ title, hint }: { title: string; hint?: React.ReactNode }) {
  return (
    <div className="px-4 py-10 text-center">
      <p className="text-sm text-ink-muted">{title}</p>
      {hint && <p className="mt-1 text-xs text-ink-subtle">{hint}</p>}
    </div>
  );
}

/**
 * A failure the operator can act on.
 *
 * Shown instead of a thrown error because the most common cause in development
 * is simply that the API is not running, and that deserves an instruction rather
 * than a stack trace.
 */
export function ErrorPanel({ code, message }: { code: string; message: string }) {
  return (
    <div className="rounded-[--radius-card] border border-danger/40 bg-danger-soft px-4 py-3">
      <p className="text-sm font-medium text-danger">{message}</p>
      <p className="mt-1 font-mono text-[11px] text-danger/70">{code}</p>
    </div>
  );
}

export function TableShell({
  head,
  children,
}: {
  head: React.ReactNode;
  children: React.ReactNode;
}) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full min-w-[640px] border-collapse text-sm">
        <thead className="bg-surface-sunken text-left text-[11px] uppercase tracking-wide text-ink-subtle">
          {head}
        </thead>
        <tbody className="divide-y divide-border">{children}</tbody>
      </table>
    </div>
  );
}

export function Th({
  children,
  align = "left",
}: {
  children?: React.ReactNode;
  align?: "left" | "right";
}) {
  return (
    <th className={`px-4 py-2 font-medium ${align === "right" ? "text-right" : "text-left"}`}>
      {children}
    </th>
  );
}

export function Td({
  children,
  align = "left",
  className = "",
}: {
  children?: React.ReactNode;
  align?: "left" | "right";
  className?: string;
}) {
  return (
    <td className={`px-4 py-2.5 ${align === "right" ? "text-right" : ""} ${className}`}>
      {children}
    </td>
  );
}

export function ProductLink({ id, sku, name }: { id: string; sku: string; name: string }) {
  return (
    <Link href={`/products/${id}`} className="group block">
      <span className="font-medium group-hover:text-accent">{name}</span>
      <span className="ml-2 num text-xs text-ink-subtle">{sku}</span>
    </Link>
  );
}
