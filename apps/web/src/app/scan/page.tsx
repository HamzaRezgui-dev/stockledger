import { Card } from "@/components/ui";
import { Scanner } from "@/components/scanner";

export const metadata = { title: "Scan · StockLedger" };

export default function ScanPage() {
  return (
    <div className="mx-auto max-w-md space-y-4">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Scan</h1>
        <p className="mt-1 text-sm text-ink-muted">
          Scan a barcode to open that product&apos;s stock and ledger.
        </p>
      </div>

      <Card>
        <div className="p-4">
          <Scanner />
        </div>
      </Card>

      <p className="text-xs text-ink-subtle">
        Scanning uses the browser&apos;s built-in barcode detector — no library, no per-scan cost.
        Availability varies by browser, so manual entry is always there.
      </p>
    </div>
  );
}
