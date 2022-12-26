import InteractableDevice from './InteractableDevice';
import ReplView from './ReplView';
import React, { useEffect, useState } from 'react';
import { DeviceScreen } from './models';
import { API, wait } from './api';

const InteractPage = () => {
  const [deviceScreen, setDeviceScreen] = useState<DeviceScreen>()
  const [replError, setReplError] = useState<string | null>(null)
  const [footerHint, setFooterHint] = useState<string | null>()

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
      <div className="p-12">
        <InteractableDevice
          deviceScreen={deviceScreen}
          onHint={setFooterHint}
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
    </div>
  )
}

export default InteractPage