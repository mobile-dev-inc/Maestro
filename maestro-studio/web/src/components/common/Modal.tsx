import React, { ReactNode } from "react";
import { motion } from "framer-motion";

export const Modal = ({
  onClose,
  children,
}: {
  onClose: () => void;
  children: ReactNode;
}) => {
  return (
    <motion.div
      className="fixed w-full h-full p-12 top-0 left-0 flex items-center justify-center bg-slate-900/60 dark:bg-white/20 z-50"
      onClick={onClose}
    >
      <motion.div
        className="flex flex-col h-full min-w-[70%] min-h-[70%] max-w-[1000px] rounded-lg"
        initial={{ scale: 0.97, opacity: 0 }}
        animate={{ scale: 1, opacity: 1 }}
        transition={{ ease: "easeOut", duration: 0.1 }}
        onClick={(e) => e.stopPropagation()}
      >
        {children}
      </motion.div>
    </motion.div>
  );
};
