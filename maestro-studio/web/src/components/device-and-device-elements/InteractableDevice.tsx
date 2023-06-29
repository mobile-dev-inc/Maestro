import React, { MouseEventHandler, useState } from "react";
import { DeviceScreen, DivProps, UIElement } from "../../helpers/models";
import { API } from "../../api/api";
import { AnnotatedScreenshot } from "./AnnotatedScreenshot";
import { MousePosition } from "@react-hook/mouse-position";
import { isHotkeyPressed } from "react-hotkeys-hook";

type GestureEvent = {
  x: number;
  y: number;
  timestamp: number;
};

const createGestureEvent = (
  e: React.MouseEvent<HTMLDivElement, MouseEvent>
): GestureEvent => {
  const { offsetLeft, offsetTop } = e.currentTarget;
  return {
    x: e.pageX - offsetLeft,
    y: e.pageY - offsetTop,
    timestamp: e.timeStamp,
  };
};

const GestureDiv = ({
  onTap,
  onSwipe,
  gesturesEnabled = true,
  ...rest
}: {
  onTap: (x: number, y: number) => void;
  onSwipe: (
    startX: number,
    startY: number,
    endX: number,
    endY: number,
    duration: number
  ) => void;
  gesturesEnabled?: boolean;
} & DivProps) => {
  const [start, setStart] = useState<GestureEvent>();

  const onStart: MouseEventHandler<HTMLDivElement> = (e) => {
    if (!gesturesEnabled) return;
    setStart(createGestureEvent(e));
  };

  const onEnd: MouseEventHandler<HTMLDivElement> = (e) => {
    if (!gesturesEnabled || !start) return;
    const end = createGestureEvent(e);

    const { width: clientWidth, height: clientHeight } =
      e.currentTarget.getBoundingClientRect();

    const duration = end.timestamp - start.timestamp;
    const distance = Math.hypot(end.x - start.x, end.y - start.y);

    if (duration < 100 || distance < 10) {
      onTap(start.x / clientWidth, start.y / clientHeight);
    } else {
      onSwipe(
        start.x / clientWidth,
        start.y / clientHeight,
        end.x / clientWidth,
        end.y / clientHeight,
        duration
      );
    }
  };

  const onCancel = () => {
    setStart(undefined);
  };

  return (
    <div
      {...rest}
      onMouseDown={onStart}
      onMouseUp={onEnd}
      onMouseLeave={onCancel}
    />
  );
};

const useMetaKeyDown = () => {
  return isHotkeyPressed("meta");
};

const toPercent = (n: number, total: number) =>
  `${Math.round((100 * n) / total)}%`;

const InteractableDevice = ({
  hoveredElement,
  setHoveredElement,
  deviceScreen,
  onHint,
  inspectedElement,
  onInspectElement,
}: {
  hoveredElement: UIElement | null;
  setHoveredElement: (element: UIElement | null) => void;
  deviceScreen: DeviceScreen;
  onHint: (hint: string | null) => void;
  inspectedElement: UIElement | null;
  onInspectElement: (element: UIElement | null) => void;
}) => {
  const metaKeyDown = useMetaKeyDown();

  const onTapGesture = (x: number, y: number) => {
    API.repl.runCommand(`
      tapOn:
        point: "${Math.round(100 * x)}%,${Math.round(100 * y)}%"
    `);
  };

  const onSwipeGesture = (
    startX: number,
    startY: number,
    endX: number,
    endY: number,
    duration: number
  ) => {
    const startXPercent = Math.round(startX * 100);
    const startYPercent = Math.round(startY * 100);
    const endXPercent = Math.round(endX * 100);
    const endYPercent = Math.round(endY * 100);
    API.repl.runCommand(`
      swipe:
        start: "${startXPercent}%,${startYPercent}%"
        end: "${endXPercent}%,${endYPercent}%"
        duration: ${Math.round(duration)}
    `);
  };

  const getMouseHint = (mouse: MousePosition): string | null => {
    if (
      typeof mouse.x !== "number" ||
      typeof mouse.y !== "number" ||
      typeof mouse.elementWidth !== "number" ||
      typeof mouse.elementHeight !== "number"
    ) {
      return null;
    }
    const x = toPercent(mouse.x, mouse.elementWidth);
    const y = toPercent(mouse.y, mouse.elementHeight);
    return `${x}, ${y}`;
  };

  const getElementHint = (element: UIElement): string => {
    if (element.resourceId) return element.resourceId;
    if (element.text) return element.text;
    if (!element.bounds) return "";
    const cx = toPercent(
      element.bounds.x + element.bounds.width / 2,
      deviceScreen.width
    );
    const cy = toPercent(
      element.bounds.y + element.bounds.height / 2,
      deviceScreen.height
    );
    return `${cx}, ${cy}`;
  };

  const onHover = (element: UIElement | null, mouse: MousePosition | null) => {
    const mouseHint = mouse == null ? null : getMouseHint(mouse);
    const elementHint = element == null ? null : getElementHint(element);
    onHint(elementHint || mouseHint);
    setHoveredElement(element?.id ? element : null);
  };

  return (
    <GestureDiv
      className="border-2 box-content border-pink-500 rounded-lg overflow-hidden w-full"
      style={{
        aspectRatio: deviceScreen.width / deviceScreen.height,
      }}
      onTap={onTapGesture}
      onSwipe={onSwipeGesture}
      gesturesEnabled={metaKeyDown}
    >
      <AnnotatedScreenshot
        deviceScreen={deviceScreen}
        selectedElement={inspectedElement}
        onElementSelected={onInspectElement}
        hoveredElement={hoveredElement}
        onHover={onHover}
        annotationsEnabled={!metaKeyDown}
      />
    </GestureDiv>
  );
};

export default InteractableDevice;
