import Link from "next/link";
import { notFound } from "next/navigation";
import {
  ApiError,
  getBatches,
  getHistory,
  getLocations,
  getProduct,
  getStock,
} from "@/lib/api";
import {
  Badge,
  Card,
  EmptyState,
  ErrorPanel,
  Quantity,
  TableShell,
  Td,
  Th,
} from "@/components/ui";
import { MoveForm } from "@/components/move-form";
import { ReverseButton } from "@/components/reverse-button";

/**
 * One product: what is on hand, and the ledger lines that produced it.
 *
 * The history table is the point of the whole system. Every balance decomposes
 * into the movements behind it, each showing the balance as it stood immediately
 * after — so "why is this 11?" is answered on screen instead of requiring a
 * physical recount.
 */
export default async function ProductPage({ params }: { params: Promise<{ id: string }> }) {
  // Next 16: route params arrive as a Promise.
  const { id } = await params;

  let product;
  try {
    product = await getProduct(id);
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) notFound();
    const message = error instanceof Error ? error.message : "Failed to load product";
    const code = error instanceof ApiError ? error.code : "UNEXPECTED";
    return <ErrorPanel code={code} message={message} />;
  }

  const [history, stock, locations, batches] = await Promise.all([
    getHistory(id, undefined, 200),
    getStock({ productId: id }),
    getLocations(),
    product.tracksBatches ? getBatches(id) : Promise.resolve([]),
  ]);

  const total = stock.reduce((sum, row) => sum + Number(row.qty), 0);

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <Link href="/" className="text-xs text-ink-subtle hover:text-accent">
            ← Stock
          </Link>
          <h1 className="mt-1 text-xl font-semibold tracking-tight">{product.name}</h1>
          <p className="num mt-0.5 text-xs text-ink-subtle">
            {product.sku}
            {product.barcode && ` · ${product.barcode}`}
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          {product.tracksBatches && <Badge tone="accent">batch tracked</Badge>}
          {product.archivedAt && <Badge tone="warning">archived</Badge>}
          <Badge tone="neutral">{product.unit}</Badge>
        </div>
      </div>

      <div className="grid gap-4 lg:grid-cols-[1fr_340px]">
        <div className="space-y-4">
          <Card
            title="On hand"
            subtitle={`${stock.length} slot${stock.length === 1 ? "" : "s"}`}
            action={
              <span className="text-xs text-ink-subtle">
                total <Quantity value={String(total)} unit={product.unit} />
              </span>
            }
          >
            {stock.length === 0 ? (
              <EmptyState title="Nothing on hand." hint="Receive stock using the form." />
            ) : (
              <TableShell
                head={
                  <tr>
                    <Th>Location</Th>
                    <Th>Lot</Th>
                    <Th>Expiry</Th>
                    <Th align="right">Qty</Th>
                  </tr>
                }
              >
                {stock.map((row) => (
                  <tr key={`${row.locationId}-${row.batchId ?? "none"}`}>
                    <Td>
                      <span className="num text-xs">{row.locationCode}</span>
                    </Td>
                    <Td>
                      {row.lotCode ? (
                        <span className="num text-xs">{row.lotCode}</span>
                      ) : (
                        <span className="text-xs text-ink-subtle">—</span>
                      )}
                    </Td>
                    <Td>
                      <span className="num text-xs text-ink-muted">{row.expiresOn ?? "—"}</span>
                    </Td>
                    <Td align="right">
                      <Quantity value={row.qty} unit={product.unit} />
                    </Td>
                  </tr>
                ))}
              </TableShell>
            )}
          </Card>

          <Card
            title="Ledger"
            subtitle="Newest first. Every line shows the balance immediately after it."
          >
            {history.length === 0 ? (
              <EmptyState title="No movements yet." />
            ) : (
              <TableShell
                head={
                  <tr>
                    <Th>When</Th>
                    <Th>Reason</Th>
                    <Th>Where</Th>
                    <Th align="right">Change</Th>
                    <Th align="right">Balance</Th>
                    <Th>Who</Th>
                    <Th />
                  </tr>
                }
              >
                {history.map((line) => (
                  <tr
                    key={line.id}
                    className={
                      line.reversalOfId || line.reversed
                        ? "bg-surface-sunken/40 hover:bg-surface-sunken/70"
                        : "hover:bg-surface-sunken/60"
                    }
                  >
                    <Td>
                      <span className="num text-xs text-ink-muted">
                        {formatWhen(line.occurredAt)}
                      </span>
                    </Td>
                    <Td>
                      <span className="text-xs">{humanReason(line.reason)}</span>
                      {line.reversalOfId && (
                        <span className="ml-1.5">
                          <Badge tone="accent">reverses #{line.reversalOfId}</Badge>
                        </span>
                      )}
                      {line.reversed && (
                        <span className="ml-1.5">
                          <Badge tone="warning">reversed</Badge>
                        </span>
                      )}
                      {line.note && (
                        <p className="mt-0.5 text-[11px] text-ink-subtle">{line.note}</p>
                      )}
                    </Td>
                    <Td>
                      <span className="num text-xs text-ink-muted">{line.locationCode}</span>
                      {line.lotCode && (
                        <span className="num ml-1 text-[11px] text-ink-subtle">{line.lotCode}</span>
                      )}
                    </Td>
                    <Td align="right">
                      <Quantity value={line.qtyDelta} signed />
                    </Td>
                    <Td align="right">
                      <Quantity value={line.balanceAfter} className="font-semibold" />
                    </Td>
                    <Td>
                      <span className="text-xs text-ink-muted">{line.actor}</span>
                    </Td>
                    <Td align="right">
                      {/* A reversal cannot itself be reversed, and nothing can be
                          reversed twice — so the control simply is not offered. */}
                      {!line.reversed && !line.reversalOfId && (
                        <ReverseButton movementId={line.id} productId={id} />
                      )}
                    </Td>
                  </tr>
                ))}
              </TableShell>
            )}
          </Card>
        </div>

        <div className="space-y-4">
          <Card title="Move stock">
            <div className="p-4">
              <MoveForm
                productId={id}
                unit={product.unit}
                tracksBatches={product.tracksBatches}
                locations={locations.map((l) => ({
                  id: l.id,
                  code: l.code,
                  name: l.name,
                  allowsNegative: l.allowsNegative,
                }))}
                batches={batches.map((b) => ({
                  id: b.id,
                  lotCode: b.lotCode,
                  expiresOn: b.expiresOn,
                }))}
              />
            </div>
          </Card>

          <div className="rounded-[--radius-card] border border-border bg-surface-sunken px-4 py-3 text-xs leading-relaxed text-ink-muted">
            <p className="font-medium text-ink">Nothing here is editable.</p>
            <p className="mt-1">
              The ledger rejects <code className="num">UPDATE</code> and{" "}
              <code className="num">DELETE</code> at the database level. A mistake is fixed by
              posting a reversing entry, which leaves both the error and the correction visible.
            </p>
          </div>
        </div>
      </div>
    </div>
  );
}

/** Compact, sortable-looking timestamp. Locale-independent so columns align. */
function formatWhen(iso: string): string {
  const date = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(
    date.getHours(),
  )}:${pad(date.getMinutes())}`;
}

function humanReason(reason: string): string {
  return reason.charAt(0) + reason.slice(1).toLowerCase().replace(/_/g, " ");
}
