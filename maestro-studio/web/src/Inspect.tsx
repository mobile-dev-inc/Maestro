import { AnnotatedScreenshot } from './AnnotatedScreenshot';
import React, { useState } from 'react';
import { DeviceScreen, UIElement } from './models';
import PageSwitcher from './PageSwitcher';
import Banner from './Banner';

const Inspect = ({ deviceScreen }: {
  deviceScreen: DeviceScreen
}) => {
  const [hoveredElement, setHoveredElement] = useState<UIElement | null>(null)
  const [selectedElement, setSelectedElement] = useState<UIElement | null>(null)
  
  const banner = selectedElement ? (
    <Banner
      left={selectedElement.text || ''}
      right={selectedElement.resourceId || ''}
      onClose={() => setSelectedElement(null)}
    />
  ) : null

  const searchPage = (
    <div
      className="font-bold text-lg"
    >
      UI Elements
    </div>
  )

  const detailsPage = selectedElement ? (
    <div
      className="font-bold"
    >
      Here are some examples of how you can interact with this element:
    </div>
  ) : null;
  
  return (
    <div className="App flex h-full gap-10 p-10">
      <AnnotatedScreenshot
        deviceScreen={deviceScreen}
        onElementHovered={setHoveredElement}
        hoveredElement={hoveredElement}
        onElementSelected={setSelectedElement}
        selectedElement={selectedElement}
      />
      <PageSwitcher banner={banner}>
        {searchPage}
        {detailsPage}
      </PageSwitcher>
    </div>
  )
}

export default Inspect