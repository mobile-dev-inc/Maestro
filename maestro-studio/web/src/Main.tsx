import Inspect from './Inspect';
import React, { useCallback, useEffect, useState } from 'react';
import { DeviceScreen } from './models';
import { motion } from 'framer-motion';
import { API } from './api';

const RefreshIcon = (props: React.SVGProps<SVGSVGElement>) => {
  return (
    <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" strokeWidth={1.5} stroke="currentColor" className="w-6 h-6" {...props}>
      <path strokeLinecap="round" strokeLinejoin="round" d="M16.023 9.348h4.992v-.001M2.985 19.644v-4.992m0 0h4.992m-4.993 0l3.181 3.183a8.25 8.25 0 0013.803-3.7M4.031 9.865a8.25 8.25 0 0113.803-3.7l3.181 3.182m0-4.991v4.99" />
    </svg>
  )
}

const Header = ({onRefresh}: {
  onRefresh: () => void
}) => {
  return (
    <div
      className="px-5 py-3 border-b flex justify-between items-center"
    >
      <span className="font-bold font-mono cursor-default">$ maestro studio</span>
      <button
        className="relative pl-10 cursor-default border border-slate-400 rounded px-4 py-1 hover:bg-slate-100 active:bg-slate-200"
        onClick={onRefresh}
      >
        Refresh
        <div className="absolute flex pl-3 top-0 left-0 h-full items-center">
          <RefreshIcon className="w-5 top-0"/>
        </div>
      </button>
    </div>
  )
}

const Main = () => {
  const [deviceScreen, setDeviceScreen] = useState<DeviceScreen>()
  const [error, setError] = useState<string>()

  const refresh = useCallback(async () => {
    setError(undefined)
    setDeviceScreen(undefined)
    try {
      const deviceScreen = await API.getDeviceScreen()
      setDeviceScreen(deviceScreen)
    } catch (e) {
      console.error(e)
      setError("An error occurred. Please try refreshing.")
    }
  }, [setError, setDeviceScreen])

  useEffect(() => {
    refresh()
  }, [refresh])

  return (
    <div className="flex flex-col h-full overflow-hidden">
      <Header onRefresh={refresh}/>
      {deviceScreen ? (
        <Inspect deviceScreen={deviceScreen} />
      ): (
        <div className="flex items-center justify-center flex-1">
          {error ? (
            <div className="text-xl">{error}</div>
          ) : (
            <motion.div
              className="text-xl"
              animate={{
                opacity: [0, 1, 0],
                transition: {
                  ease: 'easeOut',
                  duration: 2,
                  repeat: Infinity,
                },
              }}
            >
              Loading
            </motion.div>
          )}
        </div>
      )}
    </div>
  )
}

export default Main