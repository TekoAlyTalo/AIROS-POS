export type AppRoute = "table-map" | "products";

const RAW_BASE_PATH = import.meta.env.VITE_BASE_PATH ?? "/";

function normalizeBasePath(path: string): string {
  if (!path || path === "/") {
    return "";
  }
  const withLeadingSlash = path.startsWith("/") ? path : `/${path}`;
  return withLeadingSlash.endsWith("/") ? withLeadingSlash.slice(0, -1) : withLeadingSlash;
}

const BASE_PATH = normalizeBasePath(RAW_BASE_PATH);

export function routePath(route: AppRoute): string {
  return route === "products" ? "/products" : "/";
}

export function toAppHref(path: string): string {
  if (path === "/") {
    return BASE_PATH || "/";
  }
  return `${BASE_PATH}${path}`;
}

export function resolveRoute(pathname: string): AppRoute {
  const relativePath = BASE_PATH && pathname.startsWith(BASE_PATH) ? pathname.slice(BASE_PATH.length) || "/" : pathname;
  if (relativePath === "/products") {
    return "products";
  }
  return "table-map";
}
