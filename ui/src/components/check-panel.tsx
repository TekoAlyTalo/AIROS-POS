import { useEffect } from "react";
import { RefreshCcw } from "lucide-react";

import { PaymentDialog } from "@/components/payment-dialog";
import { StatusChip } from "@/components/status-chip";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import type { CheckRecord, SessionRecord } from "@/lib/normalize";
import { formatCurrency } from "@/lib/utils";

type CheckPanelProps = {
  partyId: string;
  checkIds: string[];
  activeCheckId: string | null;
  activeCheck: CheckRecord | null;
  sessionInfo: SessionRecord | null;
  busy: boolean;
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

export function CheckPanel({
  partyId,
  checkIds,
  activeCheckId,
  activeCheck,
  sessionInfo,
  busy,
  onSelectCheck,
  onRefreshCheck,
  onRecordPayment,
  onFinalize,
}: CheckPanelProps) {
  useEffect(() => {
    if (!activeCheckId && checkIds[0]) {
      void onSelectCheck(partyId, checkIds[0]);
    }
  }, [activeCheckId, checkIds, onSelectCheck, partyId]);

  return (
    <Card className="p-3.5">
      <CardHeader className="p-0">
        <CardTitle className="text-base">Checks</CardTitle>
      </CardHeader>
      <CardContent className="mt-3 p-0">
        {checkIds.length > 0 ? (
          <Tabs
            value={activeCheckId ?? checkIds[0]}
            onValueChange={(nextCheckId) => {
              void onSelectCheck(partyId, nextCheckId);
            }}
          >
            <TabsList className="h-auto w-full justify-start gap-1 overflow-x-auto rounded-[18px] bg-white/[0.03] p-1">
              {checkIds.map((checkId, index) => (
                <TabsTrigger key={checkId} value={checkId} className="h-9 rounded-[14px] px-3">
                  Check {index + 1}
                </TabsTrigger>
              ))}
            </TabsList>

            {checkIds.map((checkId) => (
              <TabsContent key={checkId} value={checkId} className="mt-3 space-y-3">
                {activeCheck?.id === checkId ? (
                  <>
                    <div className="rounded-[24px] border border-white/[0.08] bg-white/[0.03] p-3">
                      <div className="flex flex-wrap items-start justify-between gap-2">
                        <div className="space-y-2">
                          <div className="flex flex-wrap items-center gap-2">
                            <StatusChip label={activeCheck.status} tone={activeCheck.status === "PAID" ? "success" : "info"} />
                            {activeCheck.receiptNo ? (
                              <span className="rounded-full border border-primary/20 bg-primary/10 px-2.5 py-1 text-xs text-primary">
                                Receipt #{activeCheck.receiptNo}
                              </span>
                            ) : null}
                          </div>
                          <Button
                            variant="ghost"
                            size="sm"
                            className="h-8 rounded-full px-2.5"
                            onClick={() => void onRefreshCheck(activeCheck.id)}
                            disabled={busy}
                          >
                            <RefreshCcw className="h-3.5 w-3.5" />
                            Refresh
                          </Button>
                        </div>

                        <PaymentDialog
                          check={activeCheck}
                          busy={busy}
                          sessionInfo={sessionInfo}
                          onRecordPayment={onRecordPayment}
                          onFinalize={onFinalize}
                        />
                      </div>

                      <div className="mt-3 grid grid-cols-2 gap-2 text-sm">
                        <div className="rounded-[18px] border border-white/[0.08] bg-slate-950/35 px-3 py-2.5">
                          <p className="text-[10px] uppercase tracking-[0.18em] text-muted-foreground">Gross</p>
                          <p className="mt-1 font-semibold text-foreground">{formatCurrency(activeCheck.totalCents)}</p>
                        </div>
                        <div className="rounded-[18px] border border-white/[0.08] bg-slate-950/35 px-3 py-2.5">
                          <p className="text-[10px] uppercase tracking-[0.18em] text-muted-foreground">Due</p>
                          <p className="mt-1 font-semibold text-foreground">{formatCurrency(activeCheck.amountDueCents)}</p>
                        </div>
                        <div className="rounded-[18px] border border-white/[0.08] bg-slate-950/35 px-3 py-2.5">
                          <p className="text-[10px] uppercase tracking-[0.18em] text-muted-foreground">Paid</p>
                          <p className="mt-1 font-semibold text-foreground">{formatCurrency(activeCheck.paidTotalCents)}</p>
                        </div>
                        <div className="rounded-[18px] border border-white/[0.08] bg-slate-950/35 px-3 py-2.5">
                          <p className="text-[10px] uppercase tracking-[0.18em] text-muted-foreground">Tax</p>
                          <p className="mt-1 font-semibold text-foreground">{formatCurrency(activeCheck.taxCents)}</p>
                        </div>
                      </div>

                      <p className="mt-3 text-xs text-muted-foreground">
                        Products are added from the Products view after the target table and check are selected.
                      </p>
                    </div>

                    <div className="rounded-[24px] border border-white/[0.08] bg-white/[0.03] p-3">
                      <div className="flex items-center justify-between gap-2">
                        <p className="text-sm font-semibold text-foreground">Items</p>
                        <span className="rounded-full border border-white/10 bg-slate-950/35 px-2.5 py-1 text-[11px] text-muted-foreground">
                          {activeCheck.items.length}
                        </span>
                      </div>

                      <div className="mt-3 max-h-[280px] space-y-2 overflow-y-auto pr-1">
                        {activeCheck.items.length > 0 ? (
                          activeCheck.items.map((item) => (
                            <div key={item.itemId} className="rounded-[20px] border border-white/[0.08] bg-slate-950/35 p-3">
                              <div className="flex items-start justify-between gap-2">
                                <div className="min-w-0">
                                  <p className="truncate text-sm font-medium text-foreground">{item.nameSnapshot}</p>
                                  <p className="mt-1 truncate text-[11px] text-muted-foreground">
                                    {item.pricingModel} · qty {item.qty}
                                    {item.note ? ` · ${item.note}` : ""}
                                  </p>
                                </div>
                                {item.voided ? <StatusChip label="VOIDED" tone="danger" /> : null}
                              </div>

                              <div className="mt-2 space-y-1.5">
                                {item.components.map((component, index) => (
                                  <div
                                    key={`${item.itemId}-${index}`}
                                    className="flex items-center justify-between gap-2 text-[11px] text-muted-foreground"
                                  >
                                    <span className="truncate">
                                      {component.nameSnapshot} · VAT {component.vatRateSnapshot}
                                    </span>
                                    <span className="shrink-0">
                                      {formatCurrency(component.unitGrossCentsSnapshot * component.qty)}
                                    </span>
                                  </div>
                                ))}
                              </div>
                            </div>
                          ))
                        ) : (
                          <div className="rounded-[20px] border border-dashed border-white/10 px-3 py-3 text-sm text-muted-foreground">
                            No items yet on this check.
                          </div>
                        )}
                      </div>
                    </div>
                  </>
                ) : (
                  <div className="rounded-[20px] border border-dashed border-white/10 px-3 py-3 text-sm text-muted-foreground">
                    Select a check tab to load details.
                  </div>
                )}
              </TabsContent>
            ))}
          </Tabs>
        ) : (
          <div className="rounded-[20px] border border-dashed border-white/10 px-3 py-3 text-sm text-muted-foreground">
            No checks yet. Open a check to start ordering.
          </div>
        )}
      </CardContent>
    </Card>
  );
}
