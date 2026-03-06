import { ReceiptText, Store, TableProperties } from "lucide-react";

import { StatusChip } from "@/components/status-chip";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import type { CheckRecord, PartyRecord, TableRecord } from "@/lib/normalize";
import { formatCompactId, formatCurrency } from "@/lib/utils";

type ProductOrderAsideProps = {
  table: TableRecord | null;
  party: PartyRecord | null;
  checkIds: string[];
  activeCheckId: string | null;
  activeCheck: CheckRecord | null;
  busy: boolean;
  onOpenCheck: (partyId: string) => Promise<void>;
  onSelectCheck: (partyId: string, checkId: string) => Promise<void>;
};

export function ProductOrderAside({
  table,
  party,
  checkIds,
  activeCheckId,
  activeCheck,
  busy,
  onOpenCheck,
  onSelectCheck,
}: ProductOrderAsideProps) {
  if (!table) {
    return (
      <aside className="glass-card flex h-full min-h-[280px] flex-col justify-center p-3.5 xl:p-4">
        <div className="space-y-2.5 text-center">
          <p className="text-xs uppercase tracking-[0.28em] text-muted-foreground">Order target</p>
          <h2 className="text-lg font-semibold tracking-[0.04em] text-foreground">Select table first</h2>
          <p className="text-sm text-muted-foreground">
            Pick a table on the map, then use the product grid for one-tap ordering.
          </p>
        </div>
      </aside>
    );
  }

  const currentPartyId = table.currentPartyId ?? party?.id ?? null;

  return (
    <aside className="glass-card flex h-full min-h-0 flex-col overflow-hidden p-3.5 xl:p-4">
      <div className="mb-3 flex items-start justify-between gap-3">
        <div>
          <p className="text-xs uppercase tracking-[0.28em] text-muted-foreground">Order target</p>
          <h2 className="mt-1 text-xl font-semibold tracking-[0.04em] text-foreground">{table.label}</h2>
        </div>
        <StatusChip label={table.status} tone={table.status === "OCCUPIED" ? "info" : "warning"} />
      </div>

      <div className="flex flex-1 flex-col gap-2.5">
        <Card className="p-3">
          <CardHeader className="p-0">
            <CardTitle className="inline-flex items-center gap-2">
              <TableProperties className="h-4 w-4 text-primary" />
              Current table
            </CardTitle>
            <CardDescription className="text-xs">The product grid always adds to the selected active check.</CardDescription>
          </CardHeader>
          <CardContent className="mt-3 grid gap-2 p-0 text-sm">
            <div className="rounded-[22px] border border-white/[0.08] bg-white/[0.03] px-3 py-2.5">
              <p className="text-xs uppercase tracking-[0.18em] text-muted-foreground">Table id</p>
              <p className="mt-1.5 font-medium text-foreground">{formatCompactId(table.id)}</p>
            </div>
            <div className="rounded-[22px] border border-white/[0.08] bg-white/[0.03] px-3 py-2.5">
              <p className="text-xs uppercase tracking-[0.18em] text-muted-foreground">Party</p>
              <p className="mt-1.5 font-medium text-foreground">{formatCompactId(currentPartyId)}</p>
            </div>
          </CardContent>
        </Card>

        {!currentPartyId ? (
          <Card className="p-3">
            <CardHeader className="p-0">
              <CardTitle className="inline-flex items-center gap-2">
                <Store className="h-4 w-4 text-primary" />
                No open party
              </CardTitle>
              <CardDescription className="text-xs">Only occupied tables can receive orders from the product grid.</CardDescription>
            </CardHeader>
          </Card>
        ) : (
          <Card className="p-3">
            <CardHeader className="p-0">
              <CardTitle className="inline-flex items-center gap-2">
                <ReceiptText className="h-4 w-4 text-primary" />
                Active check
              </CardTitle>
              <CardDescription className="text-xs">Open a check once, then every product button tap adds one line item.</CardDescription>
            </CardHeader>
            <CardContent className="mt-3 space-y-2.5 p-0">
              {checkIds.length > 0 ? (
                <div className="flex flex-wrap gap-2">
                  {checkIds.map((checkId, index) => (
                    <Button
                      key={checkId}
                      variant={checkId === activeCheckId ? "default" : "secondary"}
                      size="sm"
                      disabled={busy}
                      onClick={() => void onSelectCheck(currentPartyId, checkId)}
                    >
                      Check {index + 1}
                    </Button>
                  ))}
                </div>
              ) : (
                <Button className="w-full" disabled={busy} onClick={() => void onOpenCheck(currentPartyId)}>
                  Open Check
                </Button>
              )}

              {activeCheck ? (
                <div className="grid gap-2 rounded-[22px] border border-white/[0.08] bg-white/[0.03] px-3 py-3 text-sm">
                  <div className="flex items-center justify-between">
                    <span className="text-muted-foreground">Status</span>
                    <StatusChip label={activeCheck.status} tone={activeCheck.status === "PAID" ? "success" : "info"} />
                  </div>
                  <div className="flex items-center justify-between">
                    <span className="text-muted-foreground">Gross</span>
                    <span className="font-medium text-foreground">{formatCurrency(activeCheck.totalCents)}</span>
                  </div>
                  <div className="flex items-center justify-between">
                    <span className="text-muted-foreground">Items</span>
                    <span className="font-medium text-foreground">{activeCheck.items.length}</span>
                  </div>
                </div>
              ) : (
                <div className="rounded-[22px] border border-dashed border-white/10 px-3 py-3 text-sm text-muted-foreground">
                  Select an open check to enable one-tap product ordering.
                </div>
              )}
            </CardContent>
          </Card>
        )}
      </div>
    </aside>
  );
}
