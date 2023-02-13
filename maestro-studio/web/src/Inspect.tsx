import { AnnotatedScreenshot } from './AnnotatedScreenshot';
import React, { useState } from 'react';
import { DeviceScreen, UIElement } from './models';
import PageSwitcher from './PageSwitcher';
import Banner, { ElementLabel } from './Banner';
import ElementSearch from './ElementSearch';
import { motion } from 'framer-motion';
import Examples from './Examples';

const Footer = ({selectedElement, hoveredElement}: {
  selectedElement: UIElement | null
  hoveredElement: UIElement | null
}) => {
  const content = selectedElement ? (
    <span className="whitespace-nowrap">Click on an example to copy the command</span>
  ) : (
    hoveredElement ? (
      <>
        <ElementLabel text={hoveredElement.text} />
        <div className="flex-1"/>
        <ElementLabel text={hoveredElement.resourceId} />
      </>
    ) : (
      <span className="whitespace-nowrap">Click on an element in the screenshot or search for an element by text or ID</span>
    )
  )
  return (
    <motion.div
      className="flex items-center gap-1 justify-center px-3 bg-slate-600 h-10 text-slate-100"
      initial={{ translateY: '40px' }}
      animate={{ translateY: 0 }}
      transition={{ ease: 'easeOut', duration: .05 }}
    >
      {content}
    </motion.div>
  )
}

const Inspect = ({ deviceScreen }: {
  deviceScreen: DeviceScreen
}) => {
  const [hoveredElementId, setHoveredElementId] = useState<string | null>(null)
  const [selectedElementId, setSelectedElementId] = useState<string | null>(null)

  const hoveredElement = deviceScreen.elements.find(e => e.id === hoveredElementId) || null
  const selectedElement = deviceScreen.elements.find(e => e.id === selectedElementId) || null

  const banner = selectedElement ? (
    <Banner
      left={selectedElement.text}
      right={selectedElement.resourceId}
      onClose={() => setSelectedElementId(null)}
    />
  ) : null

  const detailsPage = selectedElement ? (
    <Examples deviceScreen={deviceScreen} element={selectedElement}/>
  ) : null;
  
  return (
    <motion.div
      className="flex flex-col overflow-hidden justify-end h-full"
    >
      <motion.div
        initial={{ scale: .97, opacity: 0 }}
        animate={{ scale: 1, opacity: 1 }}
        transition={{ ease: 'easeOut', duration: .1 }}
        className="flex gap-10 p-10 overflow-hidden h-full"
      >
        <AnnotatedScreenshot
          deviceScreen={deviceScreen}
          onHover={e => setHoveredElementId(e?.id || null)}
          hoveredElement={hoveredElement}
          onElementSelected={e => setSelectedElementId(e?.id || null)}
          selectedElement={selectedElement}
        />
        <PageSwitcher banner={banner}>
          <ElementSearch
            deviceScreen={deviceScreen}
            onElementHovered={e => setHoveredElementId(e?.id || null)}
            hoveredElement={hoveredElement}
            onElementSelected={e => setSelectedElementId(e?.id || null)}
          />
          {detailsPage}
        </PageSwitcher>
      </motion.div>
      <Footer
        selectedElement={selectedElement}
        hoveredElement={hoveredElement}
      />
    </motion.div>
  )
}

export default Inspect