import { ReactNode, useEffect, useMemo, useRef, useState } from "react";
import {
  Dialog,
  DialogTrigger,
  DialogContent,
  DialogHeader,
  DialogDescription,
  DialogTitle,
} from "../design-system/dialog";
import { DeviceScreen, UIElement } from "../../helpers/models";
import {
  Tabs,
  TabsList,
  TabsTrigger,
  TabsContent,
} from "../design-system/tabs";
import { Button } from "../design-system/button";
import CopyToClipboard from "react-copy-to-clipboard";
import { Link } from "../design-system/link";
import Fuse from "fuse.js";

import {
  CommandExample,
  getCommandExamples,
} from "../../helpers/commandExample";
import { Input } from "../design-system/input";

interface ActionModalProps {
  children?: ReactNode;
  deviceScreen: DeviceScreen;
  uiElement: UIElement;
  open: boolean;
  onEdit: (example: CommandExample) => void;
  onRun: (example: CommandExample) => void;
  onClose: () => void;
}

export default function ActionModal({
  children,
  deviceScreen,
  uiElement,
  open,
  onClose,
  onEdit,
  onRun,
}: ActionModalProps) {
  const inputElementRef = useRef(null);
  const [query, setQuery] = useState<string>("");
  const [selectedTab, setSelectedTab] = useState<string>("Tap");

  /**
   * Filtering and getting the command lists
   */
  const commandList: Record<string, CommandExample[]> = useMemo(() => {
    const unfilteredExamples = getCommandExamples(
      deviceScreen,
      uiElement
    ).filter((item: CommandExample) => item.status === "available");
    const fuse = new Fuse(unfilteredExamples, { keys: ["title", "content"] });
    const examples = query
      ? fuse.search(query).map((r) => r.item)
      : unfilteredExamples;
    const newCommandList = examples.reduce(
      (acc: Record<string, CommandExample[]>, current: CommandExample) => {
        let key = current.title.split(" > ")[0];
        if (!acc[key]) {
          acc[key] = [];
        }
        acc[key].push(current);
        return acc;
      },
      {}
    );
    return newCommandList;
  }, [deviceScreen, uiElement, query]);

  /**
   * Change the tabs in case commandList changes
   */
  useEffect(() => {
    if (document.activeElement === inputElementRef.current) {
      const firstKey: string = Object.keys(commandList)[0];
      if (firstKey) {
        setSelectedTab(firstKey);
      }
    }
  }, [commandList]);

  return (
    <Dialog open={open} onOpenChange={onClose}>
      <DialogTrigger asChild>{children}</DialogTrigger>
      <DialogContent>
        <DialogHeader className="px-6 py-4">
          <DialogTitle>
            Examples of how you can interact with this element:
          </DialogTitle>
        </DialogHeader>
        <DialogDescription>
          <div className="px-6 pb-6">
            <Input
              ref={inputElementRef}
              size="sm"
              leftIcon="RiSearchLine"
              placeholder="Search commands"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
            />
          </div>
          {Object.keys(commandList).length > 0 ? (
            <Tabs
              value={selectedTab}
              onValueChange={(val: string) => setSelectedTab(val)}
              defaultValue={Object.keys(commandList)[0]}
            >
              <TabsList className="flex w-full border-b border-slate-200 dark:border-slate-800 gap-3 px-6">
                {Object.keys(commandList).map((key: string) => {
                  return <TabsTrigger value={key}>{key}</TabsTrigger>;
                })}
              </TabsList>
              {Object.keys(commandList).map((key: string) => {
                return (
                  <TabsContent value={key} className="px-6 py-8">
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
            <div className="px-6 pb-4">
              <div className="p-3 bg-slate-100 dark:bg-slate-800 rounded-md text-center font-semibold text-sm">
                Coundn't find any command for the element
              </div>
            </div>
          )}
        </DialogDescription>
      </DialogContent>
    </Dialog>
  );
}

interface ActionCommandListItemProps {
  command: CommandExample;
  onEdit: (example: CommandExample) => void;
  onRun: (example: CommandExample) => void;
}

const ActionCommandListItem = ({
  command,
  onRun,
  onEdit,
}: ActionCommandListItemProps) => {
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
    <div className="relative border border-slate-200 dark:border-slate-800 rounded-md flex gap-2 overflow-hidden">
      <pre className="overflow-x-scroll font-mono text-gray-700 dark:text-white flex-grow pt-3 pb-5 pl-3 pr-40">
        {command.content}
      </pre>
      <div className="bg-gradient-to-r from-transparent to-white dark:to-slate-900 w-80 absolute top-0 right-0 bottom-0 pointer-events-none" />
      <div className="absolute flex gap-2 right-2 top-2">
        <Button
          onClick={() => onRun(command)}
          variant="primary"
          size="sm"
          icon="RiPlayLine"
        />
        <Button
          onClick={() => onEdit(command)}
          variant="secondary"
          size="sm"
          icon="RiCodeLine"
        />
        {commandCopied ? (
          <Button variant="primary-green" size="sm" icon="RiCheckLine" />
        ) : (
          <CopyToClipboard text={command.content} onCopy={onCommandCopy}>
            <Button variant="tertiary" size="sm" icon="RiFileCopyLine" />
          </CopyToClipboard>
        )}
      </div>
    </div>
  );
};
