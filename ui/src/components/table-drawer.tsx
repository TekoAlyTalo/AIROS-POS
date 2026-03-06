import { ReceiptText, Users, UtensilsCrossed } from "lucide-react";

import { CheckPanel } from "@/components/check-panel";
import { PartyForm } from "@/components/party-form";
import { StatusChip } from "@/components/status-chip";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import type { CheckRecord, PartyRecord, SessionRecord, TableRecord } from "@/lib/normalize";
import { formatCompactId } from "@/lib/utils";

type TableDrawerProps = {
  table: TableRecord | null;
  party: PartyRecord | null;
  sessionInfo: SessionRecord | null;
  checkIds: string[];
  activeCheckId: string | null;
  activeCheck: CheckRecord | null;
  busy: boolean;
  onOpenParty: (input: { table: TableRecord; guestCount: number; note: string }) => Promise<void>;
  onOpenCheck: (partyId: string) => Promise<void>;
  onSelectCheck: (partyId: string, checkId: string) => Promise<void>;
  onRefreshCheck: (checkId: string) => Promise<CheckRecord>;
  onAddItems: (
    checkId: string,
    body: {
      items: Array<{
        product_id?: string;
        name_snapshot: string;
        qty: number;
        pricing_model: "SINGLE_VAT" | "COMPOSITE_VAT";
        note?: string;
        components: Array<{
          name_snapshot: string;
          vat_rate_snapshot: number;
          unit_gross_cents_snapshot: number;
          qty: number;
        }>;
      }>;
    },
  ) => Promise<void>;
  onRecordPayment: (input: {
    checkId: string;
    method: "CASH" | "CARD_EXTERNAL";
    amountCents: number;
    externalRef?: string;
  }) => Promise<void>;
  onFinalize: (input: { checkId: string; paymentMethod: "CASH" | "CARD_EXTERNAL" }) => Promise<void>;
};

const statusTone = {
  FREE: "success",
  OCCUPIED: "info",
  DIRTY: "warning",
  RESERVED: "danger",
} as const;

export function TableDrawer({
  table,
  party,
  sessionInfo,
  checkIds,
  activeCheckId,
  activeCheck,
  busy,
  onOpenParty,
  onOpenCheck,
  onSelectCheck,
  onRefreshCheck,
  onAddItems,
  onRecordPayment,
  onFinalize,
}: TableDrawerProps) {
  if (!table) {
    return (
      <aside className="glass-card flex h-full min-h-[620px] flex-col justify-center p-5">
        <div className="space-y-3 text-center">
          <p className="text-xs uppercase tracking-[0.28em] text-muted-foreground">Table detail</p>
          <h2 className="text-xl font-semibold tracking-[0.04em] text-foreground">Select a table</h2>
          <p className="text-sm text-muted-foreground">
            Pick any table on the map to open a party, manage checks, add items, or finalize payment.
          </p>
        </div>
      </aside>
    );
  }

  const currentPartyId = party?.id ?? table.currentPartyId;

  return (
    <aside className="glass-card flex h-full min-h-[620px] flex-col overflow-hidden p-5">
      <div className="mb-5 flex items-start justify-between gap-3">
        <div>
          <p className="text-xs uppercase tracking-[0.28em] text-muted-foreground">Table detail</p>
          <h2 className="mt-2 text-2xl font-semibold tracking-[0.04em] text-foreground">{table.label}</h2>
        </div>
        <StatusChip label={table.status} tone={statusTone[table.status]} />
      </div>

      <div className="scrollbar-thin flex-1 space-y-4 overflow-y-auto pr-1">
        <Card className="p-4">
          <CardHeader className="p-0">
            <CardTitle>Table snapshot</CardTitle>
            <CardDescription>Geometry and live seating state from the Edge service.</CardDescription>
          </CardHeader>
          <CardContent className="mt-4 grid gap-3 p-0 text-sm">
            <div className="grid grid-cols-2 gap-3">
              <div className="rounded-3xl border border-white/[0.08] bg-white/[0.03] p-3">
                <p className="text-xs uppercase tracking-[0.18em] text-muted-foreground">Table id</p>
                <p className="mt-2 font-medium text-foreground">{formatCompactId(table.id)}</p>
              </div>
              <div className="rounded-3xl border border-white/[0.08] bg-white/[0.03] p-3">
                <p className="text-xs uppercase tracking-[0.18em] text-muted-foreground">Table group</p>
                <p className="mt-2 font-medium text-foreground">{formatCompactId(table.currentTableGroupId)}</p>
              </div>
            </div>
            <div className="rounded-3xl border border-white/[0.08] bg-white/[0.03] p-3">
              <p className="text-xs uppercase tracking-[0.18em] text-muted-foreground">Geometry</p>
              <p className="mt-2 text-foreground">
                x {table.geometry.x} · y {table.geometry.y} · w {table.geometry.w} · h {table.geometry.h} · rot{" "}
                {table.geometry.rotation}
              </p>
            </div>
            <div className="flex flex-wrap gap-2">
              <StatusChip
                label={sessionInfo ? `Session ${sessionInfo.status}` : "No Open Session"}
                tone={sessionInfo ? "info" : "warning"}
                compactId={sessionInfo?.id}
              />
              {currentPartyId ? <StatusChip label="Party Linked" tone="info" compactId={currentPartyId} /> : null}
            </div>
          </CardContent>
        </Card>

        {table.status === "FREE" ? (
          <Card className="p-4">
            <CardHeader className="p-0">
              <CardTitle className="inline-flex items-center gap-2">
                <Users className="h-4 w-4 text-primary" />
                Open Party
              </CardTitle>
              <CardDescription>
                Create a new seating for this table. The backend assigns a fresh table group for every party.
              </CardDescription>
            </CardHeader>
            <CardContent className="mt-4 p-0">
              <PartyForm table={table} busy={busy} onSubmit={onOpenParty} />
            </CardContent>
          </Card>
        ) : null}

        {table.status === "OCCUPIED" ? (
          <>
            <Card className="p-4">
              <CardHeader className="p-0">
                <CardTitle className="inline-flex items-center gap-2">
                  <UtensilsCrossed className="h-4 w-4 text-primary" />
                  Party Summary
                </CardTitle>
                <CardDescription>
                  Current seating linked to this table. Party detail is limited to the available Edge endpoints.
                </CardDescription>
              </CardHeader>
              <CardContent className="mt-4 grid gap-3 p-0 text-sm">
                <div className="grid grid-cols-2 gap-3">
                  <div className="rounded-3xl border border-white/[0.08] bg-white/[0.03] p-3">
                    <p className="text-xs uppercase tracking-[0.18em] text-muted-foreground">Party</p>
                    <p className="mt-2 font-medium text-foreground">{formatCompactId(currentPartyId)}</p>
                  </div>
                  <div className="rounded-3xl border border-white/[0.08] bg-white/[0.03] p-3">
                    <p className="text-xs uppercase tracking-[0.18em] text-muted-foreground">Guests</p>
                    <p className="mt-2 font-medium text-foreground">{party?.guestCount ?? "Not loaded"}</p>
                  </div>
                </div>
                <div className="grid grid-cols-2 gap-3">
                  <div className="rounded-3xl border border-white/[0.08] bg-white/[0.03] p-3">
                    <p className="text-xs uppercase tracking-[0.18em] text-muted-foreground">Party status</p>
                    <p className="mt-2 font-medium text-foreground">{party?.status ?? "OPEN"}</p>
                  </div>
                  <div className="rounded-3xl border border-white/[0.08] bg-white/[0.03] p-3">
                    <p className="text-xs uppercase tracking-[0.18em] text-muted-foreground">Primary table</p>
                    <p className="mt-2 font-medium text-foreground">{formatCompactId(party?.primaryTableId ?? table.id)}</p>
                  </div>
                </div>
                {party?.note ? (
                  <div className="rounded-3xl border border-white/[0.08] bg-white/[0.03] p-3 text-foreground">
                    {party.note}
                  </div>
                ) : (
                  <div className="rounded-3xl border border-dashed border-white/10 p-3 text-muted-foreground">
                    No party note available.
                  </div>
                )}
                <Button
                  className="w-full"
                  disabled={busy || !currentPartyId}
                  onClick={() => {
                    if (currentPartyId) {
                      void onOpenCheck(currentPartyId);
                    }
                  }}
                >
                  <ReceiptText className="h-4 w-4" />
                  Open Check
                </Button>
              </CardContent>
            </Card>

            {currentPartyId ? (
              <CheckPanel
                partyId={currentPartyId}
                checkIds={checkIds}
                activeCheckId={activeCheckId}
                activeCheck={activeCheck}
                sessionInfo={sessionInfo}
                busy={busy}
                onSelectCheck={onSelectCheck}
                onRefreshCheck={onRefreshCheck}
                onAddItems={onAddItems}
                onRecordPayment={onRecordPayment}
                onFinalize={onFinalize}
              />
            ) : (
              <div className="rounded-3xl border border-dashed border-white/10 p-4 text-sm text-muted-foreground">
                This table is occupied but the current party id is not available from the API payload.
              </div>
            )}
          </>
        ) : null}

        {table.status === "DIRTY" || table.status === "RESERVED" ? (
          <Card className="p-4">
            <CardHeader className="p-0">
              <CardTitle>{table.status === "DIRTY" ? "Table awaiting reset" : "Reserved table"}</CardTitle>
              <CardDescription>
                This state is read-only in the current Staff UI. Use the backend staff flows to seat or release it.
              </CardDescription>
            </CardHeader>
            <CardContent className="mt-4 rounded-3xl border border-white/[0.08] bg-white/[0.03] p-4 text-sm text-muted-foreground">
              {table.status === "DIRTY"
                ? "The table is marked dirty and should be cleaned before opening a new party."
                : "The table is reserved. Open-party actions stay disabled until service marks it available."}
            </CardContent>
          </Card>
        ) : null}
      </div>
    </aside>
  );
}
