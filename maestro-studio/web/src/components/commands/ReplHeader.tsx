import { useState } from "react";
import clsx from "clsx";
import copy from "copy-to-clipboard";
import { Checkbox } from "../design-system/checkbox";
import { Button } from "../design-system/button";
import { ConfirmationDialog } from "../common/ConfirmationDialog";

interface ReplHeaderProps {
  onSelectAll: () => void;
  onDeselectAll: () => void;
  selectedLength: number;
  copyText: string;
  allSelected: boolean;
  onPlay: () => void;
  onExport: () => void;
  onDelete: () => void;
}

export default function ReplHeader({
  onSelectAll,
  onDeselectAll,
  selectedLength,
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
    <div className="flex justify-between w-full border-b border-slate-100 dark:border-slate-800 flex-wrap pt-4">
      <div
        className={clsx(
          "py-2",
          selectedLength > 0
            ? "text-gray-900 dark:text-white"
            : "text-gray-400 dark:text-gray-600"
        )}
      >
        <Checkbox
          size="sm"
          checked={allSelected}
          onChange={() => {
            if (selectedLength === 0) {
              onSelectAll();
            } else {
              onDeselectAll();
            }
          }}
          indeterminate={selectedLength > 0 && !allSelected}
          label={
            selectedLength > 0 ? `${selectedLength} Selected` : "Select All"
          }
        />
      </div>
      {selectedLength > 0 && (
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
            <Button
              onClick={() => {
                copy(copyText);
                onCommandCopy();
              }}
              variant="quaternary"
              size="sm"
              leftIcon="RiFileCopyLine"
            >
              Copy
            </Button>
          )}

          <ConfirmationDialog
            title={`Delete (${selectedLength}) command${
              selectedLength === 1 ? "" : "s"
            }?`}
            content={`Click confirm to delete the selected command${
              selectedLength === 1 ? "" : "s"
            }.`}
            mainAction={onDelete}
          >
            <Button
              variant="quaternary-red"
              size="sm"
              leftIcon="RiDeleteBinLine"
            >
              Delete
            </Button>
          </ConfirmationDialog>
        </div>
      )}
    </div>
  );
}
