/** @type {import('tailwindcss').Config} */
export default {
  content: ["./index.html", "./src/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        // Exact hex values from android/.../MealVisuals.kt's
        // backgroundFor() - kept in sync by hand since there's no
        // shared design-token source between the two clients yet. If
        // these ever drift, MealVisuals.kt is the source of truth.
        meal: {
          breakfast: "#FFE0B2",
          "breakfast-dark": "#4A3418",
          lunch: "#C8E6C9",
          "lunch-dark": "#28402A",
          dinner: "#D1C4E9",
          "dinner-dark": "#352D4A",
          snack: "#FFCCBC",
          "snack-dark": "#4A2E24",
        },
        // Exact hex values from android/.../MacroProgressRing.kt's
        // MacroColors object - same "kept in sync by hand" caveat.
        macro: {
          protein: "#E8837A",
          fat: "#E6B800",
          carbs: "#7EC8E3",
          fiber: "#9C7A54",
        },
      },
    },
  },
  plugins: [],
};
