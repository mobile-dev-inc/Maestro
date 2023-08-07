import { ThemeToggle } from "../common/theme";
import { API } from "../../api/api";
import clsx from "clsx";

const HeaderBanner = () => {
  const { data } = API.useBannerMessage({ refreshInterval: 1000 });
  if (!data?.level || data.level === "none") return null;

  const bgColor = {
    info: "bg-blue-100 dark:bg-blue-900",
    warning: "bg-amber-100 dark:bg-amber-900",
    error: "bg-red-100 dark:bg-red-900",
  }[data.level];

  return (
    <div
      className={clsx("py-3 px-7 text-sm text-center font-semibold", bgColor)}
    >
      <span>{data.message}</span>
    </div>
  );
};

const Header = () => (
  <div className="flex flex-col">
    <div className="flex py-3 px-7 items-center bg-white dark:bg-slate-900 dark:text-white border-b border-slate-200 dark:border-slate-800">
      <span className="font-bold cursor-default grow">$ maestro studio</span>
      <ThemeToggle />
    </div>
    <HeaderBanner />
  </div>
);

export default Header;
