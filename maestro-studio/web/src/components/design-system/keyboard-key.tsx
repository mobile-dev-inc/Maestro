import { ReactNode } from "react";

interface KeyboardKeyProps {
  children: ReactNode;
}

const KeyboardKey = ({ children }: KeyboardKeyProps) => {
  return (
    <kbd className="h-6 min-w-6 px-1 bg-slate-200 dark:bg-slate-800 rounded-sm flex items-center justify-center text-center font-mono">
      {children}
    </kbd>
  );
};

export default KeyboardKey;
