import { useEffect, useMemo, useRef, useState } from "react";
import { ChevronRight, Loader2 } from "lucide-react";

import { TableTile, type TableTileLayout } from "@/components/table-tile";
import { Button } from "@/components/ui/button";
import type { TableRecord } from "@/lib/normalize";
import { cn } from "@/lib/utils";

type TableMapCanvasProps = {
  tables: TableRecord[];
  selectedTableId: string | null;
  onSelectTable: (tableId: string) => void;
  loading: boolean;
};

type MapBounds = {
  minX: number;
  minY: number;
  maxX: number;
  maxY: number;
  width: number;
  height: number;
};

type TablePage = {
  index: number;
  tables: TableRecord[];
  bounds: MapBounds;
};

type Size = {
  width: number;
  height: number;
};

const MAP_FRAME_PADDING = 20;
const MIN_RENDERED_TABLE_WIDTH = 108;
const MIN_RENDERED_TABLE_HEIGHT = 74;
const FALLBACK_VIEWPORT_SIZE: Size = { width: 880, height: 560 };

function useElementSize<T extends HTMLElement>() {
  const ref = useRef<T | null>(null);
  const [size, setSize] = useState<Size>({ width: 0, height: 0 });

  useEffect(() => {
    const node = ref.current;
    if (!node) {
      return;
    }

    const observer = new ResizeObserver((entries) => {
      const entry = entries[0];
      if (!entry) {
        return;
      }
      const nextWidth = Math.round(entry.contentRect.width);
      const nextHeight = Math.round(entry.contentRect.height);
      setSize((current) =>
        current.width === nextWidth && current.height === nextHeight
          ? current
          : { width: nextWidth, height: nextHeight },
      );
    });

    observer.observe(node);
    return () => observer.disconnect();
  }, []);

  return { ref, size };
}

function sortTablesByGeometry(tables: TableRecord[]): TableRecord[] {
  return [...tables].sort((left, right) => {
    const topDelta = left.geometry.y - right.geometry.y;
    if (Math.abs(topDelta) > 1) {
      return topDelta;
    }
    const leftDelta = left.geometry.x - right.geometry.x;
    if (Math.abs(leftDelta) > 1) {
      return leftDelta;
    }
    return left.label.localeCompare(right.label);
  });
}

function rotatedBounds(table: TableRecord): MapBounds {
  const rotation = (table.geometry.rotation * Math.PI) / 180;
  const absCos = Math.abs(Math.cos(rotation));
  const absSin = Math.abs(Math.sin(rotation));
  const centerX = table.geometry.x + table.geometry.w / 2;
  const centerY = table.geometry.y + table.geometry.h / 2;
  const rotatedWidth = table.geometry.w * absCos + table.geometry.h * absSin;
  const rotatedHeight = table.geometry.w * absSin + table.geometry.h * absCos;
  const minX = centerX - rotatedWidth / 2;
  const minY = centerY - rotatedHeight / 2;
  const maxX = centerX + rotatedWidth / 2;
  const maxY = centerY + rotatedHeight / 2;

  return {
    minX,
    minY,
    maxX,
    maxY,
    width: maxX - minX,
    height: maxY - minY,
  };
}

function buildPageBounds(tables: TableRecord[]): MapBounds {
  if (tables.length === 0) {
    return {
      minX: 0,
      minY: 0,
      maxX: 0,
      maxY: 0,
      width: 1,
      height: 1,
    };
  }

  const boxes = tables.map(rotatedBounds);
  const minX = Math.min(...boxes.map((box) => box.minX));
  const minY = Math.min(...boxes.map((box) => box.minY));
  const maxX = Math.max(...boxes.map((box) => box.maxX));
  const maxY = Math.max(...boxes.map((box) => box.maxY));
  const averageWidth = tables.reduce((sum, table) => sum + table.geometry.w, 0) / tables.length;
  const averageHeight = tables.reduce((sum, table) => sum + table.geometry.h, 0) / tables.length;
  const padding = Math.max(18, Math.min(averageWidth, averageHeight) * 0.22);

  return {
    minX: minX - padding,
    minY: minY - padding,
    maxX: maxX + padding,
    maxY: maxY + padding,
    width: maxX - minX + padding * 2,
    height: maxY - minY + padding * 2,
  };
}

function groupTablesIntoRows(tables: TableRecord[]): TableRecord[][] {
  const sorted = sortTablesByGeometry(tables);
  const heights = sorted.map((table) => table.geometry.h).sort((left, right) => left - right);
  const medianHeight = heights[Math.floor(heights.length / 2)] ?? 90;
  const rowThreshold = Math.max(24, medianHeight * 0.65);
  const rows: Array<{ centerY: number; tables: TableRecord[] }> = [];

  for (const table of sorted) {
    const centerY = table.geometry.y + table.geometry.h / 2;
    const currentRow = rows.length > 0 ? rows[rows.length - 1] : undefined;
    if (!currentRow || Math.abs(centerY - currentRow.centerY) > rowThreshold) {
      rows.push({ centerY, tables: [table] });
      continue;
    }
    const nextTables = [...currentRow.tables, table].sort((left, right) => left.geometry.x - right.geometry.x);
    currentRow.tables = nextTables;
    currentRow.centerY =
      nextTables.reduce((sum, current) => sum + current.geometry.y + current.geometry.h / 2, 0) / nextTables.length;
  }

  return rows.map((row) => row.tables);
}

function resolveTileLayout(table: TableRecord, bounds: MapBounds, size: Size): TableTileLayout {
  const drawableWidth = Math.max(size.width - MAP_FRAME_PADDING * 2, 1);
  const drawableHeight = Math.max(size.height - MAP_FRAME_PADDING * 2, 1);
  const scale = Math.min(drawableWidth / bounds.width, drawableHeight / bounds.height);
  const contentWidth = bounds.width * scale;
  const contentHeight = bounds.height * scale;
  const offsetX = (size.width - contentWidth) / 2;
  const offsetY = (size.height - contentHeight) / 2;
  const width = table.geometry.w * scale;
  const height = table.geometry.h * scale;

  return {
    left: offsetX + (table.geometry.x - bounds.minX) * scale,
    top: offsetY + (table.geometry.y - bounds.minY) * scale,
    width,
    height,
    rotation: table.geometry.rotation,
    compact: width < 132 || height < 96,
  };
}

function resolvePageScale(bounds: MapBounds, size: Size): number {
  const drawableWidth = Math.max(size.width - MAP_FRAME_PADDING * 2, 1);
  const drawableHeight = Math.max(size.height - MAP_FRAME_PADDING * 2, 1);
  return Math.min(drawableWidth / bounds.width, drawableHeight / bounds.height);
}

function pageKeepsTablesUsable(tables: TableRecord[], size: Size): boolean {
  if (tables.length === 0) {
    return true;
  }

  const bounds = buildPageBounds(tables);
  const scale = resolvePageScale(bounds, size);
  return tables.every((table) => {
    const renderedWidth = table.geometry.w * scale;
    const renderedHeight = table.geometry.h * scale;
    return renderedWidth >= MIN_RENDERED_TABLE_WIDTH && renderedHeight >= MIN_RENDERED_TABLE_HEIGHT;
  });
}

function splitRowByViewport(row: TableRecord[], size: Size): TableRecord[][] {
  const pages: TableRecord[][] = [];
  let currentPage: TableRecord[] = [];

  for (const table of row) {
    const nextPage = [...currentPage, table];
    if (currentPage.length > 0 && !pageKeepsTablesUsable(nextPage, size)) {
      pages.push(currentPage);
      currentPage = [table];
      continue;
    }
    currentPage = nextPage;
  }

  if (currentPage.length > 0) {
    pages.push(currentPage);
  }

  return pages;
}

function buildTablePages(tables: TableRecord[], size: Size): TablePage[] {
  const pages: TableRecord[][] = [];
  let currentPage: TableRecord[] = [];

  for (const row of groupTablesIntoRows(tables)) {
    const candidatePage = [...currentPage, ...row];
    if (currentPage.length === 0 || pageKeepsTablesUsable(candidatePage, size)) {
      currentPage = candidatePage;
      continue;
    }

    if (currentPage.length > 0) {
      pages.push(currentPage);
      currentPage = [];
    }

    if (pageKeepsTablesUsable(row, size)) {
      currentPage = [...row];
      continue;
    }

    const splitPages = splitRowByViewport(row, size);
    pages.push(...splitPages.slice(0, -1));
    currentPage = splitPages.length > 0 ? splitPages[splitPages.length - 1] : [];
  }

  if (currentPage.length > 0) {
    pages.push(currentPage);
  }

  if (pages.length === 0) {
    pages.push([]);
  }

  return pages.map((pageTables, index) => ({
    index,
    tables: pageTables,
    bounds: buildPageBounds(pageTables),
  }));
}

export function TableMapCanvas({ tables, selectedTableId, onSelectTable, loading }: TableMapCanvasProps) {
  const { ref: viewportRef, size } = useElementSize<HTMLDivElement>();
  const pagingViewport = size.width > 0 && size.height > 0 ? size : FALLBACK_VIEWPORT_SIZE;
  const pages = useMemo(
    () => buildTablePages(tables, pagingViewport),
    [pagingViewport.height, pagingViewport.width, tables],
  );
  const [activePageIndex, setActivePageIndex] = useState(0);
  const previousSelectedTableId = useRef<string | null>(null);

  useEffect(() => {
    setActivePageIndex((current) => Math.min(current, Math.max(pages.length - 1, 0)));
  }, [pages.length]);

  useEffect(() => {
    if (!selectedTableId || previousSelectedTableId.current === selectedTableId) {
      previousSelectedTableId.current = selectedTableId;
      return;
    }

    previousSelectedTableId.current = selectedTableId;
    const selectedPageIndex = pages.findIndex((page) => page.tables.some((table) => table.id === selectedTableId));
    if (selectedPageIndex >= 0) {
      setActivePageIndex(selectedPageIndex);
    }
  }, [pages, selectedTableId]);

  const activePage = pages[activePageIndex] ?? pages[0];
  const activePageLabel = activePage ? `Page ${activePage.index + 1}` : "Page";

  return (
    <div className="flex h-full min-h-0 gap-3">
      <div className="glass-card flex min-h-0 flex-1 flex-col overflow-hidden rounded-[30px] border-dashed border-white/[0.08] bg-slate-950/25 p-3">
        <div className="mb-3 flex items-center justify-between gap-2">
          <div className="flex items-center gap-2">
            <p className="text-[11px] uppercase tracking-[0.24em] text-muted-foreground">{activePageLabel}</p>
            <span className="rounded-full border border-white/10 bg-white/[0.03] px-2.5 py-1 text-[11px] text-muted-foreground">
              {activePage?.tables.length ?? 0} tables
            </span>
          </div>
        </div>

        <div
          ref={viewportRef}
          className="relative min-h-0 flex-1 overflow-hidden rounded-[26px] border border-white/[0.06] bg-[radial-gradient(circle_at_18%_18%,rgba(73,128,161,0.14),transparent_30%),linear-gradient(180deg,rgba(11,21,38,0.84),rgba(6,10,18,0.98))] shadow-[inset_0_1px_0_rgba(255,255,255,0.04)]"
        >
          <div className="pointer-events-none absolute inset-0 bg-[linear-gradient(90deg,rgba(255,255,255,0.02)_1px,transparent_1px),linear-gradient(180deg,rgba(255,255,255,0.02)_1px,transparent_1px)] bg-[size:72px_72px] opacity-30" />

          {loading ? (
            <div className="relative z-10 flex h-full flex-col items-center justify-center gap-3 text-sm text-muted-foreground">
              <Loader2 className="h-5 w-5 animate-spin text-primary" />
              Loading floor state
            </div>
          ) : activePage?.tables.length ? (
            <div className="relative z-10 h-full w-full">
              {activePage.tables.map((table) => (
                <TableTile
                  key={table.id}
                  table={table}
                  layout={resolveTileLayout(table, activePage.bounds, pagingViewport)}
                  selected={table.id === selectedTableId}
                  onSelect={onSelectTable}
                />
              ))}
            </div>
          ) : (
            <div className="relative z-10 flex h-full items-center justify-center rounded-[24px] border border-dashed border-white/10 text-sm text-muted-foreground">
              No tables available on this floor.
            </div>
          )}
        </div>
      </div>

      {pages.length > 1 ? (
        <div className="flex w-14 shrink-0 flex-col items-center justify-center gap-2">
          {pages.map((page) => (
            <Button
              key={page.index}
              type="button"
              variant={page.index === activePageIndex ? "default" : "secondary"}
              className={cn("h-12 w-full rounded-[18px] px-0 text-sm font-semibold", page.index === activePageIndex && "shadow-glass")}
              onClick={() => {
                setActivePageIndex(page.index);
                if (page.tables[0]) {
                  onSelectTable(page.tables[0].id);
                }
              }}
            >
              {page.index + 1}
            </Button>
          ))}

          <div className="flex h-12 w-full items-center justify-center rounded-[18px] border border-white/10 bg-white/[0.03] text-muted-foreground">
            <ChevronRight className="h-4 w-4" />
          </div>
        </div>
      ) : null}
    </div>
  );
}
