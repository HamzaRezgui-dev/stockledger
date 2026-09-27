import Link from "next/link";
import { ApiError, getExpiring, getLowStock, getStock } from "@/lib/api";
import {
  Badge,
  Card,
  EmptyState,
  ErrorPanel,
  ExpiryBadge,
  ProductLink,
  Quantity,
  TableShell,
  Td,
  Th,
} from "@/components/ui";

/**
 * Stock overview.
 *
 * Alerts come first and stock second, because the two questions an operator
 * actually opens this app with are "what do I need to order?" and "what is about
 * to expire?" — not "list everything".
 */
export default async function StockPage() {
  // Fetched together: three independent reads should cost one round trip's
  // latency, not three.
  const [stock, low, expiring] = await Promise.all([
    getStock().catch(toError),
    getLowStock().catch(toError),
    getExpiring(90).catch(toError),
  ]);

  const firstError = [stock, low, expiring].find(isError);
  if (firstError) {
    return (
      <div className="space-y-4">
        <ErrorPanel code={firstError.code} message={firstError.message} />
        <p className="text-sm text-ink-muted">
          Start the API with <code className="num text-xs">pnpm api:dev</code>, and Postgres with{" "}
          <code className="num text-xs">pnpm db:up</code>.
        </p>
      </div>
    );
  }

  const rows = stock as Awaited<ReturnType<typeof getStock>>;
  const lowRows = low as Awaited<ReturnType<typeof getLowStock>>;
  const expiringRows = expiring as Awaited<ReturnType<typeof getExpiring>>;
  const expired = expiringRows.filter((r) => r.expired);

  return (
    <div className="space-y-6">
      <div className="grid gap-3 sm:grid-cols-3">
        <Stat label="Stocked slots" value={String(rows.length)} hint="product × location × batch" />
        <Stat
          label="Below reorder point"
          value={String(lowRows.length)}
          hint={lowRows.length ? "needs ordering" : "all healthy"}
          tone={lowRows.length ? "warning" : "neutral"}
        />
        <Stat
          label="Expiring within 90d"
          value={String(expiringRows.length)}
          hint={expired.length ? `${expired.length} already expired` : "none expired"}
          tone={expired.length ? "danger" : expiringRows.length ? "warning" : "neutral"}
        />
      </div>

      {lowRows.length > 0 && (
        <Card title="Reorder" subtitle="At or below reorder point, summed across locations">
          <TableShell
            head={
              <tr>
                <Th>Product</Th>
                <Th align="right">On hand</Th>
                <Th align="right">Reorder at</Th>
                <Th align="right">Short by</Th>
              </tr>
            }
          >
            {lowRows.map((r) => (
              <tr key={r.productId} className="hover:bg-surface-sunken/60">
                <Td>
                  <ProductLink id={r.productId} sku={r.sku} name={r.productName} />
                </Td>
                <Td align="right">
                  <Quantity value={r.onHand} unit={r.unit} />
                </Td>
                <Td align="right">
                  <Quantity value={r.reorderPoint} />
                </Td>
                <Td align="right">
                  <Badge tone="warning">
                    <Quantity value={r.shortfall} />
                  </Badge>
                </Td>
              </tr>
            ))}
          </TableShell>
        </Card>
      )}

      {expiringRows.length > 0 && (
        <Card title="Expiring" subtitle="Soonest first — expired lots should be written off">
          <TableShell
            head={
              <tr>
                <Th>Product</Th>
                <Th>Lot</Th>
                <Th>Location</Th>
                <Th align="right">Qty</Th>
                <Th align="right">Expiry</Th>
              </tr>
            }
          >
            {expiringRows.map((r) => (
              <tr key={r.batchId + r.locationId} className="hover:bg-surface-sunken/60">
                <Td>
                  <ProductLink id={r.productId} sku={r.sku} name={r.productName} />
                </Td>
                <Td>
                  <span className="num text-xs">{r.lotCode}</span>
                </Td>
                <Td>
                  <span className="num text-xs text-ink-muted">{r.locationCode}</span>
                </Td>
                <Td align="right">
                  <Quantity value={r.qty} />
                </Td>
                <Td align="right">
                  <ExpiryBadge expiresOn={r.expiresOn} daysRemaining={r.daysRemaining} />
                </Td>
              </tr>
            ))}
          </TableShell>
        </Card>
      )}

      <Card title="On hand" subtitle="Derived from the ledger — never stored">
        {rows.length === 0 ? (
          <EmptyState
            title="No stock recorded yet."
            hint={
              <>
                Create a product and a location in <Link href="/setup" className="text-accent underline">Setup</Link>,
                then receive some stock.
              </>
            }
          />
        ) : (
          <TableShell
            head={
              <tr>
                <Th>Product</Th>
                <Th>Location</Th>
                <Th>Lot</Th>
                <Th align="right">Qty</Th>
              </tr>
            }
          >
            {rows.map((r) => (
              <tr
                key={`${r.productId}-${r.locationId}-${r.batchId ?? "none"}`}
                className="hover:bg-surface-sunken/60"
              >
                <Td>
                  <ProductLink id={r.productId} sku={r.sku} name={r.productName} />
                </Td>
                <Td>
                  <span className="num text-xs text-ink-muted">{r.locationCode}</span>
                </Td>
                <Td>
                  {r.lotCode ? (
                    <span className="num text-xs">{r.lotCode}</span>
                  ) : (
                    <span className="text-xs text-ink-subtle">—</span>
                  )}
                </Td>
                <Td align="right">
                  <Quantity value={r.qty} unit={r.unit} />
                </Td>
              </tr>
            ))}
          </TableShell>
        )}
      </Card>
    </div>
  );
}

function Stat({
  label,
  value,
  hint,
  tone = "neutral",
}: {
  label: string;
  value: string;
  hint?: string;
  tone?: "neutral" | "warning" | "danger";
}) {
  const accent = {
    neutral: "text-ink",
    warning: "text-warning",
    danger: "text-danger",
  }[tone];

  return (
    <div className="rounded-[--radius-card] border border-border bg-surface-raised px-4 py-3">
      <p className="text-[11px] uppercase tracking-wide text-ink-subtle">{label}</p>
      <p className={`num mt-1 text-2xl font-semibold ${accent}`}>{value}</p>
      {hint && <p className="mt-0.5 text-xs text-ink-subtle">{hint}</p>}
    </div>
  );
}

/** Normalises a rejected read into a renderable shape. */
function toError(error: unknown): { __error: true; code: string; message: string } {
  if (error instanceof ApiError) {
    return { __error: true, code: error.code, message: error.message };
  }
  return {
    __error: true,
    code: "UNEXPECTED",
    message: error instanceof Error ? error.message : "Failed to load stock",
  };
}

function isError(value: unknown): value is { __error: true; code: string; message: string } {
  return typeof value === "object" && value !== null && "__error" in value;
}
