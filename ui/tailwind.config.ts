import type { Config } from "tailwindcss";
import tailwindcssAnimate from "tailwindcss-animate";

const config = {
  darkMode: ["class"],
  content: ["./index.html", "./src/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        background: "hsl(var(--background))",
        foreground: "hsl(var(--foreground))",
        card: "hsl(var(--card))",
        "card-foreground": "hsl(var(--card-foreground))",
        border: "hsl(var(--border))",
        input: "hsl(var(--input))",
        ring: "hsl(var(--ring))",
        primary: "hsl(var(--primary))",
        "primary-foreground": "hsl(var(--primary-foreground))",
        secondary: "hsl(var(--secondary))",
        "secondary-foreground": "hsl(var(--secondary-foreground))",
        muted: "hsl(var(--muted))",
        "muted-foreground": "hsl(var(--muted-foreground))",
        accent: "hsl(var(--accent))",
        "accent-foreground": "hsl(var(--accent-foreground))",
        destructive: "hsl(var(--destructive))",
        "destructive-foreground": "hsl(var(--destructive-foreground))",
      },
      borderRadius: {
        xl: "1rem",
        "2xl": "1.25rem",
        "3xl": "1.5rem",
      },
      boxShadow: {
        glow: "0 0 0 1px rgba(72, 212, 190, 0.18), 0 14px 38px rgba(8, 17, 34, 0.45)",
        glass: "0 18px 50px rgba(5, 12, 26, 0.45)",
      },
      backgroundImage: {
        "app-gradient":
          "radial-gradient(circle at top left, rgba(49, 104, 144, 0.18), transparent 30%), radial-gradient(circle at top right, rgba(64, 174, 148, 0.12), transparent 26%), linear-gradient(180deg, rgba(12, 22, 41, 0.96), rgba(4, 8, 18, 1))",
      },
      fontFamily: {
        sans: ['"Segoe UI Variable"', '"Trebuchet MS"', "sans-serif"],
      },
    },
  },
  plugins: [tailwindcssAnimate],
} satisfies Config;

export default config;
