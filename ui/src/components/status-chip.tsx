import { cn, formatCompactId } from "@/lib/utils";

type StatusChipProps = {
  label: string;
  tone?: "neutral" | "success" | "warning" | "danger" | "info";
  compactId?: string | null;
};

const toneClasses: Record<NonNullable<StatusChipProps["tone"]>, string> = {
  neutral: "border-white/10 bg-white/[0.06] text-foreground/90",
  success: "border-emerald-400/25 bg-emerald-500/10 text-emerald-100",
  warning: "border-amber-400/25 bg-amber-500/10 text-amber-100",
  danger: "border-rose-400/25 bg-rose-500/10 text-rose-100",
  info: "border-sky-400/25 bg-sky-500/10 text-sky-100",
};

export function StatusChip({ label, tone = "neutral", compactId }: StatusChipProps) {
  return (
    <span
      className={cn(
        "inline-flex items-center gap-2 rounded-full border px-3 py-1.5 text-xs font-medium tracking-[0.12em]",
        toneClasses[tone],
      )}
    >
      <span>{label}</span>
      {compactId ? <span className="text-[10px] opacity-70">{formatCompactId(compactId)}</span> : null}
    </span>
  );
}
