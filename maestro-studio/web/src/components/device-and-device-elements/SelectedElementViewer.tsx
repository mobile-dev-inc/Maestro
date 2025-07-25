import { useEffect, useRef } from "react";
import InteractableDevice from "./InteractableDevice";
import { UIElement } from "../../helpers/models";
import { useDeviceContext } from "../../context/DeviceContext";
import { Button } from "../design-system/button";
import copy from "copy-to-clipboard";

export default function SelectedElementViewer({
  uiElement,
}: {
  uiElement: UIElement | null;
}) {
  const { deviceScreen } = useDeviceContext();
  const defaultWrapperSize = 320;
  const containerElementRef = useRef<HTMLDivElement>(null);
  const deviceRef = useRef<HTMLDivElement>(null);

  /**
   * Set The element size and position based on Element selected
   */
  useEffect(() => {
    if (!uiElement || !deviceScreen) {
      return;
    }

    if (deviceRef.current && uiElement.bounds) {
      // Set the wrapper height to be used & increase height if element is big
      let currentWrapperHeight = defaultWrapperSize;
      const maxHeight =
        defaultWrapperSize * (deviceScreen.height / deviceScreen.width) - 4;
      // Chromium Screen (Smaller height)
      if (deviceScreen.height < deviceScreen.width) {
        containerElementRef.current &&
          (containerElementRef.current.style.height = `${maxHeight}px`);
        return;
      }
      // Selected element is large in height
      else if (uiElement.bounds.height > deviceScreen.width) {
        currentWrapperHeight = Math.min(
          defaultWrapperSize * (uiElement.bounds.height / deviceScreen.width) +
            5000,
          maxHeight
        );
        containerElementRef.current &&
          (containerElementRef.current.style.height = `${currentWrapperHeight}px`);
      }
      // Current Element Position Ratio
      const selectedRatio =
        (2 * uiElement.bounds.y + uiElement.bounds.height) /
        (2 * deviceScreen.height);
      // Current Calculated top position for center
      const currentTopPos =
        currentWrapperHeight / 2 -
        1 * deviceRef.current.clientHeight * selectedRatio;
      // Max Value of top
      const maxTopValue =
        (-1 *
          deviceRef.current.clientHeight *
          (deviceScreen.height - deviceScreen.width)) /
        deviceScreen.height;
      // Min Value for top
      const minTopValue = 0;
      // Set position
      deviceRef.current.style.top = `${Math.max(
        Math.min(currentTopPos, minTopValue),
        maxTopValue
      )}px`;
    }
  }, [deviceScreen, uiElement]);

  return (
    <div
      className="hidden md:block"
      style={{ width: defaultWrapperSize + "px" }}
    >
      <ElementHighInfo label="id" value={uiElement?.resourceId} />
      <ElementHighInfo label="text" value={uiElement?.text} />
      <div
        style={{
          minWidth: defaultWrapperSize + "px",
        }}
      >
        <div
          ref={containerElementRef}
          style={{ height: defaultWrapperSize + "px" }}
          className="relative overflow-hidden rounded-lg border border-black/20 dark:border-white/20"
        >
          <div ref={deviceRef} className="absolute -top-1 -left-2 -right-2">
            <InteractableDevice enableGestureControl={false} />
          </div>
        </div>
      </div>
    </div>
  );
}

const ElementHighInfo = ({
  label,
  value,
}: {
  label: string;
  value?: string;
}) => {
  const { setInspectedElement } = useDeviceContext();

  const copyValue = () => {
    if (value) {
      copy(value);
      setInspectedElement(null);
    }
  };

  if (!value) {
    return null;
  }

  return (
    <div className="bg-gray-100 dark:bg-slate-800 pl-3 py-1 pr-1 rounded-lg mb-2 flex gap-3 items-start">
      <p className="text-sm py-1 min-w-[32px]">{label}:</p>
      <p
        className="text-sm font-semibold flex-grow py-1"
        style={{ lineBreak: "anywhere" }}
      >
        {value}
      </p>
      <Button
        onClick={(e) => {
          e.preventDefault();
          e.stopPropagation();
          copyValue();
        }}
        tabIndex={-1}
        variant="quaternary"
        size="sm"
        icon="RiFileCopyLine"
      />
    </div>
  );
};
