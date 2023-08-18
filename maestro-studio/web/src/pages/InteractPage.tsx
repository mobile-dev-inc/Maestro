import { useState } from "react";
import clsx from "clsx";

import InteractableDevice from "../components/device-and-device-elements/InteractableDevice";
import ReplView from "../components/commands/ReplView";
import { UIElement } from "../helpers/models";
import { API } from "../api/api";
import ActionModal from "../components/device-and-device-elements/ActionModal";
import { CommandExample } from "../helpers/commandExample";
import ElementsPanel from "../components/device-and-device-elements/ElementsPanel";
import { Button } from "../components/design-system/button";
import DeviceWrapperAspectRatio from "../components/device-and-device-elements/DeviceWrapperAspectRatio";

const InteractPage = () => {
  const [showElementsPanel, setShowElementsPanel] = useState<boolean>(false);
  const { deviceScreen } = API.useDeviceScreen();
  const [input, setInput] = useState("");
  const [footerHint, setFooterHint] = useState<string | null>();
  const [hoveredElement, setHoveredElement] = useState<UIElement | null>(null);
  const [inspectedElementId, setInspectedElementId] =
    useState<string | null>(null);
  const inspectedElement =
    deviceScreen?.elements?.find((e) => e.id === inspectedElementId) || null;

  const onEdit = (example: CommandExample) => {
    if (example.status === "unavailable") return;
    setInput(example.content.trim());
    setInspectedElementId(null);

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
    setInspectedElementId(null);
  };

  if (!deviceScreen) return null;

  return (
    <div className="flex h-full overflow-hidden">
      {showElementsPanel && (
        <ElementsPanel
          hoveredElement={hoveredElement}
          setHoveredElement={setHoveredElement}
          deviceScreen={deviceScreen}
          onElementSelected={(element: UIElement | null) => {
            if (!element) return;
            setInspectedElementId(element?.id);
          }}
          closePanel={() => setShowElementsPanel(false)}
          onHint={setFooterHint}
        />
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
        <DeviceWrapperAspectRatio
          aspectRatio={deviceScreen.width / deviceScreen.height}
        >
          <InteractableDevice
            hoveredElement={hoveredElement}
            setHoveredElement={setHoveredElement}
            deviceScreen={deviceScreen}
            onHint={setFooterHint}
            inspectedElement={inspectedElement}
            onInspectElement={(e) => setInspectedElementId(e?.id || null)}
          />
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
        <ReplView input={input} onInput={setInput} />
      </div>
      {inspectedElement && (
        <ActionModal
          deviceScreen={deviceScreen}
          uiElement={inspectedElement}
          onEdit={onEdit}
          onRun={onRun}
          open={!!inspectedElement}
          onClose={() => setInspectedElementId(null)}
        />
      )}
    </div>
  );
};

export default InteractPage;
