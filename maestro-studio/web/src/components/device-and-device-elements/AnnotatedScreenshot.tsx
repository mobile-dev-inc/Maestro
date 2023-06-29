import { DeviceScreen, UIElement } from "../../helpers/models";
import React, { CSSProperties, useEffect, useRef, useState } from "react";
import useMouse, { MousePosition } from "@react-hook/mouse-position";

type AnnotationState = "default" | "hidden" | "hovered" | "selected";

const Annotation = ({
  element,
  deviceWidth,
  deviceHeight,
  state,
  onClick,
}: {
  element: UIElement;
  deviceWidth: number;
  deviceHeight: number;
  state: AnnotationState;
  onClick: () => void;
}) => {
  if (!element.bounds || state === "hidden") return null;
  const { x, y, width, height } = element.bounds;
  const l = `${(x / deviceWidth) * 100}%`;
  const t = `${(y / deviceHeight) * 100}%`;
  const w = `${(width / deviceWidth) * 100}%`;
  const h = `${(height / deviceHeight) * 100}%`;

  let className = "border border-dashed border-pink-400/20";
  let style: CSSProperties = {};

  if (state === "hovered") {
    className = "border-4 border-blue-500 active:active:bg-blue-400/40 z-10";
    style = {
      boxShadow: "0 0 0 9999px rgba(244, 114, 182, 0.4)",
    };
  } else if (state === "selected") {
    className = "border-4 border-blue-500 z-10";
    style = {
      boxShadow: "0 0 0 9999px rgba(96, 165, 250, 0.4)",
    };
  }

  return (
    <>
      <div
        className={`absolute ${className} shadow-pink-400`}
        style={{
          left: l,
          top: t,
          width: w,
          height: h,
          ...style,
        }}
        onClick={onClick}
      />
    </>
  );
};

const Crosshairs = ({
  cx,
  cy,
  color,
}: {
  cx: number;
  cy: number;
  color: string;
}) => {
  return (
    <>
      <div
        className={`absolute z-20 w-[1px] h-full ${color} -translate-x-1/2 pointer-events-none`}
        style={{
          top: 0,
          bottom: 0,
          left: `${cx * 100}%`,
        }}
      />
      <div
        className={`absolute z-20 h-[1px] w-full ${color} -translate-y-1/2 pointer-events-none`}
        style={{
          left: 0,
          right: 0,
          top: `${cy * 100}%`,
        }}
      />
    </>
  );
};

const pointInBounds = (
  boundsX: number,
  boundsY: number,
  boundsWidth: number,
  boundsHeight: number,
  x: number,
  y: number
) => {
  return (
    x >= boundsX &&
    x <= boundsX + boundsWidth &&
    y >= boundsY &&
    y <= boundsY + boundsHeight
  );
};

const getHoveredElement = (
  deviceScreen: DeviceScreen,
  mouse: MousePosition
): UIElement | null => {
  const hoveredList = deviceScreen.elements.filter((element) => {
    if (!element.bounds) return false;
    const {
      x: boundsX,
      y: boundsY,
      width: boundsWidth,
      height: boundsHeight,
    } = element.bounds;
    const { x: mouseX, y: mouseY, elementWidth, elementHeight } = mouse;
    if (
      mouseX === null ||
      mouseY === null ||
      elementWidth === null ||
      elementHeight === null
    )
      return false;
    return pointInBounds(
      boundsX / deviceScreen.width,
      boundsY / deviceScreen.height,
      boundsWidth / deviceScreen.width,
      boundsHeight / deviceScreen.height,
      mouseX / elementWidth,
      mouseY / elementHeight
    );
  });
  if (hoveredList.length === 0) return null;
  return hoveredList.sort((a, b) => {
    if (!a.bounds && !b.bounds) return 0;
    if (!a.bounds) return 1;
    if (!b.bounds) return -1;
    return a.bounds.width * a.bounds.height - b.bounds.width * b.bounds.height;
  })[0];
};

export const AnnotatedScreenshot = ({
  deviceScreen,
  selectedElement,
  onElementSelected,
  hoveredElement,
  onHover,
  annotationsEnabled = true,
}: {
  deviceScreen: DeviceScreen;
  selectedElement: UIElement | null;
  onElementSelected: (element: UIElement | null) => void;
  hoveredElement: UIElement | null;
  onHover: (element: UIElement | null, mouse: MousePosition | null) => void;
  annotationsEnabled?: boolean;
}) => {
  const ref = useRef(null);
  const [hasMouseLeft, setHasMouseLeft] = useState<boolean>(false);
  const mouse = useMouse(ref, { enterDelay: 100, leaveDelay: 100 });

  useEffect(() => {
    if (mouse.isOver) {
      setHasMouseLeft(false);
      if (annotationsEnabled) {
        const hoveredElement = getHoveredElement(deviceScreen, mouse);
        onHover(hoveredElement, mouse);
      } else {
        onHover(null, mouse);
      }
    } else if (!hasMouseLeft) {
      setHasMouseLeft(true);
      onHover(null, null);
    }
  }, [deviceScreen, mouse, onHover, annotationsEnabled, hasMouseLeft]);

  const createAnnotation = (element: UIElement) => {
    let state: AnnotationState = "default";
    if (selectedElement?.id === element?.id) {
      state = "selected";
    } else if (selectedElement !== null) {
      state = "hidden";
    } else if (hoveredElement?.id === element?.id) {
      state = "hovered";
    }
    return (
      <Annotation
        key={element.id}
        element={element}
        deviceWidth={deviceScreen.width}
        deviceHeight={deviceScreen.height}
        state={state}
        onClick={() => {
          if (selectedElement) {
            onElementSelected(null);
          } else {
            onElementSelected(element);
          }
        }}
      />
    );
  };

  const focusedElement = selectedElement || hoveredElement;

  const createCrosshairs = () => {
    if (annotationsEnabled || !mouse.isOver) {
      const bounds = focusedElement?.bounds;
      if (!bounds) return null;
      const { x, y, width, height } = bounds;
      const cx = (x + width / 2) / deviceScreen.width;
      const cy = (y + height / 2) / deviceScreen.height;
      const color =
        focusedElement === selectedElement ? "bg-pink-400" : "bg-blue-400";
      return <Crosshairs cx={cx} cy={cy} color={color} />;
    } else {
      if (mouse.x && mouse.y && mouse.elementWidth && mouse.elementHeight) {
        return (
          <Crosshairs
            cx={mouse.x / mouse.elementWidth}
            cy={mouse.y / mouse.elementHeight}
            color="bg-blue-400"
          />
        );
      } else {
        return null;
      }
    }
  };

  return (
    <div
      ref={ref}
      className="relative overflow-hidden"
      style={{
        aspectRatio: deviceScreen.width / deviceScreen.height,
      }}
      onClick={() => {
        if (selectedElement) onElementSelected(null);
      }}
    >
      <img
        className="h-full pointer-events-none select-none"
        src={deviceScreen.screenshot}
        alt="screenshot"
      />
      {createCrosshairs()}
      {(annotationsEnabled || !mouse.isOver) && (
        <div className="w-full h-full">
          {deviceScreen.elements.map(createAnnotation)}
        </div>
      )}
    </div>
  );
};
