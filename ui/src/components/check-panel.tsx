import { useEffect, useMemo, useState, type FormEvent } from "react";
import { Plus, RefreshCcw } from "lucide-react";

import { PaymentDialog } from "@/components/payment-dialog";
import { StatusChip } from "@/components/status-chip";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Textarea } from "@/components/ui/textarea";
import type { CheckRecord, SessionRecord } from "@/lib/normalize";
import { formatCurrency, toNumber } from "@/lib/utils";

type ComponentDraft = {
  nameSnapshot: string;
  vatRateSnapshot: string;
  unitGrossCentsSnapshot: string;
  qty: string;
};

type CheckPanelProps = {
  partyId: string;
  checkIds: string[];
  activeCheckId: string | null;
  activeCheck: CheckRecord | null;
  sessionInfo: SessionRecord | null;
  busy: boolean;
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

const vatOptions = [
  { label: "25.5%", value: "0.255" },
  { label: "14%", value: "0.14" },
];

function emptyCompositeRow(): ComponentDraft {
  return {
    nameSnapshot: "",
    vatRateSnapshot: "0.255",
    unitGrossCentsSnapshot: "",
    qty: "1",
  };
}

export function CheckPanel({
  partyId,
  checkIds,
  activeCheckId,
  activeCheck,
  sessionInfo,
  busy,
  onSelectCheck,
  onRefreshCheck,
  onAddItems,
  onRecordPayment,
  onFinalize,
}: CheckPanelProps) {
  const [pricingModel, setPricingModel] = useState<"SINGLE_VAT" | "COMPOSITE_VAT">("SINGLE_VAT");
  const [itemName, setItemName] = useState("");
  const [itemNote, setItemNote] = useState("");
  const [itemQty, setItemQty] = useState("1");
  const [singleVatRate, setSingleVatRate] = useState("0.255");
  const [singleGrossCents, setSingleGrossCents] = useState("");
  const [compositeRows, setCompositeRows] = useState<ComponentDraft[]>([emptyCompositeRow(), emptyCompositeRow()]);
  const [composerError, setComposerError] = useState<string | null>(null);

  useEffect(() => {
    if (!activeCheckId && checkIds[0]) {
      void onSelectCheck(partyId, checkIds[0]);
    }
  }, [activeCheckId, checkIds, onSelectCheck, partyId]);

  const itemTotalPreview = useMemo(() => {
    if (pricingModel === "SINGLE_VAT") {
      return toNumber(singleGrossCents, 0) * Math.max(1, toNumber(itemQty, 1));
    }
    return compositeRows.reduce(
      (sum, row) => sum + toNumber(row.unitGrossCentsSnapshot, 0) * Math.max(1, toNumber(row.qty, 1)),
      0,
    );
  }, [compositeRows, itemQty, pricingModel, singleGrossCents]);

  function resetComposer() {
    setItemName("");
    setItemNote("");
    setItemQty("1");
    setSingleVatRate("0.255");
    setSingleGrossCents("");
    setCompositeRows([emptyCompositeRow(), emptyCompositeRow()]);
    setComposerError(null);
  }

  async function handleSubmitComposer(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!activeCheck) {
      return;
    }

    const nextQty = Math.max(1, toNumber(itemQty, 1));
    if (!itemName.trim()) {
      setComposerError("Item name is required.");
      return;
    }

    let components;
    if (pricingModel === "SINGLE_VAT") {
      const gross = toNumber(singleGrossCents, 0);
      if (gross <= 0) {
        setComposerError("Single VAT items require a positive gross cents value.");
        return;
      }
      components = [
        {
          name_snapshot: itemName.trim(),
          vat_rate_snapshot: Number(singleVatRate),
          unit_gross_cents_snapshot: gross,
          qty: nextQty,
        },
      ];
    } else {
      const cleanedRows = compositeRows.filter(
        (row) => row.nameSnapshot.trim() && toNumber(row.unitGrossCentsSnapshot, 0) > 0,
      );
      if (cleanedRows.length < 2) {
        setComposerError("Composite VAT items require at least two explicit components.");
        return;
      }
      components = cleanedRows.map((row) => ({
        name_snapshot: row.nameSnapshot.trim(),
        vat_rate_snapshot: Number(row.vatRateSnapshot),
        unit_gross_cents_snapshot: toNumber(row.unitGrossCentsSnapshot, 0),
        qty: Math.max(1, toNumber(row.qty, nextQty)),
      }));
    }

    setComposerError(null);
    await onAddItems(activeCheck.id, {
      items: [
        {
          name_snapshot: itemName.trim(),
          qty: nextQty,
          pricing_model: pricingModel,
          note: itemNote.trim() || undefined,
          components,
        },
      ],
    });
    resetComposer();
  }

  return (
    <div className="space-y-4">
      <Card className="p-4">
        <CardHeader className="p-0">
          <CardTitle>Checks</CardTitle>
          <CardDescription>Open and review checks known to this UI session.</CardDescription>
        </CardHeader>
        <CardContent className="mt-4 p-0">
          {checkIds.length > 0 ? (
            <Tabs
              value={activeCheckId ?? checkIds[0]}
              onValueChange={(nextCheckId) => {
                void onSelectCheck(partyId, nextCheckId);
              }}
            >
              <TabsList className="w-full justify-start overflow-x-auto">
                {checkIds.map((checkId, index) => (
                  <TabsTrigger key={checkId} value={checkId}>
                    Check {index + 1}
                  </TabsTrigger>
                ))}
              </TabsList>
              {checkIds.map((checkId) => (
                <TabsContent key={checkId} value={checkId} className="space-y-4">
                  {activeCheck?.id === checkId ? (
                    <>
                      <div className="grid gap-2 rounded-3xl border border-white/[0.08] bg-white/[0.03] p-4">
                        <div className="flex items-center justify-between">
                          <div>
                            <p className="text-xs uppercase tracking-[0.22em] text-muted-foreground">Check status</p>
                            <div className="mt-2 flex items-center gap-2">
                              <StatusChip label={activeCheck.status} tone={activeCheck.status === "PAID" ? "success" : "info"} />
                              <Button
                                variant="ghost"
                                size="sm"
                                onClick={() => void onRefreshCheck(activeCheck.id)}
                                disabled={busy}
                              >
                                <RefreshCcw className="h-3.5 w-3.5" />
                                Refresh
                              </Button>
                            </div>
                          </div>
                          <PaymentDialog
                            check={activeCheck}
                            busy={busy}
                            sessionInfo={sessionInfo}
                            onRecordPayment={onRecordPayment}
                            onFinalize={onFinalize}
                          />
                        </div>
                        <div className="grid grid-cols-2 gap-3 text-sm md:grid-cols-4">
                          <div>
                            <p className="text-xs uppercase tracking-[0.18em] text-muted-foreground">Gross</p>
                            <p className="mt-1 text-lg font-semibold">{formatCurrency(activeCheck.totalCents)}</p>
                          </div>
                          <div>
                            <p className="text-xs uppercase tracking-[0.18em] text-muted-foreground">Net</p>
                            <p className="mt-1 text-lg font-semibold">{formatCurrency(activeCheck.subtotalCents)}</p>
                          </div>
                          <div>
                            <p className="text-xs uppercase tracking-[0.18em] text-muted-foreground">Tax</p>
                            <p className="mt-1 text-lg font-semibold">{formatCurrency(activeCheck.taxCents)}</p>
                          </div>
                          <div>
                            <p className="text-xs uppercase tracking-[0.18em] text-muted-foreground">Paid</p>
                            <p className="mt-1 text-lg font-semibold">{formatCurrency(activeCheck.paidTotalCents)}</p>
                          </div>
                        </div>
                        {activeCheck.receiptNo ? (
                          <div className="text-sm text-primary">Receipt #{activeCheck.receiptNo}</div>
                        ) : null}
                      </div>

                      <form
                        className="grid gap-4 rounded-3xl border border-white/[0.08] bg-white/[0.03] p-4"
                        onSubmit={(event) => void handleSubmitComposer(event)}
                      >
                        <div className="flex items-center justify-between">
                          <div>
                            <p className="text-xs uppercase tracking-[0.22em] text-muted-foreground">Add item</p>
                            <p className="mt-1 text-sm text-muted-foreground">Use explicit component pricing only.</p>
                          </div>
                          <div className="flex gap-2">
                            <Button
                              type="button"
                              size="sm"
                              variant={pricingModel === "SINGLE_VAT" ? "default" : "secondary"}
                              onClick={() => setPricingModel("SINGLE_VAT")}
                            >
                              Single VAT
                            </Button>
                            <Button
                              type="button"
                              size="sm"
                              variant={pricingModel === "COMPOSITE_VAT" ? "default" : "secondary"}
                              onClick={() => setPricingModel("COMPOSITE_VAT")}
                            >
                              Composite VAT
                            </Button>
                          </div>
                        </div>

                        <div className="grid gap-4 md:grid-cols-2">
                          <div className="space-y-2">
                            <Label htmlFor="item-name">Item name</Label>
                            <Input id="item-name" value={itemName} onChange={(event) => setItemName(event.target.value)} />
                          </div>
                          <div className="space-y-2">
                            <Label htmlFor="item-qty">Quantity</Label>
                            <Input
                              id="item-qty"
                              type="number"
                              min={1}
                              value={itemQty}
                              onChange={(event) => setItemQty(event.target.value)}
                            />
                          </div>
                        </div>

                        <div className="space-y-2">
                          <Label htmlFor="item-note">Note</Label>
                          <Textarea
                            id="item-note"
                            value={itemNote}
                            onChange={(event) => setItemNote(event.target.value)}
                            placeholder="Optional kitchen or service note"
                          />
                        </div>

                        {pricingModel === "SINGLE_VAT" ? (
                          <div className="grid gap-4 md:grid-cols-2">
                            <div className="space-y-2">
                              <Label htmlFor="single-gross">Gross cents</Label>
                              <Input
                                id="single-gross"
                                type="number"
                                min={1}
                                value={singleGrossCents}
                                onChange={(event) => setSingleGrossCents(event.target.value)}
                              />
                            </div>
                            <div className="space-y-2">
                              <Label htmlFor="single-vat">VAT rate</Label>
                              <select
                                id="single-vat"
                                className="h-11 w-full rounded-2xl border border-white/10 bg-input/90 px-4 text-sm text-foreground outline-none"
                                value={singleVatRate}
                                onChange={(event) => setSingleVatRate(event.target.value)}
                              >
                                {vatOptions.map((option) => (
                                  <option key={option.value} value={option.value}>
                                    {option.label}
                                  </option>
                                ))}
                              </select>
                            </div>
                          </div>
                        ) : (
                          <div className="space-y-3">
                            {compositeRows.map((row, index) => (
                              <div
                                key={index}
                                className="grid gap-3 rounded-3xl border border-white/[0.08] bg-slate-950/35 p-4 md:grid-cols-4"
                              >
                                <div className="space-y-2 md:col-span-2">
                                  <Label>Component name</Label>
                                  <Input
                                    value={row.nameSnapshot}
                                    onChange={(event) =>
                                      setCompositeRows((current) =>
                                        current.map((item, itemIndex) =>
                                          itemIndex === index ? { ...item, nameSnapshot: event.target.value } : item,
                                        ),
                                      )
                                    }
                                  />
                                </div>
                                <div className="space-y-2">
                                  <Label>VAT rate</Label>
                                  <select
                                    className="h-11 w-full rounded-2xl border border-white/10 bg-input/90 px-4 text-sm text-foreground outline-none"
                                    value={row.vatRateSnapshot}
                                    onChange={(event) =>
                                      setCompositeRows((current) =>
                                        current.map((item, itemIndex) =>
                                          itemIndex === index ? { ...item, vatRateSnapshot: event.target.value } : item,
                                        ),
                                      )
                                    }
                                  >
                                    {vatOptions.map((option) => (
                                      <option key={option.value} value={option.value}>
                                        {option.label}
                                      </option>
                                    ))}
                                  </select>
                                </div>
                                <div className="space-y-2">
                                  <Label>Gross cents</Label>
                                  <Input
                                    type="number"
                                    min={1}
                                    value={row.unitGrossCentsSnapshot}
                                    onChange={(event) =>
                                      setCompositeRows((current) =>
                                        current.map((item, itemIndex) =>
                                          itemIndex === index
                                            ? { ...item, unitGrossCentsSnapshot: event.target.value }
                                            : item,
                                        ),
                                      )
                                    }
                                  />
                                </div>
                                <div className="space-y-2">
                                  <Label>Qty</Label>
                                  <Input
                                    type="number"
                                    min={1}
                                    value={row.qty}
                                    onChange={(event) =>
                                      setCompositeRows((current) =>
                                        current.map((item, itemIndex) =>
                                          itemIndex === index ? { ...item, qty: event.target.value } : item,
                                        ),
                                      )
                                    }
                                  />
                                </div>
                              </div>
                            ))}
                            <Button
                              type="button"
                              variant="secondary"
                              size="sm"
                              onClick={() => setCompositeRows((current) => [...current, emptyCompositeRow()])}
                            >
                              <Plus className="h-3.5 w-3.5" />
                              Add component row
                            </Button>
                          </div>
                        )}

                        <div className="flex items-center justify-between text-sm text-muted-foreground">
                          <span>Item preview total</span>
                          <span className="text-foreground">{formatCurrency(itemTotalPreview)}</span>
                        </div>
                        {composerError ? <div className="text-sm text-rose-200">{composerError}</div> : null}
                        <Button type="submit" disabled={busy}>
                          Add item
                        </Button>
                      </form>

                      <Card className="p-4">
                        <CardHeader className="p-0">
                          <CardTitle>Check items</CardTitle>
                        </CardHeader>
                        <CardContent className="mt-4 space-y-3 p-0">
                          {activeCheck.items.length > 0 ? (
                            activeCheck.items.map((item) => (
                              <div
                                key={item.itemId}
                                className="rounded-3xl border border-white/[0.08] bg-white/[0.03] p-4"
                              >
                                <div className="flex items-start justify-between gap-3">
                                  <div>
                                    <p className="text-sm font-medium text-foreground">{item.nameSnapshot}</p>
                                    <p className="mt-1 text-xs text-muted-foreground">
                                      {item.pricingModel} · qty {item.qty} {item.note ? `· ${item.note}` : ""}
                                    </p>
                                  </div>
                                  {item.voided ? <StatusChip label="VOIDED" tone="danger" /> : null}
                                </div>
                                <div className="mt-3 space-y-2">
                                  {item.components.map((component, index) => (
                                    <div key={`${item.itemId}-${index}`} className="flex items-center justify-between text-xs text-muted-foreground">
                                      <span>
                                        {component.nameSnapshot} · VAT {component.vatRateSnapshot}
                                      </span>
                                      <span>{formatCurrency(component.unitGrossCentsSnapshot * component.qty)}</span>
                                    </div>
                                  ))}
                                </div>
                              </div>
                            ))
                          ) : (
                            <div className="rounded-3xl border border-dashed border-white/10 p-4 text-sm text-muted-foreground">
                              No items yet on this check.
                            </div>
                          )}
                        </CardContent>
                      </Card>
                    </>
                  ) : (
                    <div className="rounded-3xl border border-dashed border-white/10 p-4 text-sm text-muted-foreground">
                      Select a check tab to load details.
                    </div>
                  )}
                </TabsContent>
              ))}
            </Tabs>
          ) : (
            <div className="rounded-3xl border border-dashed border-white/10 p-4 text-sm text-muted-foreground">
              No known checks yet. Open a check from the party panel to start ordering.
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
