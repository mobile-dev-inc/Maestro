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
          "flex flex-row border-b border-slate-100 dark:border-slate-800 gap-3 w-[calc(100%-24px)] relative overflow-hidden",
          isDragging && "border-transparent"
        )
      )}
    >
      <div onClick={onClick} className="py-3 cursor-pointer">
        <Checkbox
          size="sm"
          checked={selected}
          className="pointer-events-none"
        />
      </div>
      <pre className="font-mono flex-grow cursor-default overflow-x-scroll text-sm text-gray-900 dark:text-white py-3 overflow-scroll">
        {command.yaml}
      </pre>
      <div className="bg-gradient-to-r from-transparent to-white dark:to-slate-900 w-10 absolute top-0 right-0 bottom-0 pointer-events-none rounded-lg" />
      <div className="absolute top-4 right-1">
        <StatusIcon status={command.status} />
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
