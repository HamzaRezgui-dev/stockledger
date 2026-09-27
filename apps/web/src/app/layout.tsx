import type { Metadata } from "next";
import Link from "next/link";
import "./globals.css";

export const metadata: Metadata = {
  title: "StockLedger",
  description: "Append-only inventory. Every number explains itself.",
};

const NAV = [
  { href: "/", label: "Stock" },
  { href: "/scan", label: "Scan" },
  { href: "/setup", label: "Setup" },
] as const;

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body className="min-h-dvh antialiased">
        <header className="sticky top-0 z-10 border-b border-border bg-surface-raised/90 backdrop-blur">
          <div className="mx-auto flex max-w-6xl flex-wrap items-center gap-x-6 gap-y-2 px-4 py-3">
            <Link href="/" className="flex items-baseline gap-2">
              <span className="text-base font-semibold tracking-tight">StockLedger</span>
              <span className="hidden text-xs text-ink-subtle sm:inline">append-only inventory</span>
            </Link>
            <nav className="flex gap-1 text-sm">
              {NAV.map((item) => (
                <Link
                  key={item.href}
                  href={item.href}
                  className="rounded-md px-3 py-1.5 text-ink-muted transition-colors hover:bg-surface-sunken hover:text-ink"
                >
                  {item.label}
                </Link>
              ))}
            </nav>
          </div>
        </header>

        <main className="mx-auto max-w-6xl px-4 py-6">{children}</main>

        <footer className="mx-auto max-w-6xl px-4 py-8 text-xs text-ink-subtle">
          Stock is derived from an append-only ledger — never edited. Corrections are reversing
          entries.
        </footer>
      </body>
    </html>
  );
}
