import { ReactElement } from "react";
import { ReplCommand, ReplCommandStatus } from "../../helpers/models";
import { Checkbox } from "../design-system/checkbox";
import { Icon } from "../design-system/icon";
import { Spinner } from "../design-system/spinner";
import clsx from "clsx";
import { twMerge } from "tailwind-merge";

interface CommandRowProps {
  command: ReplCommand;
  selected: boolean;
  onClick: () => void;
  isDragging: boolean;
}

export default function CommandRow({
  command,
  selected,
  onClick,
  isDragging,
}: CommandRowProps) {
  return (
    <div
      className={twMerge(
        clsx(
          "flex flex-row gap-3 w-[calc(100%-24px)] relative overflow-hidden py-1 pr-1",
          isDragging && "border-transparent"
        )
      )}
    >
      <div onClick={onClick} className="py-1 cursor-pointer">
        <Checkbox
          size="sm"
          checked={selected}
          className="pointer-events-none"
        />
      </div>
      <div className="relative flex-grow">
        <pre className="font-mono bg-gray-100 dark:bg-slate-800/50 cursor-default overflow-auto text-sm text-gray-900 dark:text-white px-2 py-1 pr-10 hide-scrollbar min-h-full flex items-center rounded-xl">
          {command.yaml}
        </pre>
        <div className="bg-gradient-to-r from-transparent to-gray-100 dark:to-slate-800/50 w-10 absolute top-0 right-0 bottom-0 pointer-events-none rounded-xl" />
        <div className="absolute top-2 right-2">
          <StatusIcon status={command.status} />
        </div>
      </div>
    </div>
  );
}

const StatusIcon = ({
  status,
}: {
  status: ReplCommandStatus;
}): ReactElement | null => {
  switch (status) {
    case "success":
      return (
        <Icon iconName="RiCheckLine" size="16" className="text-green-500" />
      );
    case "canceled":
      return null;
    case "error":
      return <Icon iconName="RiAlertLine" size="16" className="text-red-500" />;
    case "pending":
      return null;
    case "running":
      return <Spinner size="16" className="text-gray-900 dark:text-white" />;
  }
};
