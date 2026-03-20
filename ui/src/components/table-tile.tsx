import type { TableRecord } from "@/lib/normalize";
import { cn, formatCurrency, formatElapsedAge, minutesSince } from "@/lib/utils";

type TableTone = "neutral" | "success" | "warning" | "danger";

export type TableTileLayout = {
  left: number;
  top: number;
  width: number;
  height: number;
  rotation: number;
  compact: boolean;
};

type TableTileProps = {
  table: TableRecord;
  layout: TableTileLayout;
  selected: boolean;
  onSelect: (tableId: string) => void;
};

const statusTone = {
  FREE: "neutral",
  OCCUPIED: "success",
  DIRTY: "warning",
  RESERVED: "danger",
} as const;

const occupiedScale: TableTone[] = ["neutral", "success", "warning", "danger"];

const tileClasses: Record<TableTone, string> = {
  neutral: "border-white/10 bg-slate-900/72 hover:border-white/20 hover:bg-slate-900/88",
  success:
    "border-emerald-400/20 bg-[linear-gradient(180deg,rgba(16,185,129,0.14),rgba(15,23,42,0.86))] hover:border-emerald-300/35 hover:bg-[linear-gradient(180deg,rgba(16,185,129,0.18),rgba(15,23,42,0.92))]",
  warning:
    "border-amber-400/22 bg-[linear-gradient(180deg,rgba(245,158,11,0.14),rgba(15,23,42,0.86))] hover:border-amber-300/35 hover:bg-[linear-gradient(180deg,rgba(245,158,11,0.18),rgba(15,23,42,0.92))]",
  danger:
    "border-rose-400/22 bg-[linear-gradient(180deg,rgba(244,63,94,0.14),rgba(15,23,42,0.86))] hover:border-rose-300/35 hover:bg-[linear-gradient(180deg,rgba(244,63,94,0.18),rgba(15,23,42,0.92))]",
};

function getTileTone(table: TableRecord): TableTone {
  if (table.status !== "OCCUPIED") {
    return statusTone[table.status] as TableTone;
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

export function TableTile({ table, layout, selected, onSelect }: TableTileProps) {
  const tone = getTileTone(table);
  const secondaryLabel =
    table.status === "OCCUPIED"
      ? `${table.knownChecksCount} chk`
      : table.status === "FREE"
        ? "Free"
        : table.status === "DIRTY"
          ? "Dirty"
          : "Reserved";
  const valueLabel =
    table.status === "OCCUPIED"
      ? [table.knownOpenTotalGrossCents !== null ? formatCurrency(table.knownOpenTotalGrossCents) : null, formatElapsedAge(table.openedAt)]
          .filter(Boolean)
          .join(" · ")
      : null;

  return (
    <button
      type="button"
      onClick={() => onSelect(table.id)}
      className={cn(
        "group absolute flex flex-col justify-between overflow-hidden border text-left shadow-glass transition-all duration-200 hover:-translate-y-0.5",
        layout.compact ? "rounded-[20px] px-2 py-1.5" : "rounded-[24px] px-2.5 py-2",
        tileClasses[tone],
        selected && "glow-outline ring-1 ring-primary/30",
      )}
      style={{
        left: layout.left,
        top: layout.top,
        width: layout.width,
        height: layout.height,
        transform: `rotate(${layout.rotation}deg)`,
        transformOrigin: "center center",
      }}
    >
      <div className="min-w-0">
        <p className="truncate text-[10px] uppercase tracking-[0.22em] text-muted-foreground">Table</p>
        <h3 className={cn("mt-1 truncate font-semibold tracking-[0.02em] text-foreground", layout.compact ? "text-sm" : "text-base")}>
          {table.label}
        </h3>
      </div>

      <div className="min-w-0">
        <p className={cn("truncate text-muted-foreground", layout.compact ? "text-[11px]" : "text-xs")}>{secondaryLabel}</p>
        {valueLabel ? (
          <p className={cn("mt-1 truncate text-foreground/90", layout.compact ? "text-[11px]" : "text-xs")}>{valueLabel}</p>
        ) : null}
      </div>
    </button>
  );
}
