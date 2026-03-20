import type { ReactNode } from "react";

import { Sidebar } from "@/components/sidebar";
import type { AppRoute } from "@/lib/router";
import { cn } from "@/lib/utils";

type AppShellProps = {
  currentRoute: AppRoute;
  onNavigate: (route: AppRoute) => void;
  header: ReactNode;
  children: ReactNode;
  aside: ReactNode;
};

export function AppShell({ currentRoute, onNavigate, header, children, aside }: AppShellProps) {
  const cashierLayout = currentRoute === "table-map";

  return (
    <div className={cn("min-h-screen p-2.5 md:p-3 xl:p-3.5", cashierLayout && "lg:h-[100dvh] lg:overflow-hidden")}>
      <div
        className={cn(
          "mx-auto grid gap-2.5 md:gap-3",
          cashierLayout
            ? "max-w-[1600px] lg:h-full lg:grid-cols-[172px_minmax(0,1fr)_292px] xl:grid-cols-[184px_minmax(0,1fr)_304px] 2xl:max-w-[1680px] 2xl:grid-cols-[208px_minmax(0,1fr)_320px]"
            : "max-w-[1680px] xl:grid-cols-[228px_minmax(0,1fr)_320px] xl:gap-4",
        )}
      >
        <div className={cn(cashierLayout ? "lg:min-h-0 lg:h-full" : "xl:sticky xl:top-5 xl:h-[calc(100dvh-2.5rem)]")}>
          <Sidebar currentRoute={currentRoute} onNavigate={onNavigate} />
        </div>

        <main
          className={cn(
            "flex flex-col gap-2.5 md:gap-3",
            cashierLayout
              ? "min-h-[calc(100dvh-1.25rem)] lg:min-h-0 lg:h-full"
              : "min-h-[calc(100dvh-1.5rem)] md:min-h-[calc(100dvh-2rem)] xl:min-h-[calc(100dvh-2.5rem)] xl:gap-4",
          )}
        >
          {header}

          <div className="min-h-0 flex-1">{children}</div>
        </main>

        <div className={cn(cashierLayout ? "lg:min-h-0 lg:h-full" : "xl:sticky xl:top-5 xl:h-[calc(100dvh-2.5rem)]")}>
          {aside}
        </div>
      </div>
    </div>
  );
}
