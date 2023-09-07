/** @type {import('tailwindcss').Config} */
const defaultTheme = require("tailwindcss/defaultTheme");

module.exports = {
  content: ["./src/**/*.{js,jsx,ts,tsx}"],
  theme: {
    fontFamily: {
      sans: ["'Inter'", "sans-serif"],
      mono: ["'JetBrains Mono'", "monospace"],
    },
    extend: {
      colors: {
        slate: {
          650: "rgb(61 75 95)",
          750: "rgb(41 53 72)",
          850: "rgb(22 32 51)",
        },
        purple: {
          25: "#f9f8ff",
          50: "#f1eeff",
          75: "#e2dcff",
          100: "#c6bbff",
          200: "#bcafff",
          300: "#a797ff",
          400: "#8b75ff",
          500: "#7357ff",
          600: "#6347f4",
          700: "#553ade",
          800: "#3c28a4",
          900: "#21194d",
        },
      },
      boxShadow: {
        up: "0px -6px 12px -6px rgba(17, 12, 34, 0.1)",
      },
      minWidth: {
        ...defaultTheme.spacing,
      },
    },
  },
  plugins: [],
  darkMode: "class",
};
