import InteractableDevice from './InteractableDevice';
import ReplView from './ReplView';
import React, { useEffect, useState } from 'react';
import { DeviceScreen, UIElement } from './models';
import { API, wait } from './api';
import Examples from './Examples';
import { Modal } from './Modal';

const CloseIcon = () => {
  return (
    <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" strokeWidth={1.5} stroke="currentColor" className="w-6 h-6">
      <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
    </svg>
  )
}

const InspectModal = ({deviceScreen, element, onClose}: {
  deviceScreen: DeviceScreen
  element: UIElement
  onClose: () => void
}) => {
  return (
    <Modal onClose={onClose}>
      <div className="flex border-b border-b-slate-200 items-center p-4 gap-4">
        <div className="flex justify-center p-2 rounded items-center hover:bg-slate-900/20 active:bg-slate-900/40" onClick={onClose}>
          <CloseIcon />
        </div>
        <span className="font-bold">
          Inspect Element
        </span>
      </div>
      <div
        className="overflow-y-scroll"
      >
        <div className="p-10">
          <Examples deviceScreen={deviceScreen} element={element} />
        </div>
      </div>
    </Modal>
  )
}

const InteractPage = () => {
  const [deviceScreen, setDeviceScreen] = useState<DeviceScreen>()
  const [replError, setReplError] = useState<string | null>(null)
  const [footerHint, setFooterHint] = useState<string | null>()
  const [inspectedElement, setInspectedElement] = useState<UIElement | null>(null)

  useEffect(() => {
    let running = true;
    (async () => {
      while (running) {
        try {
          const deviceScreen = await API.getDeviceScreen(true)
          setDeviceScreen(deviceScreen)
        } catch (e) {
          console.error(e)
          await wait(1000)
        }
      }
    })()
    return () => { running = false }
  }, [setDeviceScreen])

  if (!deviceScreen) return null

  return (
    <div className="flex h-full overflow-hidden">
      <div className="p-12 bg-slate-50">
        <InteractableDevice
          deviceScreen={deviceScreen}
          onHint={setFooterHint}
          onInspectElement={setInspectedElement}
        />
      </div>
      <div className="flex flex-col flex-1 h-full overflow-hidden border-l shadow-xl">
        <span className="px-6 py-4 font-bold font-mono border-b text-lg cursor-default">$ maestro studio</span>
        <div className="p-6 h-full overflow-hidden">
          <ReplView onError={setReplError}/>
        </div>
        <div
          className="flex items-center gap-1 justify-center px-3 bg-slate-50 border-t h-10 text-slate-500 overflow-hidden whitespace-nowrap data-[error]:bg-red-100 data-[error]:text-red-800"
          data-error={footerHint ? null : replError}
        >
          {footerHint || replError}
        </div>
      </div>
      {inspectedElement && (
        <InspectModal
          deviceScreen={deviceScreen}
          element={inspectedElement}
          onClose={() => setInspectedElement(null)}
        />
      )}
    </div>
  )
}

export default InteractPage