import {
  ReactNode,
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import _ from "lodash";
import {
  Dialog,
  DialogTrigger,
  DialogContent,
  DialogHeader,
  DialogDescription,
  DialogTitle,
} from "../design-system/dialog";
import {
  Tabs,
  TabsList,
  TabsTrigger,
  TabsContent,
} from "../design-system/tabs";
import { Button } from "../design-system/button";
import copy from "copy-to-clipboard";
import { Link } from "../design-system/link";
import { Input, InputWrapper } from "../design-system/input";
import Fuse from "fuse.js";
import {
  CommandExample,
  getCommandExamples,
} from "../../helpers/commandExample";
import SelectedElementViewer from "./SelectedElementViewer";
import clsx from "clsx";
import { Icon } from "../design-system/icon";
import { EnterKey } from "../design-system/utils/images";
import KeyboardKey from "../design-system/keyboard-key";
import { useDeviceContext } from "../../context/DeviceContext";

interface ActionModalProps {
  children?: ReactNode;
  onEdit: (example: CommandExample) => void;
  onRun: (example: CommandExample) => void;
}

export default function ActionModal({
  children,
  onEdit,
  onRun,
}: ActionModalProps) {
  const isMac = navigator.platform.toUpperCase().indexOf("MAC") >= 0;
  const { deviceScreen, inspectedElement, setInspectedElement } =
    useDeviceContext();
  const inputElementRef = useRef<HTMLInputElement>(null);
  const prevCommandListRef = useRef<Record<string, CommandExample[]>>();
  const [query, setQuery] = useState<string>("");
  const [selectedTab, setSelectedTab] = useState<string>("Tap");
  const [commandBeingCopied, setCommandBeingCopied] =
    useState<string | null>(null);
  const [commandList, setCommandList] = useState<
    Record<string, CommandExample[]>
  >({});
  const [selectedCommand, setSelectedCommand] = useState<CommandExample>();

  /**
   * Saving the filters and unfilteredCommands
   */
  const { unfilteredExamples, fuse } = useMemo(() => {
    const unfilteredExamples = getCommandExamples(
      deviceScreen,
      inspectedElement
    ).filter((item: CommandExample) => item.status === "available");
    const fuse = new Fuse(unfilteredExamples, { keys: ["title", "content"] });
    return { unfilteredExamples, fuse };
  }, [deviceScreen, inspectedElement]);

  /**
   * Filtering and getting the new command lists
   */
  useEffect(() => {
    const examples = query
      ? fuse.search(query).map((r) => r.item)
      : unfilteredExamples;
    const newCommandList = examples.reduce(
      (acc: Record<string, CommandExample[]>, current: CommandExample) => {
        let key = current.title.split(" > ")[0];
        if (!acc[key]) {
          acc[key] = [];
        }
        // Push the current item to the list
        acc[key].push(current);
        // Use _.uniqBy to filter out duplicates, comparing based on 'id' property
        acc[key] = _.uniqBy(acc[key], "content");
        return acc;
      },
      {}
    );
    setCommandList(newCommandList);
  }, [query, fuse, unfilteredExamples]);

  const updateSelectedCommand = useCallback(
    ({
      val,
      direction,
      currentTab,
    }: {
      val?: string;
      direction?: "first" | "next" | "prev";
      currentTab?: string;
    }) => {
      if (direction === "first") {
        if (commandList && currentTab) {
          setSelectedCommand(commandList[currentTab][0]);
        }
      } else if (direction === "next") {
        setSelectedCommand((prev) => {
          if (prev === undefined || !currentTab) {
            return undefined;
          }
          const commandObject = commandList[currentTab];
          const currentIndex = _.findIndex(commandObject, prev);
          if (commandObject.length <= 1 || currentIndex === -1) {
            return prev;
          } else {
            const nextIndex = (currentIndex + 1) % commandObject.length;
            return commandObject[nextIndex];
          }
        });
      } else if (direction === "prev") {
        setSelectedCommand((prev) => {
          if (prev === undefined || !currentTab) {
            return undefined;
          }
          const commandObject = commandList[currentTab];
          if (commandObject.length <= 1) {
            return prev;
          }
          const currentIndex = _.findIndex(commandObject, prev);
          if (currentIndex === -1) {
            return commandObject[commandObject.length - 1];
          } else {
            const nextIndex =
              (currentIndex - 1 + commandObject.length) % commandObject.length;
            return commandObject[nextIndex];
          }
        });
      }
    },
    [commandList]
  );

  const updateTabs = useCallback(
    ({ val, direction }: { val?: string; direction?: "right" | "left" }) => {
      if (val) {
        setSelectedTab(val);
        updateSelectedCommand({ direction: "first", currentTab: val });
      } else if (direction === "right") {
        const currentCommandList = Object.keys(commandList);
        setSelectedTab((prev) => {
          const currentIndex = currentCommandList.indexOf(prev);
          if (currentCommandList.length <= 1 || currentIndex === -1) {
            return prev;
          } else {
            const val =
              currentCommandList[
                (currentIndex + 1) % currentCommandList.length
              ];
            updateSelectedCommand({ direction: "first", currentTab: val });
            return val;
          }
        });
      } else if (direction === "left") {
        const currentCommandList = Object.keys(commandList);
        setSelectedTab((prev) => {
          if (currentCommandList.length <= 1) {
            return prev;
          }
          const currentIndex = currentCommandList.indexOf(prev);
          if (currentIndex === -1) {
            return currentCommandList[currentCommandList.length - 1];
          } else {
            const val =
              currentCommandList[
                (currentIndex - 1 + currentCommandList.length) %
                  currentCommandList.length
              ];
            updateSelectedCommand({ direction: "first", currentTab: val });
            return val;
          }
        });
      }
    },
    [commandList, updateSelectedCommand]
  );

  const copyCommand = useCallback((command: string) => {
    copy(command);
    setCommandBeingCopied(command);
    setTimeout(() => {
      setCommandBeingCopied(null);
    }, 1000);
  }, []);

  /**
   * Change the tabs on search
   */
  useEffect(() => {
    const prevCommandList = prevCommandListRef.current;
    if (JSON.stringify(prevCommandList) !== JSON.stringify(commandList)) {
      if (document.activeElement === inputElementRef.current) {
        const firstKey: string = Object.keys(commandList)[0];
        if (firstKey) {
          updateTabs({ val: firstKey });
        }
      }
    }
    prevCommandListRef.current = commandList;
  }, [commandList, updateTabs]);

  /**
   * Keyboard Actions
   */
  const handleKeyPress = useCallback(
    (e: KeyboardEvent) => {
      switch (e.code) {
        case "ArrowRight":
          e.preventDefault();
          updateTabs({ direction: "right" });
          break;
        case "ArrowLeft":
          e.preventDefault();
          updateTabs({ direction: "left" });
          break;
        case "ArrowDown":
          e.preventDefault();
          updateSelectedCommand({ direction: "next", currentTab: selectedTab });
          break;
        case "ArrowUp":
          e.preventDefault();
          updateSelectedCommand({ direction: "prev", currentTab: selectedTab });
          break;
        case "Enter":
          e.preventDefault();
          if (
            (isMac && e.metaKey) || // If mac - Command is pressed
            (!isMac && e.ctrlKey && !e.altKey && !e.shiftKey) // Or If not mac - Only control key is pressed
          ) {
            selectedCommand && onRun(selectedCommand);
          } else {
            selectedCommand && onEdit(selectedCommand);
          }
          break;
        case "KeyD":
          e.preventDefault();
          if (
            (isMac && e.metaKey) || // If mac - Command is pressed
            (!isMac && e.ctrlKey && !e.altKey && !e.shiftKey) // Or If not mac - Only control key is pressed
          ) {
            const documentation = selectedCommand?.documentation;
            if (!documentation) return;
            window.open(documentation, "_blank", "noreferrer");
          }
          break;
        case "KeyC":
          if (
            (isMac && e.metaKey) || // If mac - Command is pressed
            (!isMac && e.ctrlKey && !e.altKey && !e.shiftKey) // Or If not mac - Only control key is pressed
          ) {
            // If no text is selected
            if (window && window.getSelection()?.toString() === "") {
              e.preventDefault();
              if (typeof selectedCommand?.content === "string") {
                copyCommand(selectedCommand.content);
              }
            }
          }
          break;
        case "Tab":
          e.preventDefault();
          if (document.activeElement !== inputElementRef.current) {
            inputElementRef.current?.focus();
          }
          break;
      }
    },
    [
      isMac,
      copyCommand,
      onEdit,
      onRun,
      selectedCommand,
      selectedTab,
      updateSelectedCommand,
      updateTabs,
    ]
  );

  /**
   * Add Keyboard Actions
   */
  useEffect(() => {
    function conditionalHandleKeyPress(event: KeyboardEvent) {
      if (!inspectedElement) return;
      handleKeyPress(event);
    }
    if (inspectedElement) {
      const timeoutId = setTimeout(() => {
        window.addEventListener("keydown", conditionalHandleKeyPress);
      }, 0); // Introducing a delay to bypass current event loop

      return () => {
        clearTimeout(timeoutId); // Clear timeout in case the component unmounts before it executes
        window.removeEventListener("keydown", conditionalHandleKeyPress);
      };
    } else {
      window.removeEventListener("keydown", conditionalHandleKeyPress);
    }
  }, [handleKeyPress, inspectedElement]);

  return (
    <Dialog
      open={!!inspectedElement}
      onOpenChange={() => setInspectedElement(null)}
    >
      <DialogTrigger asChild>{children}</DialogTrigger>
      <DialogContent className="sm:max-w-6xl w-[95vw]">
        <KeyboardShortcutsHeader />
        <div className="flex gap-20 p-8 items-stretch">
          <SelectedElementViewer uiElement={inspectedElement} />
          <div className="flex-grow min-w-0">
            <DialogHeader className="pb-4">
              <DialogTitle className="text-left">
                Examples of how you can interact with this element:
              </DialogTitle>
            </DialogHeader>
            <DialogDescription>
              <div className="mb-6">
                <InputWrapper size="sm">
                  <Input
                    ref={inputElementRef}
                    leftIcon="RiSearchLine"
                    placeholder="Search commands"
                    value={query}
                    onChange={(e) => setQuery(e.target.value)}
                  />
                </InputWrapper>
              </div>
              {Object.keys(commandList).length > 0 ? (
                <Tabs
                  value={selectedTab}
                  onValueChange={(val: string) => updateTabs({ val })}
                  defaultValue={Object.keys(commandList)[0]}
                >
                  <TabsList className="flex w-full border-b border-slate-200 dark:border-slate-800 gap-3">
                    {Object.keys(commandList).map((key: string) => {
                      return <TabsTrigger value={key}>{key}</TabsTrigger>;
                    })}
                  </TabsList>
                  {Object.keys(commandList).map((key: string) => {
                    return (
                      <TabsContent value={key} className="py-8">
                        <div className="flex flex-col gap-3">
                          <div className="flex justify-end">
                            <Link
                              href={commandList[key][0].documentation}
                              target="_blank"
                              rel="noopener noreferrer"
                              variant="info"
                              rightIcon="RiArrowRightUpLine"
                              className="mb-3"
                            >
                              View {key} Documentation
                            </Link>
                          </div>
                          {commandList[key].map(
                            (item: CommandExample, index: number) => {
                              return (
                                <ActionCommandListItem
                                  key={`command-${item.title}-${index}`}
                                  selected={
                                    JSON.stringify(selectedCommand) ===
                                    JSON.stringify(item)
                                  }
                                  setSelectedCommand={setSelectedCommand}
                                  isBeingCopied={
                                    item.content === commandBeingCopied
                                  }
                                  copyCommand={copyCommand}
                                  command={item}
                                  onRun={onRun}
                                  onEdit={onEdit}
                                />
                              );
                            }
                          )}
                        </div>
                      </TabsContent>
                    );
                  })}
                </Tabs>
              ) : (
                <div className="pb-4">
                  <div className="p-3 bg-slate-100 dark:bg-slate-800 rounded-md text-center font-semibold text-sm">
                    Couldn't find any command for the element
                  </div>
                </div>
              )}
            </DialogDescription>
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
}

interface ActionCommandListItemProps {
  selected?: boolean;
  command: CommandExample;
  isBeingCopied: boolean;
  setSelectedCommand: (example: CommandExample) => void;
  copyCommand: (command: string) => void;
  onEdit: (example: CommandExample) => void;
  onRun: (example: CommandExample) => void;
}

const ActionCommandListItem = ({
  selected = false,
  command,
  isBeingCopied,
  setSelectedCommand,
  copyCommand,
  onRun,
  onEdit,
}: ActionCommandListItemProps) => {
  const commandItemRef = useRef<HTMLDivElement>(null);

  /**
   * Scroll selected element in view
   */
  useEffect(() => {
    if (selected && commandItemRef.current) {
      commandItemRef.current?.scrollIntoView({ behavior: "smooth" });
    }
  }, [selected]);

  return (
    <div
      ref={commandItemRef}
      onClick={() => setSelectedCommand(command)}
      onDoubleClick={() => onRun(command)}
      className={clsx(
        `relative border rounded-md flex gap-2 overflow-hidden cursor-pointer group`,
        selected
          ? "border-purple-500 ring-4 ring-offset-0 ring-purple-100/20"
          : "border-slate-200 dark:border-slate-800"
      )}
    >
      <pre className="overflow-auto font-mono text-gray-700 dark:text-white flex-grow pt-3 pb-5 pl-3 pr-40 hide-scrollbar">
        {command.content}
      </pre>
      <div className="bg-gradient-to-r from-transparent to-white dark:to-slate-900 w-80 absolute top-0 right-0 bottom-0 pointer-events-none opacity-0 group-hover:opacity-100 transition-all" />
      <div className="absolute flex gap-2 right-2 top-2">
        <Button
          onClick={(e) => {
            e.preventDefault();
            e.stopPropagation();
            onRun(command);
          }}
          className="opacity-0 group-hover:opacity-100"
          variant="primary"
          size="sm"
          icon="RiPlayLine"
        />
        <Button
          onClick={(e) => {
            e.preventDefault();
            e.stopPropagation();
            onEdit(command);
          }}
          className="opacity-0 group-hover:opacity-100"
          variant="secondary"
          size="sm"
          icon="RiCodeLine"
        />
        {isBeingCopied ? (
          <Button variant="primary-green" size="sm" icon="RiCheckLine" />
        ) : (
          <Button
            onClick={(e) => {
              e.preventDefault();
              e.stopPropagation();
              copyCommand(command.content);
            }}
            className="opacity-0 group-hover:opacity-100"
            variant="tertiary"
            size="sm"
            icon="RiFileCopyLine"
          />
        )}
      </div>
    </div>
  );
};

const KeyboardShortcutsHeader = () => {
  const isMac = navigator.platform.toUpperCase().indexOf("MAC") >= 0;

  return (
    <div className="hidden md:flex pl-8 pr-12 py-3 border-b border-slate-200 dark:border-slate-800 gap-x-8 gap-y-3 flex-wrap">
      <div className="flex gap-2">
        <p>Navigate:</p>
        <div className="flex gap-1">
          <KeyboardKey>
            <Icon iconName="RiArrowUpLine" size="16" />
          </KeyboardKey>
          <KeyboardKey>
            <Icon iconName="RiArrowDownLine" size="16" />
          </KeyboardKey>
          <KeyboardKey>
            <Icon iconName="RiArrowLeftLine" size="16" />
          </KeyboardKey>
          <KeyboardKey>
            <Icon iconName="RiArrowRightLine" size="16" />
          </KeyboardKey>
        </div>
      </div>
      <div className="flex gap-2">
        <p>Copy:</p>
        <div className="flex gap-1">
          {isMac ? (
            <KeyboardKey>
              <Icon iconName="RiCommandLine" size="16" />
            </KeyboardKey>
          ) : (
            <KeyboardKey>Ctrl</KeyboardKey>
          )}
          <KeyboardKey>C</KeyboardKey>
        </div>
      </div>
      <div className="flex gap-2">
        <p>Doc:</p>
        <div className="flex gap-1">
          {isMac ? (
            <KeyboardKey>
              <Icon iconName="RiCommandLine" size="16" />
            </KeyboardKey>
          ) : (
            <KeyboardKey>Ctrl</KeyboardKey>
          )}
          <KeyboardKey>D</KeyboardKey>
        </div>
      </div>
      <div className="flex gap-2">
        <p>Edit:</p>
        <div className="flex gap-1">
          <KeyboardKey>
            <EnterKey className="w-4" />
          </KeyboardKey>
        </div>
      </div>
      <div className="flex gap-2">
        <p>Run:</p>
        <div className="flex gap-1">
          {isMac ? (
            <KeyboardKey>
              <Icon iconName="RiCommandLine" size="16" />
            </KeyboardKey>
          ) : (
            <KeyboardKey>Ctrl</KeyboardKey>
          )}
          <KeyboardKey>
            <EnterKey className="w-4" />
          </KeyboardKey>
        </div>
      </div>
      <div className="flex gap-2">
        <p>Focus Search:</p>
        <div className="flex gap-1">
          <KeyboardKey>Tab</KeyboardKey>
        </div>
      </div>
      <div className="flex gap-2">
        <p>Close:</p>
        <div className="flex gap-1">
          <KeyboardKey>Esc</KeyboardKey>
        </div>
      </div>
    </div>
  );
};
