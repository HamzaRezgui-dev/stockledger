"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { lookupBarcodeAction } from "@/lib/actions";
import type { Product } from "@/lib/api";

/**
 * Barcode scanning with the device camera.
 *
 * Uses the browser's native `BarcodeDetector` — no library, no bundle cost, and
 * no per-scan fee. It is not available everywhere (notably Safari and Firefox),
 * so there is always a manual entry field: a scanner that silently fails on an
 * operator's phone is worse than one that never claimed to work.
 */

// Minimal shape of the API we use. Not in lib.dom.d.ts yet.
interface DetectedBarcode {
  rawValue: string;
  format: string;
}
interface BarcodeDetectorLike {
  detect(source: HTMLVideoElement): Promise<DetectedBarcode[]>;
}
type BarcodeDetectorCtor = new (options?: { formats?: string[] }) => BarcodeDetectorLike;

type Status =
  | { kind: "idle" }
  | { kind: "starting" }
  | { kind: "scanning" }
  | { kind: "looking-up"; barcode: string }
  | { kind: "found"; product: Product }
  | { kind: "unknown"; barcode: string }
  | { kind: "error"; message: string };

export function Scanner() {
  const router = useRouter();
  const videoRef = useRef<HTMLVideoElement>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const loopRef = useRef<number | null>(null);
  const [status, setStatus] = useState<Status>({ kind: "idle" });
  const [supported, setSupported] = useState<boolean | null>(null);

  useEffect(() => {
    setSupported("BarcodeDetector" in window);
  }, []);

  const stop = useCallback(() => {
    if (loopRef.current !== null) {
      window.clearTimeout(loopRef.current);
      loopRef.current = null;
    }
    // Releasing tracks matters: leaving them open keeps the camera light on,
    // which users reasonably read as the app spying on them.
    streamRef.current?.getTracks().forEach((track) => track.stop());
    streamRef.current = null;
  }, []);

  // Stop on unmount, so navigating away always releases the camera.
  useEffect(() => stop, [stop]);

  const handleBarcode = useCallback(
    async (barcode: string) => {
      stop();
      setStatus({ kind: "looking-up", barcode });
      const result = await lookupBarcodeAction(barcode);
      if (!result.ok) {
        setStatus({ kind: "error", message: result.message });
        return;
      }
      if (result.data) {
        setStatus({ kind: "found", product: result.data });
        router.push(`/products/${result.data.id}`);
      } else {
        setStatus({ kind: "unknown", barcode });
      }
    },
    [router, stop],
  );

  const start = useCallback(async () => {
    setStatus({ kind: "starting" });
    try {
      const stream = await navigator.mediaDevices.getUserMedia({
        // The rear camera on a phone, which is the one pointed at a shelf.
        video: { facingMode: { ideal: "environment" } },
      });
      streamRef.current = stream;
      const video = videoRef.current;
      if (!video) return;
      video.srcObject = stream;
      await video.play();

      const Detector = (window as unknown as { BarcodeDetector?: BarcodeDetectorCtor })
        .BarcodeDetector;
      if (!Detector) {
        setStatus({ kind: "error", message: "This browser cannot scan. Type the barcode instead." });
        stop();
        return;
      }

      const detector = new Detector({
        // The formats actually printed on retail and pharmacy packaging.
        formats: ["ean_13", "ean_8", "upc_a", "upc_e", "code_128", "code_39", "itf", "data_matrix"],
      });

      setStatus({ kind: "scanning" });

      const tick = async () => {
        if (!streamRef.current || !videoRef.current) return;
        try {
          const found = await detector.detect(videoRef.current);
          const first = found[0];
          if (first?.rawValue) {
            void handleBarcode(first.rawValue);
            return;
          }
        } catch {
          // A single failed frame is normal (motion blur, bad focus). Keep going
          // rather than tearing the session down.
        }
        // ~150ms is responsive to a human but leaves the CPU alone; a tight
        // rAF loop heats the phone and drains battery for no extra accuracy.
        loopRef.current = window.setTimeout(() => void tick(), 150);
      };
      void tick();
    } catch (error) {
      const message =
        error instanceof DOMException && error.name === "NotAllowedError"
          ? "Camera permission denied. Type the barcode instead."
          : "Could not start the camera. Type the barcode instead.";
      setStatus({ kind: "error", message });
      stop();
    }
  }, [handleBarcode, stop]);

  return (
    <div className="space-y-4">
      <div className="overflow-hidden rounded-[--radius-card] border border-border bg-surface-sunken">
        <div className="relative aspect-[4/3] w-full">
          <video
            ref={videoRef}
            playsInline
            muted
            className="h-full w-full object-cover"
            aria-label="Camera preview"
          />
          {status.kind === "scanning" && (
            <div className="pointer-events-none absolute inset-0 flex items-center justify-center">
              <div className="h-24 w-4/5 rounded-lg border-2 border-accent/80 shadow-[0_0_0_9999px_rgba(0,0,0,0.35)]" />
            </div>
          )}
          {(status.kind === "idle" || status.kind === "error") && (
            <div className="absolute inset-0 flex flex-col items-center justify-center gap-3 p-4 text-center">
              <p className="text-sm text-ink-muted">
                {status.kind === "error" ? status.message : "Point the camera at a barcode."}
              </p>
              {supported === false && (
                <p className="text-xs text-ink-subtle">
                  This browser has no barcode support — use manual entry below.
                </p>
              )}
              {supported !== false && (
                <button
                  type="button"
                  onClick={() => void start()}
                  className="rounded-md bg-accent px-4 py-2 text-sm font-medium text-white hover:bg-accent-hover"
                >
                  Start camera
                </button>
              )}
            </div>
          )}
        </div>
      </div>

      {status.kind === "starting" && <Note>Starting camera…</Note>}
      {status.kind === "looking-up" && (
        <Note>
          Looking up <span className="num">{status.barcode}</span>…
        </Note>
      )}
      {status.kind === "found" && (
        <p className="rounded-md bg-positive-soft px-3 py-2 text-xs text-positive">
          Found {status.product.name} — opening…
        </p>
      )}
      {status.kind === "unknown" && (
        <div className="rounded-md bg-warning-soft px-3 py-2 text-xs text-warning">
          <p className="font-medium">
            No product with barcode <span className="num">{status.barcode}</span>.
          </p>
          <p className="mt-1">
            Add it in{" "}
            <a href="/setup" className="underline">
              Setup
            </a>{" "}
            with this barcode, then scan again.
          </p>
        </div>
      )}

      <form
        onSubmit={(event) => {
          event.preventDefault();
          const input = event.currentTarget.elements.namedItem("barcode");
          if (input instanceof HTMLInputElement && input.value.trim()) {
            void handleBarcode(input.value.trim());
          }
        }}
        className="flex gap-2"
      >
        <input
          name="barcode"
          type="text"
          inputMode="numeric"
          placeholder="Or type a barcode"
          autoComplete="off"
          className="num w-full rounded-md border border-border bg-surface px-3 py-2 text-sm outline-none focus:border-accent"
        />
        <button
          type="submit"
          className="shrink-0 rounded-md border border-border-strong px-3 py-2 text-sm font-medium hover:bg-surface-sunken"
        >
          Look up
        </button>
      </form>
    </div>
  );
}

function Note({ children }: { children: React.ReactNode }) {
  return <p className="text-xs text-ink-muted">{children}</p>;
}
