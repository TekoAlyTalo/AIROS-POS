import { useState, type FormEvent } from "react";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import type { TableRecord } from "@/lib/normalize";
import { toNumber } from "@/lib/utils";

type PartyFormProps = {
  table: TableRecord;
  busy: boolean;
  onSubmit: (input: { table: TableRecord; guestCount: number; note: string }) => Promise<void>;
};

export function PartyForm({ table, busy, onSubmit }: PartyFormProps) {
  const [guestCount, setGuestCount] = useState("2");
  const [note, setNote] = useState("");

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    await onSubmit({
      table,
      guestCount: Math.max(1, toNumber(guestCount, 1)),
      note,
    });
    setNote("");
  }

  return (
    <form className="space-y-4" onSubmit={(event) => void handleSubmit(event)}>
      <div className="space-y-2">
        <Label htmlFor="guest-count">Guest count</Label>
        <Input
          id="guest-count"
          type="number"
          min={1}
          value={guestCount}
          onChange={(event) => setGuestCount(event.target.value)}
        />
      </div>

      <div className="space-y-2">
        <Label htmlFor="party-note">Note</Label>
        <Textarea
          id="party-note"
          value={note}
          onChange={(event) => setNote(event.target.value)}
          placeholder="Optional service note"
        />
      </div>

      <Button type="submit" className="w-full" disabled={busy}>
        Open Party at {table.label}
      </Button>
    </form>
  );
}
