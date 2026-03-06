import { LayoutGrid, Package2, ReceiptText, Waves } from "lucide-react";

import { Button } from "@/components/ui/button";
import type { AppRoute } from "@/lib/router";
import { cn } from "@/lib/utils";

const navItems = [
  { id: "table-map", label: "Table Map", icon: LayoutGrid },
  { id: "products", label: "Products", icon: Package2 },
  { id: "checks", label: "Checks", icon: ReceiptText, disabled: true },
  { id: "service", label: "Service", icon: Waves, disabled: true },
] as const;

type SidebarProps = {
  currentRoute: AppRoute;
  onNavigate: (route: AppRoute) => void;
};

export function Sidebar({ currentRoute, onNavigate }: SidebarProps) {
  return (
    <aside className="glass-card flex h-full flex-col justify-between p-4 xl:p-5">
      <div className="space-y-4">
        <div className="flex justify-center rounded-[26px] border border-white/[0.08] bg-gradient-to-b from-slate-950/80 to-slate-950/35 p-3.5">
          <div className="flex aspect-square w-full max-w-[168px] items-center justify-center overflow-hidden rounded-[26px] border border-white/[0.08] bg-black/40 shadow-[0_12px_32px_rgba(0,0,0,0.28)]">
            <img src="/images/airos.png" alt="AIROS" className="block h-auto w-[82%] object-contain" />
          </div>
        </div>

        <div className="space-y-3">
          <p className="text-[11px] uppercase tracking-[0.24em] text-muted-foreground">Navigation</p>
          <div className="space-y-2">
            {navItems.map((item) => {
              const Icon = item.icon;
              const active = item.id === currentRoute;
              const disabled = "disabled" in item && item.disabled;
              return (
                <Button
                  key={item.id}
                  variant={active ? "default" : "secondary"}
                  className={cn("w-full justify-start rounded-2xl px-4", !active && "opacity-70")}
                  disabled={disabled}
                  onClick={() => {
                    if (item.id === "table-map" || item.id === "products") {
                      onNavigate(item.id);
                    }
                  }}
                >
                  <Icon className="h-4 w-4" />
                  {item.label}
                </Button>
              );
            })}
          </div>
        </div>
      </div>

      <div className="rounded-3xl border border-white/[0.08] bg-white/[0.03] p-3.5">
        <p className="text-xs uppercase tracking-[0.24em] text-muted-foreground">Mode</p>
        <p className="mt-2 text-sm text-foreground">Dev token active</p>
        <p className="mt-1 text-xs text-muted-foreground">Edge writes only. No AI assistance in the POS path.</p>
      </div>
    </aside>
  );
}
