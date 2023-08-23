import { memo, useEffect, useRef, useState } from "react";
import { API } from "../../api/api";
import { Icon } from "../design-system/icon";
import { FormattedFlow, ReplCommand } from "../../helpers/models";
import { SaveFlowModal } from "./SaveFlowModal";
import { useConfirmationDialog } from "../common/ConfirmationDialog";
import { Button } from "../design-system/button";
import ReplHeader from "./ReplHeader";
import CommandInput from "./CommandInput";
import CommandList from "./CommandList";
import { EnterKey } from "../design-system/utils/images";

const getFlowText = (selected: ReplCommand[]): string => {
  return selected
    .map((c) => (c.yaml.endsWith("\n") ? c.yaml : `${c.yaml}\n`))
    .join("");
};

const ReplView = ({
  input,
  onInput,
}: {
  input: string;
  onInput: (input: string) => void;
}) => {
  const listRef = useRef<HTMLElement>();
  const [replError, setReplError] = useState<string | null>(null);
  const [_selected, setSelected] = useState<string[]>([]);
  const [formattedFlow, setFormattedFlow] =
    useState<FormattedFlow | null>(null);
  const { error, repl } = API.repl.useRepl();
  const listSize = repl?.commands.length || 0;
  const previousListSize = useRef(0);

  const [showConfirmationDialog, Dialog] = useConfirmationDialog(() =>
    API.repl.deleteCommands(selectedIds)
  );

  // Scroll to bottom when new commands are added
  useEffect(() => {
    const listSizeChange = listSize - previousListSize.current;
    if (listSizeChange > 0 && listRef.current) {
      listRef.current.scrollTop = listRef.current.scrollHeight;
    }
    previousListSize.current = listSize;
  }, [listSize]);

  if (error) {
    return <div>Error fetching repl</div>;
  }

  if (!repl) {
    return null;
  }

  const selectedCommands = _selected
    .map((id) => repl.commands.find((c) => c.id === id))
    .filter((c): c is ReplCommand => !!c);
  const selectedIds = selectedCommands.map((c) => c.id);

  const runCommand = async () => {
    if (!input) return;
    setReplError(null);
    try {
      await API.repl.runCommand(input);
      onInput("");
    } catch (e: any) {
      setReplError(e.message || "Failed to run command");
    }
  };

  const onReorder = (newOrder: ReplCommand[]) => {
    API.repl.reorderCommands(newOrder.map((c) => c.id));
  };

  const onPlay = () => {
    API.repl.runCommandsById(selectedIds);
  };

  const onExport = () => {
    if (selectedIds.length === 0) return;
    API.repl.formatFlow(selectedIds).then(setFormattedFlow);
  };
  const onDelete = () => {
    showConfirmationDialog();
  };

  const flowText = getFlowText(selectedCommands);

  return (
    <>
      <div className="pt-6 pb-8 px-12 overflow-auto hide-scrollbar">
        {repl.commands.length > 0 ? (
          <div className="flex flex-col">
            <div>
              <ReplHeader
                onSelectAll={() => setSelected(repl.commands.map((c) => c.id))}
                onDeselectAll={() => setSelected([])}
                selected={selectedIds.length}
                allSelected={selectedIds.length === repl.commands.length}
                copyText={flowText}
                onPlay={onPlay}
                onExport={onExport}
                onDelete={onDelete}
              />
            </div>
            <div className="-ml-6 py-5 -mr-1">
              <CommandList
                onReorder={onReorder}
                commands={repl.commands}
                selectedIds={selectedIds}
                updateSelected={(id: string) => {
                  if (selectedIds.includes(id)) {
                    setSelected((prevState: string[]) =>
                      prevState.filter((val: string) => val !== id)
                    );
                  } else {
                    setSelected((prevState: string[]) => [...prevState, id]);
                  }
                }}
              />
            </div>
          </div>
        ) : (
          <div className="flex px-12 flex-col items-center py-4 bg-slate-50 dark:bg-slate-800/50 rounded-xl mb-4">
            <div className="p-4 bg-white dark:bg-slate-900 rounded-3xl mb-4 shadow-xl">
              <Icon iconName="RiCodeLine" size="20" />
            </div>
            <p className="text-center text-base font-semibold mb-1">
              No commands added yet.
            </p>
            <p className="text-center text-sm text-gray-600 dark:text-gray-300">
              Write command below OR select an element, then a command to add it
            </p>
          </div>
        )}
        <form
          className="mb-8 gap-2 flex flex-col relative"
          onSubmit={(e: React.FormEvent) => {
            e.preventDefault();
            runCommand();
          }}
        >
          <CommandInput
            setValue={(value) => {
              setReplError(null);
              onInput(value);
            }}
            value={input}
            error={replError}
            placeholder="Enter a command"
            onSubmit={runCommand}
          />
          <Button
            disabled={!input || !!replError}
            type="submit"
            leftIcon="RiCommandLine"
            size="sm"
            className="absolute bottom-2 right-2 text-lg font-medium"
          >
            +
            <EnterKey className="w-4" />
          </Button>
        </form>
      </div>
      {formattedFlow && (
        <SaveFlowModal
          formattedFlow={formattedFlow}
          onClose={() => {
            setFormattedFlow(null);
          }}
        />
      )}
      <Dialog
        title={`Delete (${selectedIds.length}) command${
          selectedIds.length === 1 ? "" : "s"
        }?`}
        content={`Click confirm to delete the selected command${
          selectedIds.length === 1 ? "" : "s"
        }.`}
      />
    </>
  );
};

export default memo(ReplView);
