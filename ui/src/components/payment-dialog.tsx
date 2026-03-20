import { useEffect, useMemo, useState } from "react";
import { CreditCard, Wallet } from "lucide-react";

import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import type { CheckRecord, SessionRecord } from "@/lib/normalize";
import { formatCurrency, toNumber } from "@/lib/utils";

type PaymentDialogProps = {
  check: CheckRecord;
  busy: boolean;
  sessionInfo: SessionRecord | null;
  onRecordPayment: (input: {
    checkId: string;
    method: "CASH" | "CARD_EXTERNAL";
    amountCents: number;
    externalRef?: string;
  }) => Promise<void>;
  onFinalize: (input: { checkId: string; paymentMethod: "CASH" | "CARD_EXTERNAL" }) => Promise<void>;
};

export function PaymentDialog({ check, busy, sessionInfo, onRecordPayment, onFinalize }: PaymentDialogProps) {
  const [open, setOpen] = useState(false);
  const [method, setMethod] = useState<"CASH" | "CARD_EXTERNAL">("CARD_EXTERNAL");
  const [amountCents, setAmountCents] = useState(String(Math.max(check.totalCents - check.paidTotalCents, 0)));
  const [externalRef, setExternalRef] = useState("");

  const remainingCents = useMemo(() => Math.max(check.totalCents - check.paidTotalCents, 0), [check]);

  useEffect(() => {
    setAmountCents(String(remainingCents));
  }, [remainingCents, check.id]);

  async function handleRecordPayment() {
    await onRecordPayment({
      checkId: check.id,
      method,
      amountCents: Math.max(1, toNumber(amountCents, remainingCents || 1)),
      externalRef: method === "CARD_EXTERNAL" ? externalRef : undefined,
    });
  }

  async function handleFinalize() {
    await onFinalize({ checkId: check.id, paymentMethod: method });
    setOpen(false);
  }

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button className="w-full sm:w-auto">Pay / Finalize</Button>
      </DialogTrigger>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Finalize check</DialogTitle>
          <DialogDescription>
            Record payment first, then finalize with the same method. Cash rounding applies only on finalize.
          </DialogDescription>
        </DialogHeader>

        <div className="grid gap-3 rounded-3xl border border-white/[0.08] bg-white/[0.03] p-4">
          <div className="flex items-center justify-between text-sm">
            <span className="text-muted-foreground">Total</span>
            <span>{formatCurrency(check.totalCents)}</span>
          </div>
          <div className="flex items-center justify-between text-sm">
            <span className="text-muted-foreground">Paid</span>
            <span>{formatCurrency(check.paidTotalCents)}</span>
          </div>
          <div className="flex items-center justify-between text-sm">
            <span className="text-muted-foreground">Remaining</span>
            <span>{formatCurrency(remainingCents)}</span>
          </div>
          <div className="text-xs text-muted-foreground">
            {sessionInfo ? `Session ${sessionInfo.status}` : "No open session linked"}
          </div>
        </div>

        <div className="grid gap-4">
          <div className="grid grid-cols-2 gap-2">
            <Button
              type="button"
              variant={method === "CARD_EXTERNAL" ? "default" : "secondary"}
              onClick={() => setMethod("CARD_EXTERNAL")}
            >
              <CreditCard className="h-4 w-4" />
              CARD_EXTERNAL
            </Button>
            <Button type="button" variant={method === "CASH" ? "default" : "secondary"} onClick={() => setMethod("CASH")}>
              <Wallet className="h-4 w-4" />
              CASH
            </Button>
          </div>

          <div className="space-y-2">
            <Label htmlFor="payment-amount">Amount cents</Label>
            <Input
              id="payment-amount"
              type="number"
              min={1}
              value={amountCents}
              onChange={(event) => setAmountCents(event.target.value)}
            />
          </div>

          {method === "CARD_EXTERNAL" ? (
            <div className="space-y-2">
              <Label htmlFor="external-ref">External reference</Label>
              <Input
                id="external-ref"
                value={externalRef}
                onChange={(event) => setExternalRef(event.target.value)}
                placeholder="Terminal slip / reference"
              />
            </div>
          ) : null}
        </div>

        <div className="grid grid-cols-2 gap-3">
          <Button type="button" variant="secondary" disabled={busy} onClick={() => void handleRecordPayment()}>
            Record payment
          </Button>
          <Button type="button" disabled={busy} onClick={() => void handleFinalize()}>
            Finalize
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
