import { ThemeToggle } from "../common/theme";

const Header = () => (
  <div className="flex py-3 px-7 items-center bg-white dark:bg-slate-900 dark:text-white border-b border-slate-200 dark:border-slate-800">
    <span className="font-bold cursor-default grow">$ maestro studio</span>
    <ThemeToggle />
  </div>
);

export default Header;
