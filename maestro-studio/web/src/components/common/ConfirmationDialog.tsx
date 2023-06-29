import { useState } from "react";
import { motion } from "framer-motion";
import { Button } from "../design-system/button";

type DialogProps = {
  title: string;
  content: string;
};

export const useConfirmationDialog = (
  onConfirm: () => void
): [() => void, React.FC<DialogProps>] => {
  const [isOpen, setIsOpen] = useState(false);

  const Dialog = ({ title, content }: DialogProps) => {
    if (!isOpen) return null;

    return (
      <div
        className="modal fade fixed bg-black/60 w-full center top-0 left-0 h-full outline-none overflow-x-hidden overflow-y-auto z-50"
        tabIndex={1}
      >
        <motion.div
          className="modal-dialog m-auto mt-20 relative w-1/3"
          animate={{
            opacity: [0, 1],
            transition: {
              duration: 0.1,
            },
          }}
        >
          <div className="modal-content relative flex flex-col w-full pointer-events-auto bg-white dark:bg-slate-800 dark:text-white rounded-md">
            <div className="modal-header flex flex-shrink-0 items-center justify-between px-6 pt-4 rounded-t-md">
              <h1 className="text-xl font-medium leading-normal text-gray-800 dark:text-white">
                {title}
              </h1>
            </div>
            <div className="modal-body relative py-2 px-6 text-sm">
              {content}
            </div>
            <div className="modal-footer flex flex-shrink-0 flex-wrap items-center justify-end py-4 px-6 pb-6 rounded-b-md gap-2">
              <Button
                onClick={() => setIsOpen(false)}
                type="button"
                size="md"
                variant="tertiary"
              >
                Cancel
              </Button>
              <Button
                onClick={() => {
                  setIsOpen(false);
                  onConfirm();
                }}
                type="button"
                size="md"
                variant="primary-red"
              >
                Confirm
              </Button>
            </div>
          </div>
        </motion.div>
      </div>
    );
  };

  return [() => setIsOpen(true), Dialog];
};
