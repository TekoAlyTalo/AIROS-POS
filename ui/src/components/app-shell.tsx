import type { ReactNode } from "react";

import { Sidebar } from "@/components/sidebar";
import type { AppRoute } from "@/lib/router";

type AppShellProps = {
  currentRoute: AppRoute;
  onNavigate: (route: AppRoute) => void;
  header: ReactNode;
  children: ReactNode;
  aside: ReactNode;
};

export function AppShell({ currentRoute, onNavigate, header, children, aside }: AppShellProps) {
  return (
    <div className="min-h-screen p-3 md:p-4 xl:p-5">
      <div className="mx-auto grid max-w-[1680px] gap-3 xl:grid-cols-[228px_minmax(0,1fr)_320px] xl:gap-4">
        <div className="xl:sticky xl:top-5 xl:h-[calc(100dvh-2.5rem)]">
          <Sidebar currentRoute={currentRoute} onNavigate={onNavigate} />
        </div>

        <main className="flex min-h-[calc(100dvh-1.5rem)] flex-col gap-3 md:min-h-[calc(100dvh-2rem)] xl:min-h-[calc(100dvh-2.5rem)] xl:gap-4">
          {header}

          <div className="min-h-0 flex-1">{children}</div>
        </main>

        <div className="xl:sticky xl:top-5 xl:h-[calc(100dvh-2.5rem)]">{aside}</div>
      </div>
    </div>
  );
}
