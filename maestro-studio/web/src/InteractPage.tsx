import InteractableDevice from './InteractableDevice';
import ReplView from './ReplView';
import React, { useEffect, useState } from 'react';
import { DeviceScreen } from './models';
import { API, wait } from './api';
import { ActionModal } from './ActionModal';
import { ThemeToggle } from './theme';

const InteractPage = () => {
  const [deviceScreen, setDeviceScreen] = useState<DeviceScreen>()
  const [replError, setReplError] = useState<string | null>(null)
  const [input, setInput] = useState('')
  const [footerHint, setFooterHint] = useState<string | null>()
  const [inspectedElementId, setInspectedElementId] = useState<string | null>(null)
  const inspectedElement = deviceScreen?.elements?.find(e => e.id === inspectedElementId) || null

  useEffect(() => {
    let running = true;
    (async () => {
      while (running) {
        try {
          const deviceScreen = await API.getDeviceScreen()
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
      <div className="p-12 bg-slate-50 dark:bg-slate-700">
        <InteractableDevice
          deviceScreen={deviceScreen}
          onHint={setFooterHint}
          inspectedElement={inspectedElement}
          onInspectElement={e => setInspectedElementId(e?.id || null)}
        />
      </div>
      <div className="flex flex-col flex-1 h-full overflow-hidden border-l dark:border-slate-600 shadow-xl relative dark:bg-slate-800 dark:text-white">
        <div className="flex flex-row items-center spacing-x-8 border-b dark:border-slate-600 pr-6">
          <span className="px-6 py-4 grow font-bold font-mono text-lg cursor-default dark:text-white">$ maestro studio</span>
          <ThemeToggle />
        </div>
        <div className="p-6 h-full overflow-hidden">
          <ReplView
            input={input}
            onInput={setInput}
            onError={setReplError}
          />
        </div>
        <div
          className="flex items-center gap-1 justify-center px-3 bg-slate-50 dark:bg-slate-800 dark:text-white border-t dark:border-slate-600 h-auto text-slate-500 overflow-hidden data-[error]:bg-red-100 data-[error]:text-red-800 data-[error]:p-4"
          data-error={footerHint ? null : replError}
        >
          {footerHint || replError}
        </div>
        {inspectedElement && (
          <ActionModal
            deviceWidth={deviceScreen.width}
            deviceHeight={deviceScreen.height}
            uiElement={inspectedElement}
            onEdit={example => {
              if (example.status === 'unavailable') return
              setInput(example.content.trim())
              setInspectedElementId(null)
            }}
            onRun={example => {
              if (example.status === 'unavailable') return
              API.repl.runCommand(example.content)
              setInspectedElementId(null)
            }}
            onClose={() => setInspectedElementId(null)}
          />
        )}
      </div>
    </div>
  )
}

export default InteractPage