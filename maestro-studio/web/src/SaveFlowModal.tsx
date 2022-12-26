import { FormattedFlow } from './models';
import React, { useState } from 'react';
import { motion } from 'framer-motion';
import AutosizingTextArea from './AutosizingTextArea';
import { saveAs } from 'file-saver';

export const SaveFlowModal = ({formattedFlow, onClose}: {
  formattedFlow: FormattedFlow
  onClose: () => void
}) => {
  const [config, setConfig] = useState(formattedFlow.config)
  const [commands, setCommands] = useState(formattedFlow.commands)
  const onSave = () => {
    const content = `${config}\n---\n${commands}`
    saveAs(new Blob([content], {type: 'text/yaml;charset=utf-8'}), "Flow.yaml")
    onClose()
  }
  return (
    <motion.div
      className="fixed w-full h-full p-12 top-0 left-0 flex items-center justify-center bg-slate-900/60"
      onClick={onClose}
    >
      <motion.div
        className="flex flex-col gap-8 h-full p-10 min-w-[70%] min-h-[70%] bg-white rounded-lg"
        initial={{ scale: .97, opacity: 0 }}
        animate={{ scale: 1, opacity: 1 }}
        transition={{ ease: 'easeOut', duration: .1 }}
        onClick={e => e.stopPropagation()}
      >
        <span
          className="text-lg font-bold"
        >
          Save Flow to File
        </span>
        <div
          className="flex flex-col h-full border rounded bg-red-100"
        >
          <AutosizingTextArea
            className="resize-none p-4 pr-16 overflow-y-scroll overflow-hidden bg-gray-50 font-mono cursor-text outline-none border border-transparent border-b-slate-200 focus:border focus:border-slate-400"
            value={config}
            setValue={setConfig}
          />
          <textarea
            className="resize-none p-4 pr-16 h-full overflow-y-scroll overflow-hidden bg-gray-50 font-mono cursor-text outline-none border border-transparent focus:border focus:border-slate-400"
            value={commands}
            onChange={e => setCommands(e.currentTarget.value)}
          />
        </div>
        <div
          className="flex justify-end gap-2"
        >
          <button
            className="px-4 py-1 border rounded cursor-default hover:bg-slate-100 active:bg-slate-200"
            onClick={onClose}
          >
            Cancel
          </button>
          <button
            className="px-4 py-1 border bg-blue-700 text-white rounded cursor-default hover:bg-blue-800 active:bg-blue-900"
            onClick={onSave}
          >
            Save
          </button>
        </div>
      </motion.div>
    </motion.div>
  )
}