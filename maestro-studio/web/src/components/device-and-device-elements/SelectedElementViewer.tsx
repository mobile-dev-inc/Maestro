import { useEffect, useRef } from "react";
import InteractableDevice from "./InteractableDevice";
import { DeviceScreen, UIElement } from "../../helpers/models";

export default function SelectedElementViewer({
  deviceScreen,
  uiElement,
}: {
  deviceScreen: DeviceScreen;
  uiElement: UIElement;
}) {
  const defaultWrapperSize = 320;
  const containerElementRef = useRef<HTMLDivElement>(null);
  const deviceRef = useRef<HTMLDivElement>(null);

  /**
   * Set The element size and position based on Element selected
   */
  useEffect(() => {
    if (deviceRef.current && uiElement.bounds) {
      // Set the wrapper height to be used & increase height if element is big
      let currentWrapperHeight = defaultWrapperSize;
      if (uiElement.bounds.height > currentWrapperHeight) {
        currentWrapperHeight = Math.min(
          defaultWrapperSize * (uiElement.bounds.height / deviceScreen.width) +
            100,
          deviceScreen.height
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
  }, [deviceScreen.height, deviceScreen.width, uiElement.bounds]);

  return (
    <div
      className="hidden md:block"
      style={{
        width: defaultWrapperSize + "px",
        minWidth: defaultWrapperSize + "px",
      }}
    >
      <div
        ref={containerElementRef}
        style={{ height: defaultWrapperSize + "px" }}
        className="relative overflow-hidden rounded-lg border border-black/20 dark:border-white/20"
      >
        <div ref={deviceRef} className="absolute -top-1 -left-1 -right-1">
          <InteractableDevice
            enableGestureControl={false}
            deviceScreen={deviceScreen}
            inspectedElement={uiElement}
          />
        </div>
      </div>
    </div>
  );
}
