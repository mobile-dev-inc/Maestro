import Inspect from './Inspect';
import React, { useCallback, useEffect, useState } from 'react';
import { DeviceScreen } from './models';
import { motion } from 'framer-motion';
import { API } from './api';

const DeprecatedInspectPage = () => {
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
      {deviceScreen ? (
        <Inspect deviceScreen={deviceScreen} refresh={refresh} />
      ): (
        <div className="flex items-center justify-center flex-1 dark:bg-slate-800 dark:text-white">
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

export default DeprecatedInspectPage