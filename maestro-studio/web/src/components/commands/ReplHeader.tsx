import { useState } from "react";
import clsx from "clsx";
import { CopyToClipboard } from "react-copy-to-clipboard";
import { Checkbox } from "../design-system/checkbox";
import { Button } from "../design-system/button";

interface ReplHeaderProps {
  onSelectAll: () => void;
  onDeselectAll: () => void;
  selected: number;
  copyText: string;
  allSelected: boolean;
  onPlay: () => void;
  onExport: () => void;
  onDelete: () => void;
}

export default function ReplHeader({
  onSelectAll,
  onDeselectAll,
  selected,
  copyText,
  allSelected,
  onPlay,
  onExport,
  onDelete,
}: ReplHeaderProps) {
  const [commandCopied, setCommandCopied] = useState<boolean>(false);

  /**
   * Show success state for copy
   */
  const onCommandCopy = () => {
    setCommandCopied(true);
    setTimeout(() => {
      setCommandCopied(false);
    }, 1000);
  };

  return (
    <div className="flex justify-between w-full border-b border-slate-100 dark:border-slate-800 flex-wrap">
      <div
        className={clsx(
          "py-2",
          selected > 0
            ? "text-gray-900 dark:text-white"
            : "text-gray-400 dark:text-gray-600"
        )}
      >
        <Checkbox
          size="sm"
          checked={allSelected}
          onChange={() => {
            if (selected === 0) {
              onSelectAll();
            } else {
              onDeselectAll();
            }
          }}
          indeterminate={selected > 0 && !allSelected}
          label={selected > 0 ? `${selected} Selected` : "Select All"}
        />
      </div>
      {selected > 0 && (
        <div className="flex gap-0 flex-wrap justify-end">
          <Button
            variant="quaternary"
            size="sm"
            leftIcon="RiPlayLine"
            onClick={onPlay}
          >
            Play
          </Button>
          <Button
            variant="quaternary"
            size="sm"
            leftIcon="RiUpload2Line"
            onClick={onExport}
          >
            Export
          </Button>
          {commandCopied ? (
            <Button variant="primary-green" size="sm" leftIcon="RiCheckLine">
              Copy
            </Button>
          ) : (
            <CopyToClipboard text={copyText} onCopy={onCommandCopy}>
              <Button variant="quaternary" size="sm" leftIcon="RiFileCopyLine">
                Copy
              </Button>
            </CopyToClipboard>
          )}
          <Button
            variant="quaternary-red"
            size="sm"
            leftIcon="RiDeleteBinLine"
            onClick={onDelete}
          >
            Delete
          </Button>
        </div>
      )}
    </div>
  );
}
