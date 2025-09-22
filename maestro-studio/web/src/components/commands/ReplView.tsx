import { memo, useEffect, useRef, useState } from "react";
import { API } from "../../api/api";
import { Icon } from "../design-system/icon";
import { FormattedFlow, ReplCommand } from "../../helpers/models";
import { SaveFlowModal } from "./SaveFlowModal";
import ReplHeader from "./ReplHeader";
import CommandList from "./CommandList";
import CommandCreator from "./CommandCreator";
import { useDeviceContext } from "../../context/DeviceContext";
import { useRepl } from '../../context/ReplContext';

const getFlowText = (selected: ReplCommand[]): string => {
  let combinedYaml = '';
  
  // Process each command independently
  selected.forEach(command => {
    // Get the first line of the command YAML
    const lines = command.yaml.split('\n');
    if (lines.length === 0) return;
    
    // Process the first line (command line)
    let firstLine = lines[0].trim();
    if (!firstLine.startsWith('-')) {
      firstLine = `- ${firstLine}`;
    }
    combinedYaml += firstLine + '\n';
    
    // Process the parameter lines
    for (let i = 1; i < lines.length; i++) {
      if (lines[i].trim()) {
        // Parameters should have appropriate indentation.
        // Given we're adding 2 characters for '- ', we add 2 spaces to subsequent lines.
        combinedYaml += '  ' + lines[i] + '\n';
      }
    }
  });
  
  return combinedYaml;
};

const ReplView = () => {
  const { currentCommandValue, setCurrentCommandValue } = useDeviceContext();
  const listRef = useRef<HTMLElement>();
  const [_selected, setSelected] = useState<string[]>([]);
  const [formattedFlow, setFormattedFlow] =
    useState<FormattedFlow | null>(null);
  const { repl, errorMessage, setErrorMessage, reorderCommands, deleteCommands, runCommandYaml, runCommandIds } = useRepl();
  const listSize = repl?.commands.length || 0;
  const previousListSize = useRef(0);

  // Scroll to bottom when new commands are added
  useEffect(() => {
    const listSizeChange = listSize - previousListSize.current;
    if (listSizeChange > 0 && listRef.current) {
      listRef.current.scrollTop = listRef.current.scrollHeight;
    }
    previousListSize.current = listSize;
  }, [listSize]);

  if (!repl) {
    return null;
  }

  const selectedCommands = _selected
    .map((id) => repl.commands.find((c) => c.id === id))
    .filter((c): c is ReplCommand => !!c);
  const selectedIds = selectedCommands.map((c) => c.id);

  // TODO handle invalid yaml
  const onCommandSubmit = async () => {
    if (!currentCommandValue) return;
    const success = await runCommandYaml(currentCommandValue);
    if (success) setCurrentCommandValue('');
  };

  const onReorder = (newOrder: ReplCommand[]) => {
    reorderCommands(newOrder.map((c) => c.id));
  };

  const onPlay = async () => {
    await runCommandIds(selectedIds);
  };

  const onExport = () => {
    if (selectedIds.length === 0) return;
    const commands = selectedIds.map(id => repl.commands.find(command => command.id === id)?.yaml).filter(Boolean) as string[]
    API.formatFlow(commands).then(setFormattedFlow);
  };

  const onDelete = () => {
    deleteCommands(selectedIds);
  };

  const flowText = getFlowText(selectedCommands);

  return (
    <>
      {repl.commands.length > 0 ? (
        <div className="flex flex-col h-full">
          <div className="px-12">
            <ReplHeader
              onSelectAll={() => setSelected(repl.commands.map((c) => c.id))}
              onDeselectAll={() => setSelected([])}
              selectedLength={selectedIds.length}
              allSelected={selectedIds.length === repl.commands.length}
              copyText={flowText}
              onPlay={onPlay}
              onExport={onExport}
              onDelete={onDelete}
            />
          </div>
          <div className="px-12 overflow-auto pb-20 hide-scrollbar">
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
            <CommandCreator
              onSubmit={onCommandSubmit}
              error={errorMessage}
              setError={setErrorMessage}
            />
          </div>
        </div>
      ) : (
        <div className="px-12 py-6">
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
          <CommandCreator
            onSubmit={onCommandSubmit}
            error={errorMessage}
            setError={setErrorMessage}
          />
        </div>
      )}
      {/* <div className="px-12">
        
      </div> */}
      {formattedFlow && (
        <SaveFlowModal
          formattedFlow={formattedFlow}
          onClose={() => {
            setFormattedFlow(null);
          }}
        />
      )}
    </>
  );
};

export default memo(ReplView);
