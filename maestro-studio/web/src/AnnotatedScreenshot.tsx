import { DeviceScreen, UIElement } from './models';
import React, { CSSProperties, ReactNode, useEffect, useRef } from 'react';
import useMouse, { MousePosition } from '@react-hook/mouse-position';

type AnnotationState = 'default' | 'hidden' | 'hovered' | 'selected'

const Annotation = ({element, deviceWidth, deviceHeight, state, onClick}: {
  element: UIElement
  deviceWidth: number
  deviceHeight: number
  state: AnnotationState
  onClick: () => void
}) => {
  if (!element.bounds || state === 'hidden') return null
  const {x, y, width, height} = element.bounds
  const l = `${x / deviceWidth * 100}%`
  const t = `${y / deviceHeight * 100}%`
  const w = `${width / deviceWidth * 100}%`
  const h = `${height / deviceHeight * 100}%`

  let className = "border border-dashed border-pink-400"
  let style: CSSProperties = {}
  let overlay: ReactNode = null

  if (state === 'hovered') {
    className = "border-4 border-blue-500 active:active:bg-blue-400/40 z-10"
    style = {
      boxShadow: "0 0 0 9999px rgba(244, 114, 182, 0.4)",
    }
  } else if (state === 'selected') {
    className = "border-4 border-blue-500 z-10"
    style = {
      boxShadow: "0 0 0 9999px rgba(96, 165, 250, 0.4)",
    }
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
          ...style
        }}
        onClick={onClick}
      />
      {overlay}
    </>
  )
}

const pointInBounds = (boundsX: number, boundsY: number, boundsWidth: number, boundsHeight: number, x: number, y: number) => {
  return (x >= boundsX && x <= boundsX + boundsWidth) && (y >= boundsY && y <= boundsY + boundsHeight)
}

const getHoveredElement = (deviceScreen: DeviceScreen, mouse: MousePosition): UIElement | null => {
  const hoveredList = deviceScreen.elements.filter(element => {
    if (!element.bounds) return false
    const { x: boundsX, y: boundsY, width: boundsWidth, height: boundsHeight } = element.bounds
    const { x: mouseX, y: mouseY, elementWidth, elementHeight } = mouse
    if (mouseX === null || mouseY === null || elementWidth === null || elementHeight === null) return false
    return pointInBounds(
      boundsX / deviceScreen.width,
      boundsY / deviceScreen.height,
      boundsWidth / deviceScreen.width,
      boundsHeight / deviceScreen.height,
      mouseX / elementWidth,
      mouseY / elementHeight,
    )
  })
  if (hoveredList.length === 0) return null
  return hoveredList.sort((a, b) => {
    if (!a.bounds && !b.bounds) return 0
    if (!a.bounds) return 1
    if (!b.bounds) return -1
    return a.bounds.width * a.bounds.height - b.bounds.width * b.bounds.height
  })[0]
}

export const AnnotatedScreenshot = ({deviceScreen, selectedElement, onElementSelected, hoveredElement, onElementHovered}: {
  deviceScreen: DeviceScreen
  selectedElement: UIElement | null
  onElementSelected: (element: UIElement | null) => void
  hoveredElement: UIElement | null
  onElementHovered: (element: UIElement | null) => void
}) => {
  const ref = useRef(null)
  const mouse = useMouse(ref)
  const hoveredElementCandidate = getHoveredElement(deviceScreen, mouse)

  useEffect(() => {
    onElementHovered(hoveredElementCandidate)
  }, [onElementHovered, hoveredElementCandidate])

  return (
    <div
      ref={ref}
      className="relative h-full bg-red-100 overflow-clip"
      style={{
        aspectRatio: deviceScreen.width / deviceScreen.height,
      }}
    >
      <img className="h-full" src={deviceScreen.screenshot} alt="screenshot"/>
      {hoveredElement || selectedElement ? null : <div className="absolute inset-0 bg-black opacity-50"/>}
      {deviceScreen.elements.map(element => {
        let state: AnnotationState = 'default'
        if (selectedElement === element) {
          state = 'selected'
        } else if (selectedElement !== null) {
          state = 'hidden'
        } else if (hoveredElement === element) {
          state = 'hovered'
        }
        return (
          <Annotation
            element={element}
            deviceWidth={deviceScreen.width}
            deviceHeight={deviceScreen.height}
            state={state}
            onClick={() => {
              if (selectedElement) {
                onElementSelected(null)
              } else {
                onElementSelected(element)
              }
            }}
          />
        )
      })}
    </div>
  );
}