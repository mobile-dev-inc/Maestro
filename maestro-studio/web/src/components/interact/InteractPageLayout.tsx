import { useState } from "react";
import clsx from "clsx";

import InteractableDevice from "../device-and-device-elements/InteractableDevice";
import ReplView from "../commands/ReplView";
import { API } from "../../api/api";
import ActionModal from "../device-and-device-elements/ActionModal";
import { Button } from "../design-system/button";
import { CommandExample } from "../../helpers/commandExample";
import ElementsPanel from "../device-and-device-elements/ElementsPanel";
import DeviceWrapperAspectRatio from "../device-and-device-elements/DeviceWrapperAspectRatio";
import { useDeviceContext } from "../../context/DeviceContext";
import { Spinner } from "../design-system/spinner";

const InteractPageLayout = () => {
  const {
    isLoading,
    deviceScreen,
    footerHint,
    setInspectedElement,
    setCurrentCommandValue,
  } = useDeviceContext();

  const [showElementsPanel, setShowElementsPanel] = useState<boolean>(false);

  const onEdit = (example: CommandExample) => {
    if (example.status === "unavailable") return;
    setCurrentCommandValue(example.content.trim());
    setInspectedElement(null);
    // find textarea by id and focus on it if it exists
    setTimeout(() => {
      const textarea = document.getElementById("commandInputBox");
      if (textarea) {
        textarea.focus();
      }
    }, 0);
  };

  const onRun = (example: CommandExample) => {
    if (example.status === "unavailable") return;
    API.repl.runCommand(example.content);
    setInspectedElement(null);
  };

  if (isLoading)
    return (
      <div className="flex items-center justify-center h-full">
        <Spinner size="32" />
      </div>
    );

  if (!deviceScreen) return null;

  return (
    <div className="flex h-full overflow-hidden">
      {showElementsPanel && (
        <ElementsPanel closePanel={() => setShowElementsPanel(false)} />
      )}
      <div
        className={clsx(
          "px-8 pt-6 pb-7 bg-white dark:bg-slate-900 basis-1/2  relative gap-4 flex flex-col",
          showElementsPanel
            ? "lg:basis-4/12 max-w-[33.333333%]"
            : "lg:basis-5/12 max-w-[41.666667%]"
        )}
      >
        {!showElementsPanel && (
          <Button
            variant="secondary"
            leftIcon="RiSearchLine"
            className="w-full min-h-[32px]"
            onClick={() => setShowElementsPanel(true)}
          >
            Search Elements with Text or Id
          </Button>
        )}
        <DeviceWrapperAspectRatio>
          <InteractableDevice />
        </DeviceWrapperAspectRatio>
        <p className="text-xs text-center">
          Hold CMD (⌘) down to freely tap and swipe on the device screen
        </p>
        {footerHint && (
          <div className="absolute bottom-0 left-0 right-0 text-xs text-center bg-slate-100 dark:bg-slate-800 dark:text-white h-auto text-slate-800 overflow-hidden">
            {footerHint}
          </div>
        )}
      </div>
      <div className="flex flex-col flex-1 h-full overflow-hidden border-l border-slate-200 dark:border-slate-800 relative dark:bg-slate-900 dark:text-white">
        <ReplView />
      </div>
      <ActionModal onEdit={onEdit} onRun={onRun} />
    </div>
  );
};

export default InteractPageLayout;
