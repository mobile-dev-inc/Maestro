import { AnnotatedScreenshot } from './AnnotatedScreenshot';
import React, { useState } from 'react';
import { DeviceScreen, UIElement } from './models';

const Inspect = ({ deviceScreen }: {
  deviceScreen: DeviceScreen
}) => {
  const [hoveredElement, setHoveredElement] = useState<UIElement | null>(null)
  const [selectedElement, setSelectedElement] = useState<UIElement | null>(null)
  return (
    <div className="App flex h-full">
      <AnnotatedScreenshot
        deviceScreen={deviceScreen}
        onElementHovered={setHoveredElement}
        hoveredElement={hoveredElement}
        onElementSelected={setSelectedElement}
        selectedElement={selectedElement}
      />
    </div>
  )
}

export default Inspect