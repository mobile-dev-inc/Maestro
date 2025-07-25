import { useState } from "react";
import { Button } from "../design-system/button";

type Theme = "light" | "dark";

const getDefaultTheme = (): Theme => {
  if (
    localStorage.theme === "dark" ||
    (!("theme" in localStorage) &&
      window.matchMedia("(prefers-color-scheme: dark)").matches)
  ) {
    localStorage.theme = "dark";
    document.documentElement.classList.add("dark");
    return "dark";
  } else {
    localStorage.theme = "light";
    document.documentElement.classList.remove("dark");
    return "light";
  }
};

export const ThemeToggle = () => {
  const [theme, toggleTheme] = useTheme();

  return (
    <Button
      variant="quaternary"
      onClick={toggleTheme}
      icon={theme === "light" ? "RiSunLine" : "RiMoonLine"}
      size="md"
      className="h-8 w-8"
    />
  );
};

export const useTheme = (): [Theme, () => void] => {
  const [theme, setTheme] = useState<Theme>(getDefaultTheme());

  const toggleTheme = () => {
    const newTheme = localStorage.theme === "light" ? "dark" : "light";

    if (newTheme === "dark") {
      localStorage.theme = "dark";
      document.documentElement.classList.add("dark");
    } else {
      localStorage.theme = "light";
      document.documentElement.classList.remove("dark");
    }

    localStorage.theme = newTheme;
    setTheme(newTheme);
  };

  return [theme, toggleTheme];
};
