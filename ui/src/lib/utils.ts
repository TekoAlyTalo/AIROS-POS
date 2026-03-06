import { clsx, type ClassValue } from "clsx";
import { twMerge } from "tailwind-merge";

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

export function formatCurrency(cents: number) {
  return new Intl.NumberFormat("fi-FI", {
    style: "currency",
    currency: "EUR",
    minimumFractionDigits: 2,
  }).format(cents / 100);
}

export function formatCompactId(value: string | null | undefined) {
  if (!value) {
    return "Unknown";
  }
  return value.slice(0, 8).toUpperCase();
}

export function toNumber(value: string, fallback = 0) {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : fallback;
}

export function minutesSince(value: string | null | undefined, now = new Date()) {
  if (!value) {
    return null;
  }
  const parsed = new Date(value);
  const deltaMs = now.getTime() - parsed.getTime();
  if (Number.isNaN(parsed.getTime())) {
    return null;
  }
  return Math.max(0, Math.floor(deltaMs / 60000));
}

export function formatElapsedAge(value: string | null | undefined, now = new Date()) {
  if (!value) {
    return "--";
  }
  const parsed = new Date(value);
  const deltaMs = now.getTime() - parsed.getTime();
  if (Number.isNaN(parsed.getTime())) {
    return "--";
  }
  const totalSeconds = Math.max(0, Math.floor(deltaMs / 1000));
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;

  if (hours > 0) {
    return `${String(hours).padStart(2, "0")}:${String(minutes).padStart(2, "0")}`;
  }

  return `${String(minutes).padStart(2, "0")}:${String(seconds).padStart(2, "0")}`;
}
