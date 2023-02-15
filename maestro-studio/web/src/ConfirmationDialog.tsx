import { useState } from "react"
import { motion } from 'framer-motion';

type DialogProps = {
  title: string,
  content: string
}

export const useConfirmationDialog = (
  onConfirm: () => void,
): [() => void, React.FC<DialogProps>] => {
  const [isOpen, setIsOpen] = useState(false)

  const Dialog = ({ title, content }: DialogProps) => {
    if (!isOpen) return null;

    return (
      <div className="modal fade fixed bg-black/60 w-full center top-0 left-0 h-full outline-none overflow-x-hidden overflow-y-auto"
        tabIndex={1}>
        <motion.div
          className="modal-dialog m-auto mt-20 relative w-1/3"
          animate={{
            opacity: [0, 1],
            transition: {
              duration: 0.1,
            },
          }}
        >
          <div
            className="modal-content relative flex flex-col w-full pointer-events-auto bg-white rounded-md">
            <div className="modal-header flex flex-shrink-0 items-center justify-between p-4 rounded-t-md">
              <h1 className="text-xl font-medium leading-normal text-gray-800">
                {title}
              </h1>
            </div>
            <div className="modal-body relative py-2 px-4">
              {content}
            </div>
            <div
              className="modal-footer flex flex-shrink-0 flex-wrap items-center justify-end p-4 rounded-b-md">
              <button 
                type="button"
                onClick={() => setIsOpen(false)}
                className="px-4 cursor-pointer py-1 mr-2 border text-black bg-gray-100 text-white rounded cursor-default hover:bg-gray-200 active:bg-gray-300"
              >
                Cancel
              </button>
              <button 
                type="button" 
                onClick={() => {setIsOpen(false); onConfirm()}}
                className="px-4 py-1 cursor-pointer border bg-red-700 text-white rounded cursor-default hover:bg-red-800 active:bg-red-900"
              >
                Confirm
              </button>
            </div>
          </div>
        </motion.div>
      </div>
    )}

  return [() => setIsOpen(true), Dialog]
}