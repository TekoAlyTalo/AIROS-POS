import { Loader2 } from "lucide-react";

import { TableTile } from "@/components/table-tile";
import type { TableRecord } from "@/lib/normalize";

type TableMapCanvasProps = {
  tables: TableRecord[];
  selectedTableId: string | null;
  onSelectTable: (tableId: string) => void;
  loading: boolean;
};

export function TableMapCanvas({ tables, selectedTableId, onSelectTable, loading }: TableMapCanvasProps) {
  const width = Math.max(...tables.map((table) => table.geometry.x + table.geometry.w), 680) + 60;
  const height = Math.max(...tables.map((table) => table.geometry.y + table.geometry.h), 460) + 60;

  return (
    <div className="glass-card scrollbar-thin flex h-full min-h-[520px] items-center justify-center overflow-auto rounded-[2rem] border-dashed border-white/[0.08] bg-slate-950/25 p-4">
      {loading ? (
        <div className="flex flex-col items-center gap-3 text-sm text-muted-foreground">
          <Loader2 className="h-5 w-5 animate-spin text-primary" />
          Loading floor state
        </div>
      ) : (
        <div className="relative rounded-[2rem] border border-white/[0.06] bg-[radial-gradient(circle_at_20%_20%,rgba(73,128,161,0.14),transparent_28%),linear-gradient(180deg,rgba(11,21,38,0.78),rgba(6,10,18,0.96))] p-6 shadow-[inset_0_1px_0_rgba(255,255,255,0.04)]">
          <div
            className="relative rounded-[1.75rem] border border-white/[0.06] bg-slate-950/35"
            style={{ width, height }}
          >
            {tables.map((table) => (
              <TableTile
                key={table.id}
                table={table}
                selected={table.id === selectedTableId}
                onSelect={onSelectTable}
              />
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
