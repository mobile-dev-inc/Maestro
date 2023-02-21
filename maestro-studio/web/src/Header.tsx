import { ThemeToggle } from "./theme"

const Header = () => (
  <div className="flex py-4 px-8 items-center bg-white dark:bg-slate-800 dark:text-white border-b dark:border-slate-600">
    <span className="font-bold font-mono cursor-default grow">$ maestro studio</span>
    <ThemeToggle />
  </div>
)

export default Header