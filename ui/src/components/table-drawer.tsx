import { ReceiptText, Users } from "lucide-react";

import { CheckPanel } from "@/components/check-panel";
import { TableCameraPanel } from "@/components/table-camera-panel";
import { PartyForm } from "@/components/party-form";
import { StatusChip } from "@/components/status-chip";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import type { CheckRecord, PartyRecord, SessionRecord, TableRecord } from "@/lib/normalize";
import { formatCompactId, formatCurrency } from "@/lib/utils";

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
  onRecordPayment,
  onFinalize,
}: TableDrawerProps) {
  if (!table) {
    return (
      <aside className="glass-card flex h-full min-h-[360px] flex-col justify-center p-4 lg:min-h-0 lg:p-3.5">
        <div className="space-y-2 text-center">
          <p className="text-[11px] uppercase tracking-[0.24em] text-muted-foreground">Table</p>
          <h2 className="text-xl font-semibold tracking-[0.03em] text-foreground">Select a table</h2>
          <p className="text-sm text-muted-foreground">Open the target on the map, then continue with checks or products.</p>
        </div>
      </aside>
    );
  }

  const currentPartyId = party?.id ?? table.currentPartyId;

  return (
    <aside className="glass-card flex h-full min-h-[620px] flex-col overflow-hidden p-3 lg:min-h-0 lg:p-3.5">
      <div className="border-b border-white/8 pb-3">
        <div className="flex items-start justify-between gap-3">
          <div className="min-w-0">
            <p className="text-[11px] uppercase tracking-[0.24em] text-muted-foreground">Selected table</p>
            <h2 className="mt-1 truncate text-2xl font-semibold tracking-[0.03em] text-foreground">{table.label}</h2>
          </div>
          <StatusChip label={table.status} tone={statusTone[table.status]} />
        </div>

        <div className="mt-3 grid grid-cols-2 gap-2">
          <div className="rounded-[20px] border border-white/[0.08] bg-white/[0.03] px-3 py-2.5">
            <p className="text-[10px] uppercase tracking-[0.18em] text-muted-foreground">Party</p>
            <p className="mt-1 text-sm font-medium text-foreground">{currentPartyId ? formatCompactId(currentPartyId) : "None"}</p>
          </div>
          <div className="rounded-[20px] border border-white/[0.08] bg-white/[0.03] px-3 py-2.5">
            <p className="text-[10px] uppercase tracking-[0.18em] text-muted-foreground">Checks</p>
            <p className="mt-1 text-sm font-medium text-foreground">{table.knownChecksCount}</p>
          </div>
          <div className="rounded-[20px] border border-white/[0.08] bg-white/[0.03] px-3 py-2.5">
            <p className="text-[10px] uppercase tracking-[0.18em] text-muted-foreground">Open gross</p>
            <p className="mt-1 text-sm font-medium text-foreground">
              {table.knownOpenTotalGrossCents === null ? "--" : formatCurrency(table.knownOpenTotalGrossCents)}
            </p>
          </div>
          <div className="rounded-[20px] border border-white/[0.08] bg-white/[0.03] px-3 py-2.5">
            <p className="text-[10px] uppercase tracking-[0.18em] text-muted-foreground">Session</p>
            <p className="mt-1 text-sm font-medium text-foreground">{sessionInfo?.status ?? "Closed"}</p>
          </div>
        </div>
      </div>

      <div className="mt-3 flex-1 space-y-3 overflow-y-auto pr-1">
        <TableCameraPanel cameraId="cam1" title="Table camera" />

        {table.status === "FREE" ? (
          <Card className="p-3.5">
            <CardHeader className="p-0">
              <CardTitle className="inline-flex items-center gap-2 text-base">
                <Users className="h-4 w-4 text-primary" />
                Open party
              </CardTitle>
            </CardHeader>
            <CardContent className="mt-3 p-0">
              <PartyForm table={table} busy={busy} onSubmit={onOpenParty} />
            </CardContent>
          </Card>
        ) : null}

        {table.status === "OCCUPIED" ? (
          <>
            <Card className="p-3.5">
              <CardHeader className="p-0">
                <div className="flex items-start justify-between gap-2">
                  <CardTitle className="inline-flex items-center gap-2 text-base">
                    <ReceiptText className="h-4 w-4 text-primary" />
                    Party
                  </CardTitle>
                  <Button
                    size="sm"
                    className="h-9 rounded-full px-3"
                    disabled={busy || !currentPartyId}
                    onClick={() => {
                      if (currentPartyId) {
                        void onOpenCheck(currentPartyId);
                      }
                    }}
                  >
                    Open check
                  </Button>
                </div>
              </CardHeader>
              <CardContent className="mt-3 space-y-2 p-0 text-sm">
                <div className="grid grid-cols-2 gap-2">
                  <div className="rounded-[20px] border border-white/[0.08] bg-white/[0.03] px-3 py-2.5">
                    <p className="text-[10px] uppercase tracking-[0.18em] text-muted-foreground">Guests</p>
                    <p className="mt-1 font-medium text-foreground">{party?.guestCount ?? "--"}</p>
                  </div>
                  <div className="rounded-[20px] border border-white/[0.08] bg-white/[0.03] px-3 py-2.5">
                    <p className="text-[10px] uppercase tracking-[0.18em] text-muted-foreground">Status</p>
                    <p className="mt-1 font-medium text-foreground">{party?.status ?? "OPEN"}</p>
                  </div>
                </div>

                {party?.note ? (
                  <div className="rounded-[20px] border border-white/[0.08] bg-white/[0.03] px-3 py-2.5 text-foreground">
                    {party.note}
                  </div>
                ) : null}
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
                onRecordPayment={onRecordPayment}
                onFinalize={onFinalize}
              />
            ) : (
              <div className="rounded-[24px] border border-dashed border-white/10 px-3 py-3 text-sm text-muted-foreground">
                This occupied table has no current party id in the API payload.
              </div>
            )}
          </>
        ) : null}

        {table.status === "DIRTY" || table.status === "RESERVED" ? (
          <Card className="p-3.5">
            <CardHeader className="p-0">
              <CardTitle className="text-base">{table.status === "DIRTY" ? "Awaiting reset" : "Reserved table"}</CardTitle>
            </CardHeader>
            <CardContent className="mt-3 rounded-[20px] border border-white/[0.08] bg-white/[0.03] px-3 py-2.5 text-sm text-muted-foreground">
              {table.status === "DIRTY"
                ? "Clean the table before opening a new party."
                : "Open-party actions stay disabled until the table is released."}
            </CardContent>
          </Card>
        ) : null}
      </div>
    </aside>
  );
}
