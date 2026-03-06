import { BadgeEuro, Clock3, Sparkle } from "lucide-react";

import { StatusChip } from "@/components/status-chip";
import type { TableRecord } from "@/lib/normalize";
import { cn, formatCurrency, formatElapsedAge, minutesSince } from "@/lib/utils";

type TableTileProps = {
  table: TableRecord;
  selected: boolean;
  onSelect: (tableId: string) => void;
};

type TileTone = "neutral" | "success" | "warning" | "danger";

const statusTone = {
  FREE: "neutral",
  OCCUPIED: "info",
  DIRTY: "warning",
  RESERVED: "danger",
} as const;

const occupiedScale: TileTone[] = ["neutral", "success", "warning", "danger"];

const tileClasses: Record<TileTone, string> = {
  neutral: "border-white/10 bg-slate-900/70 hover:border-white/20 hover:bg-slate-900/85",
  success:
    "border-emerald-400/20 bg-[linear-gradient(180deg,rgba(16,185,129,0.12),rgba(15,23,42,0.84))] hover:border-emerald-300/35 hover:bg-[linear-gradient(180deg,rgba(16,185,129,0.16),rgba(15,23,42,0.9))]",
  warning:
    "border-amber-400/22 bg-[linear-gradient(180deg,rgba(245,158,11,0.12),rgba(15,23,42,0.84))] hover:border-amber-300/35 hover:bg-[linear-gradient(180deg,rgba(245,158,11,0.17),rgba(15,23,42,0.9))]",
  danger:
    "border-rose-400/22 bg-[linear-gradient(180deg,rgba(244,63,94,0.12),rgba(15,23,42,0.84))] hover:border-rose-300/35 hover:bg-[linear-gradient(180deg,rgba(244,63,94,0.17),rgba(15,23,42,0.9))]",
};

function getTileTone(table: TableRecord): TileTone {
  if (table.status === "FREE") {
    return "neutral";
  }
  if (table.status === "DIRTY") {
    return "warning";
  }
  if (table.status === "RESERVED") {
    return "danger";
  }

  const openTotal = table.knownOpenTotalGrossCents ?? 0;
  let severityIndex = 0;
  if (openTotal > 10000) {
    severityIndex = 3;
  } else if (openTotal > 3000) {
    severityIndex = 2;
  } else if (openTotal > 0) {
    severityIndex = 1;
  }

  const ageMinutes = minutesSince(table.openedAt);
  if (ageMinutes !== null && ageMinutes > 90) {
    severityIndex = Math.min(severityIndex + 1, occupiedScale.length - 1);
  }

  return occupiedScale[severityIndex];
}

export function TableTile({ table, selected, onSelect }: TableTileProps) {
  const tone = table.status === "OCCUPIED" ? getTileTone(table) : (statusTone[table.status] as TileTone);
  const totalLabel =
    table.knownOpenTotalGrossCents === null ? "--" : formatCurrency(table.knownOpenTotalGrossCents);

  return (
    <button
      type="button"
      onClick={() => onSelect(table.id)}
      className={cn(
        "group absolute flex flex-col justify-between rounded-3xl p-4 text-left shadow-glass transition-all duration-200 hover:-translate-y-0.5",
        tileClasses[tone],
        selected && "glow-outline ring-1 ring-primary/25",
      )}
      style={{
        left: table.geometry.x,
        top: table.geometry.y,
        width: table.geometry.w,
        height: table.geometry.h,
        transform: `rotate(${table.geometry.rotation}deg)`,
      }}
    >
      <div className="flex items-start justify-between gap-2">
        <div>
          <p className="text-[10px] uppercase tracking-[0.26em] text-muted-foreground">Table</p>
          <h3 className="mt-1 text-lg font-semibold tracking-[0.04em] text-foreground">{table.label}</h3>
        </div>
        <StatusChip label={table.status} tone={tone} />
      </div>

      <div className="space-y-2.5">
        <div className="flex items-center justify-between text-xs text-muted-foreground">
          <span className="inline-flex items-center gap-1">
            <Sparkle className="h-3 w-3 text-primary/80" />
            Checks
          </span>
          <span className="text-foreground">{table.knownChecksCount}</span>
        </div>
        <div className="flex items-center justify-between text-xs text-muted-foreground">
          <span className="inline-flex items-center gap-1">
            <BadgeEuro className="h-3 w-3 text-accent/80" />
            Open gross
          </span>
          <span className="text-foreground">{totalLabel}</span>
        </div>
        <div className="flex items-center justify-between text-xs text-muted-foreground">
          <span className="inline-flex items-center gap-1">
            <Clock3 className="h-3 w-3 text-foreground/70" />
            Age
          </span>
          <span className="text-foreground">{formatElapsedAge(table.openedAt)}</span>
        </div>
      </div>
    </button>
  );
}
