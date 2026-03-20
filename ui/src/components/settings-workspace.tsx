import { useRef, useState } from "react";
import { FileUp, Settings2 } from "lucide-react";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";

type ImportSummary = {
  created: number;
  updated: number;
  skipped: number;
  failed: number;
  failed_rows: Array<{ row_number: number; reason: string }>;
};

type SettingsWorkspaceProps = {
  editMode: boolean;
  onEditModeChange: (next: boolean) => void;
  onImportProducts: (file: File) => Promise<ImportSummary>;
};

export function SettingsWorkspace({ editMode, onEditModeChange, onImportProducts }: SettingsWorkspaceProps) {
  const importInputRef = useRef<HTMLInputElement | null>(null);
  const [importBusy, setImportBusy] = useState(false);
  const [lastImportSummary, setLastImportSummary] = useState<ImportSummary | null>(null);

  async function handleImport(file: File) {
    setImportBusy(true);
    try {
      const summary = await onImportProducts(file);
      setLastImportSummary(summary);
      toast.success(
        `Import: ${summary.created} created, ${summary.updated} updated, ${summary.skipped} skipped, ${summary.failed} failed`,
      );
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "Unable to import products.");
    } finally {
      setImportBusy(false);
    }
  }

  return (
    <section className="h-full">
      <Card className="flex h-full min-h-0 flex-col p-4">
        <CardHeader className="p-0">
          <p className="text-[11px] uppercase tracking-[0.28em] text-muted-foreground">Settings</p>
          <CardTitle className="mt-1 text-xl tracking-[0.04em]">Admin and catalog tools</CardTitle>
          <CardDescription className="mt-1 text-sm">
            Keep selling space clean while edit access and imports stay available in one place.
          </CardDescription>
        </CardHeader>

        <CardContent className="mt-4 grid min-h-0 gap-4 xl:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
          <Card className="p-4">
            <CardHeader className="p-0">
              <CardTitle className="inline-flex items-center gap-2">
                <Settings2 className="h-4 w-4 text-primary" />
                Admin edit mode
              </CardTitle>
              <CardDescription>
                Toggle Products-page editing tools such as slot assignment and product/category maintenance.
              </CardDescription>
            </CardHeader>
            <CardContent className="mt-4 space-y-4 p-0">
              <div className="rounded-[24px] border border-white/10 bg-white/[0.03] p-4">
                <p className="text-xs uppercase tracking-[0.2em] text-muted-foreground">Current state</p>
                <p className="mt-2 text-lg font-semibold text-foreground">{editMode ? "Enabled" : "Disabled"}</p>
              </div>
              <Button type="button" size="lg" onClick={() => onEditModeChange(!editMode)}>
                <Settings2 className="h-4 w-4" />
                {editMode ? "Disable edit mode" : "Enable edit mode"}
              </Button>
            </CardContent>
          </Card>

          <Card className="p-4">
            <CardHeader className="p-0">
              <CardTitle className="inline-flex items-center gap-2">
                <FileUp className="h-4 w-4 text-primary" />
                Import CSV
              </CardTitle>
              <CardDescription>Run the existing product import flow from Settings instead of the Products page.</CardDescription>
            </CardHeader>
            <CardContent className="mt-4 space-y-4 p-0">
              <input
                ref={importInputRef}
                type="file"
                accept=".csv,text/csv"
                className="hidden"
                onChange={(event) => {
                  const file = event.target.files?.[0];
                  if (file) {
                    void handleImport(file);
                  }
                  event.currentTarget.value = "";
                }}
              />
              <Button
                type="button"
                size="lg"
                variant="secondary"
                disabled={importBusy}
                onClick={() => importInputRef.current?.click()}
              >
                <FileUp className="h-4 w-4" />
                {importBusy ? "Importing..." : "Import products"}
              </Button>

              {lastImportSummary ? (
                <div className="rounded-[24px] border border-white/10 bg-white/[0.03] px-4 py-3 text-sm">
                  <p className="font-medium text-foreground">
                    Import diagnostics: {lastImportSummary.created} created, {lastImportSummary.updated} updated, {lastImportSummary.skipped} skipped, {lastImportSummary.failed} failed
                  </p>
                  {lastImportSummary.failed_rows.length > 0 ? (
                    <div className="mt-2 space-y-1 text-muted-foreground">
                      {lastImportSummary.failed_rows.slice(0, 4).map((failure) => (
                        <p key={`${failure.row_number}-${failure.reason}`}>
                          Row {failure.row_number}: {failure.reason}
                        </p>
                      ))}
                    </div>
                  ) : null}
                </div>
              ) : null}
            </CardContent>
          </Card>
        </CardContent>
      </Card>
    </section>
  );
}
