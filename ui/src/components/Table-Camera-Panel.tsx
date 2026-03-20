import { useEffect, useMemo, useState } from "react";
import { Camera, RefreshCcw } from "lucide-react";

import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

type TableCameraPanelProps = {
  cameraId?: string;
  title?: string;
  className?: string;
  refreshMs?: number;
};

type FeedState = "loading" | "live" | "offline";

function resolveBackendBaseUrl() {
  const rawBase =
    import.meta.env.VITE_EDGE_BASE_URL ??
    import.meta.env.VITE_API_BASE_URL ??
    window.location.origin;

  return String(rawBase).replace(/\/$/, "");
}

export function TableCameraPanel({
  cameraId = "cam1",
  title = "Camera",
  className,
  refreshMs = 1000,
}: TableCameraPanelProps) {
  const [cacheBust, setCacheBust] = useState(() => Date.now());
  const [feedState, setFeedState] = useState<FeedState>("loading");

  useEffect(() => {
    setFeedState("loading");
    setCacheBust(Date.now());
  }, [cameraId]);

  useEffect(() => {
    const intervalId = window.setInterval(() => {
      setCacheBust(Date.now());
    }, refreshMs);

    return () => {
      window.clearInterval(intervalId);
    };
  }, [refreshMs]);

  const imageUrl = useMemo(() => {
    const baseUrl = resolveBackendBaseUrl();
    return `${baseUrl}/vision/frame/latest?cameraId=${encodeURIComponent(cameraId)}&t=${cacheBust}`;
  }, [cacheBust, cameraId]);

  return (
    <section className={cn("rounded-[24px] border border-white/[0.08] bg-white/[0.03] p-3", className)}>
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="inline-flex items-center gap-2 text-base font-semibold text-foreground">
            <Camera className="h-4 w-4 text-primary" />
            {title}
          </div>
          <p className="mt-1 text-xs text-muted-foreground">Live frame preview for {cameraId}</p>
        </div>

        <div className="flex items-center gap-2">
          <span
            className={cn(
              "rounded-full border px-2.5 py-1 text-[11px] uppercase tracking-[0.16em]",
              feedState === "live"
                ? "border-emerald-400/20 bg-emerald-500/10 text-emerald-200"
                : feedState === "loading"
                  ? "border-sky-400/20 bg-sky-500/10 text-sky-200"
                  : "border-rose-400/20 bg-rose-500/10 text-rose-200",
            )}
          >
            {feedState === "live" ? "Live" : feedState === "loading" ? "Loading" : "Offline"}
          </span>

          <Button
            type="button"
            variant="secondary"
            size="sm"
            className="h-8 rounded-full px-3"
            onClick={() => {
              setFeedState("loading");
              setCacheBust(Date.now());
            }}
          >
            <RefreshCcw className="h-3.5 w-3.5" />
            Refresh
          </Button>
        </div>
      </div>

      <div className="mt-3 overflow-hidden rounded-[22px] border border-white/[0.08] bg-slate-950/70">
        <div className="relative aspect-video w-full">
          <img
            key={imageUrl}
            src={imageUrl}
            alt={`Camera preview ${cameraId}`}
            className="h-full w-full object-cover"
            loading="eager"
            onLoad={() => setFeedState("live")}
            onError={() => setFeedState("offline")}
          />

          {feedState !== "live" ? (
            <div className="pointer-events-none absolute inset-0 flex items-center justify-center bg-slate-950/55 px-4 text-center text-sm text-muted-foreground">
              {feedState === "loading"
                ? "Loading latest camera frame…"
                : "Camera frame unavailable. Check backend base URL and ingest state."}
            </div>
          ) : null}
        </div>
      </div>
    </section>
  );
}

export default TableCameraPanel;
