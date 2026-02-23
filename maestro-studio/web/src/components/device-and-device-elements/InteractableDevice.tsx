import React, { MouseEventHandler, useState } from "react";
import { DivProps } from "../../helpers/models";
import { AnnotatedScreenshot } from "./AnnotatedScreenshot";
import { isHotkeyPressed } from "react-hotkeys-hook";
import { useDeviceContext } from "../../context/DeviceContext";
import clsx from "clsx";
import { useRepl } from '../../context/ReplContext';

const useMetaKeyDown = () => {
  return isHotkeyPressed("meta");
};

export default function InteractableDevice({
  enableGestureControl = true,
}: {
  enableGestureControl?: boolean;
}) {
  const { deviceScreen } = useDeviceContext();
  const { runCommandYaml } = useRepl();
  const metaKeyDown = useMetaKeyDown();
  const isTvOS = deviceScreen?.platform?.toUpperCase() === "TVOS";

  const onTapGesture = async (x: number, y: number) => {
    if (isTvOS) {
      await runCommandYaml('- pressKey: "Remote Dpad Center"');
    } else {
      await runCommandYaml(`- tapOn:
    point: "${Math.round(100 * x)}%,${Math.round(100 * y)}%"`);
    }
  };

  const onSwipeGesture = async (
    startX: number,
    startY: number,
    endX: number,
    endY: number,
    duration: number
  ) => {
    if (isTvOS) {
      const deltaX = endX - startX;
      const deltaY = endY - startY;
      const key =
        Math.abs(deltaY) > Math.abs(deltaX)
          ? deltaY < 0
            ? "Remote Dpad Up"
            : "Remote Dpad Down"
          : deltaX > 0
            ? "Remote Dpad Right"
            : "Remote Dpad Left";
      await runCommandYaml(`- pressKey: "${key}"`);
    } else {
      const startXPercent = Math.round(startX * 100);
      const startYPercent = Math.round(startY * 100);
      const endXPercent = Math.round(endX * 100);
      const endYPercent = Math.round(endY * 100);
      await runCommandYaml(`
      swipe:
        start: "${startXPercent}%,${startYPercent}%"
        end: "${endXPercent}%,${endYPercent}%"
        duration: ${Math.round(duration)}
    `);
    }
  };

  return (
    <GestureDiv
      className={clsx(
        "rounded-lg overflow-hidden w-full",
        enableGestureControl ? "border-2 box-content border-pink-500" : ""
      )}
      style={{
        aspectRatio: deviceScreen
          ? deviceScreen.width / deviceScreen.height
          : 1,
      }}
      onTap={onTapGesture}
      onSwipe={onSwipeGesture}
      gesturesEnabled={enableGestureControl ? metaKeyDown : false}
    >
      <AnnotatedScreenshot
        annotationsEnabled={enableGestureControl ? !metaKeyDown : true}
      />
    </GestureDiv>
  );
}

type GestureEvent = {
  x: number;
  y: number;
  timestamp: number;
};

const createGestureEvent = (
  e: React.MouseEvent<HTMLDivElement, MouseEvent>
): GestureEvent => {
  const { top, left } = e.currentTarget.getBoundingClientRect();
  return {
    x: e.pageX - left,
    y: e.pageY - top,
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
