import React, { MouseEventHandler, useEffect, useState } from 'react';
import { DeviceScreen, DivProps, UIElement } from './models';
import { API } from './api';
import { AnnotatedScreenshot } from './AnnotatedScreenshot';
import { MousePosition } from '@react-hook/mouse-position';

type GestureEvent = {
  x: number
  y: number
  timestamp: number
}

const createGestureEvent = (e: React.MouseEvent<HTMLDivElement, MouseEvent>): GestureEvent => {
  const {offsetLeft, offsetTop} = e.currentTarget
  return {
    x: e.pageX - offsetLeft,
    y: e.pageY - offsetTop,
    timestamp: e.timeStamp,
  }
}

const GestureDiv = ({onTap, onSwipe, gesturesEnabled = true, ...rest}: {
  onTap: (x: number, y: number) => void
  onSwipe: (startX: number, startY: number, endX: number, endY: number, duration: number) => void
  gesturesEnabled?: boolean
} & DivProps) => {
  const [start, setStart] = useState<GestureEvent>()

  const onStart: MouseEventHandler<HTMLDivElement> = e => {
    if (!gesturesEnabled) return
    setStart(createGestureEvent(e))
  }

  const onEnd: MouseEventHandler<HTMLDivElement> = e => {
    if (!gesturesEnabled || !start) return
    const end = createGestureEvent(e)

    const {width: clientWidth, height: clientHeight} = e.currentTarget.getBoundingClientRect()

    const duration = end.timestamp - start.timestamp
    const distance = Math.hypot(end.x - start.x, end.y - start.y)

    if (duration < 100 || distance < 10) {
      onTap(start.x / clientWidth, start.y / clientHeight)
    } else {
      onSwipe(start.x / clientWidth, start.y / clientHeight, end.x / clientWidth, end.y / clientHeight, duration)
    }
  }

  const onCancel = () => {
    setStart(undefined)
  }

  return (
    <div
      {...rest}
      onMouseDown={onStart}
      onMouseUp={onEnd}
      onMouseLeave={onCancel}
    />
  )
}

const useMetaKeyDown = () => {
  const [metaKeyDown, setMetaKeyDown] = useState(false);

  function downHandler({key}: KeyboardEvent) {
    if (key === 'Meta') {
      setMetaKeyDown(true);
    }
  }

  function upHandler({key}: KeyboardEvent) {
    if (key === 'Meta') {
      setMetaKeyDown(false);
    }
  }

  useEffect(() => {
    window.addEventListener('keydown', downHandler);
    window.addEventListener('keyup', upHandler);
    return () => {
      window.removeEventListener('keydown', downHandler);
      window.removeEventListener('keyup', upHandler);
    };
  }, []);

  return metaKeyDown
}

const toPercent = (n: number, total: number) => `${Math.round((100 * n / total))}%`

const InteractableDevice = ({deviceScreen, onHint, onInspectElement}: {
  deviceScreen: DeviceScreen
  onHint: (hint: string | null) => void
  onInspectElement: (element: UIElement) => void
}) => {
  const [hoveredElementId, setHoveredElementId] = useState<string | null>(null)
  const metaKeyDown = useMetaKeyDown()

  const hoveredElement = deviceScreen.elements.find(e => e.id === hoveredElementId) || null

  const onTapGesture = (x: number, y: number) => {
    API.repl.runCommand(`
      tapOn:
        point: "${Math.round(100*x)}%,${Math.round(100*y)}%"
    `)
  }

  const onSwipeGesture = (startX: number, startY: number, endX: number, endY: number, duration: number) => {
    const startXpx = Math.round(startX * deviceScreen.width)
    const startYpx = Math.round(startY * deviceScreen.height)
    const endXpx = Math.round(endX * deviceScreen.width)
    const endYpx = Math.round(endY * deviceScreen.height)
    API.repl.runCommand(`
      swipe:
        start: "${startXpx}, ${startYpx}"
        end: "${endXpx}, ${endYpx}"
        duration: ${Math.round(duration)}
    `)
  }

  const onElementTap = (e: UIElement | null) => {
    if (!e) return
    if (!e.bounds) return
    if (e.resourceId) {
      const index = typeof e.resourceIdIndex === 'number' ? `index: ${e.resourceIdIndex}` : ''
      API.repl.runCommand(`
        tapOn:
          id: "${e.resourceId}"
          ${index}
      `)
    } else if (e.text) {
      if (typeof e.textIndex === 'number') {
        API.repl.runCommand(`
          tapOn:
            text: "${e.text}"
            index: ${e.textIndex}
        `)
      } else {
        API.repl.runCommand(`tapOn: ${e.text}`)
      }
    } else {
      const cx = toPercent(e.bounds.x + e.bounds.width / 2, deviceScreen.width)
      const cy = toPercent(e.bounds.y + e.bounds.height / 2, deviceScreen.height)
      API.repl.runCommand(`
        tapOn:
          point: "${cx},${cy}"
      `)
    }
  }

  const getMouseHint = (mouse: MousePosition): string | null => {
    if (
      typeof mouse.x !== 'number'
      || typeof mouse.y !== 'number'
      || typeof mouse.elementWidth !== 'number'
      || typeof mouse.elementHeight !== 'number'
    ) {
      return null
    }
    const x = toPercent(mouse.x, mouse.elementWidth)
    const y = toPercent(mouse.y, mouse.elementHeight)
    return `${x}, ${y}`
  }

  const getElementHint = (element: UIElement): string => {
    if (element.resourceId) return element.resourceId
    if (element.text) return element.text
    if (!element.bounds) return ''
    const cx = toPercent(element.bounds.x + element.bounds.width / 2, deviceScreen.width)
    const cy = toPercent(element.bounds.y + element.bounds.height / 2, deviceScreen.height)
    return `${cx}, ${cy}`
  }

  const onHover = (element: UIElement | null, mouse: MousePosition | null) => {
    const mouseHint = mouse == null ? null : getMouseHint(mouse)
    const elementHint = element == null ? null : getElementHint(element)
    onHint(elementHint || mouseHint)
    setHoveredElementId(element?.id || null)
  }

  return (
    <GestureDiv
      className="h-full border-2 border-pink-500 rounded-lg overflow-hidden"
      style={{
        aspectRatio: deviceScreen.width / deviceScreen.height,
      }}
      onTap={onTapGesture}
      onSwipe={onSwipeGesture}
      gesturesEnabled={metaKeyDown}
    >
      <AnnotatedScreenshot
        deviceScreen={deviceScreen}
        selectedElement={null}
        onElementSelected={onElementTap}
        hoveredElement={hoveredElement}
        onHover={onHover}
        onInspect={onInspectElement}
        annotationsEnabled={!metaKeyDown}
      />
    </GestureDiv>
  )
}

export default InteractableDevice