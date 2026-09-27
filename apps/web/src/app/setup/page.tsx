import { ApiError, getLocations, getProducts } from "@/lib/api";
import { Badge, Card, EmptyState, ErrorPanel, ProductLink, Quantity } from "@/components/ui";
import { CreateLocationForm, CreateProductForm, CreateBatchForm } from "@/components/setup-forms";

export const metadata = { title: "Setup · StockLedger" };

/**
 * Reference data: locations, products and lots.
 *
 * Locations come first because a product is useless without somewhere to put it,
 * and a first-time user who creates a product first immediately hits a dead end
 * at the move form.
 */
export default async function SetupPage() {
  let products;
  let locations;
  try {
    [products, locations] = await Promise.all([getProducts(), getLocations()]);
  } catch (error) {
    const code = error instanceof ApiError ? error.code : "UNEXPECTED";
    const message = error instanceof Error ? error.message : "Failed to load setup data";
    return <ErrorPanel code={code} message={message} />;
  }

  const batchTracked = products.filter((p) => p.tracksBatches);

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Setup</h1>
        <p className="mt-1 text-sm text-ink-muted">
          Locations, products and lots. Reference data is editable — only the ledger is not.
        </p>
      </div>

      <div className="grid gap-4 lg:grid-cols-2">
        <Card title="Locations" subtitle={`${locations.length} defined`}>
          <div className="border-b border-border p-4">
            <CreateLocationForm />
          </div>
          {locations.length === 0 ? (
            <EmptyState title="No locations yet." hint="Create a warehouse to hold stock." />
          ) : (
            <ul className="divide-y divide-border">
              {locations.map((l) => (
                <li key={l.id} className="flex items-center justify-between gap-2 px-4 py-2.5">
                  <div>
                    <span className="num text-sm font-medium">{l.code}</span>
                    <span className="ml-2 text-xs text-ink-muted">{l.name}</span>
                  </div>
                  <div className="flex gap-1.5">
                    <Badge tone="neutral">{l.kind.toLowerCase()}</Badge>
                    {l.allowsNegative && <Badge tone="warning">may go negative</Badge>}
                  </div>
                </li>
              ))}
            </ul>
          )}
        </Card>

        <Card title="Products" subtitle={`${products.length} active`}>
          <div className="border-b border-border p-4">
            <CreateProductForm />
          </div>
          {products.length === 0 ? (
            <EmptyState title="No products yet." />
          ) : (
            <ul className="divide-y divide-border">
              {products.map((p) => (
                <li key={p.id} className="flex items-center justify-between gap-2 px-4 py-2.5">
                  <ProductLink id={p.id} sku={p.sku} name={p.name} />
                  <div className="flex shrink-0 items-center gap-1.5">
                    {p.tracksBatches && <Badge tone="accent">batches</Badge>}
                    {p.reorderPoint !== "0.000" && (
                      <span className="text-[11px] text-ink-subtle">
                        reorder <Quantity value={p.reorderPoint} />
                      </span>
                    )}
                    <Badge tone="neutral">{p.unit.toLowerCase()}</Badge>
                  </div>
                </li>
              ))}
            </ul>
          )}
        </Card>
      </div>

      <Card
        title="Lots"
        subtitle="Only for batch-tracked products. An expiry is a calendar date, not a timestamp."
      >
        <div className="p-4">
          {batchTracked.length === 0 ? (
            <p className="text-xs text-ink-subtle">
              No batch-tracked products yet. Tick &ldquo;tracks batches&rdquo; when creating one —
              it cannot be changed afterwards, because existing movements would be left referencing
              lots the product no longer admits to having.
            </p>
          ) : (
            <CreateBatchForm products={batchTracked.map((p) => ({ id: p.id, label: `${p.sku} — ${p.name}` }))} />
          )}
        </div>
      </Card>
    </div>
  );
}
