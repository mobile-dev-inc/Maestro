import { AnimatePresence, motion } from "framer-motion";
import { ReactElement } from "react";

const PageSwitcher = ({
  children,
  banner,
}: {
  banner: ReactElement | null;
  children: [ReactElement, ReactElement | null];
}) => {
  const animationDuration = 0.08;
  return (
    <motion.div className="flex flex-col gap-4 w-full h-full basis-0 flex-grow overflow-hidden">
      {banner ? (
        <motion.div
          initial={{ opacity: 0, scale: 0.9 }}
          animate={{ opacity: 1, scale: 1 }}
          transition={{ duration: animationDuration }}
        >
          {banner}
        </motion.div>
      ) : null}
      <motion.div
        className="w-full h-full relative rounded-lg border dark:border-slate-600 dark:text-white bg-white overflow-clip"
        layout="position"
        transition={{ ease: "easeOut", duration: animationDuration }}
      >
        <motion.div className="absolute p-8 w-full h-full bg-white dark:bg-slate-700">
          {children[0]}
        </motion.div>
        <AnimatePresence>
          {children[1] ? (
            <motion.div
              className="absolute p-8 w-full h-full bg-white dark:bg-slate-700 dark:text-white"
              initial={{ opacity: 0, translateY: "10px" }}
              animate={{ opacity: 1, translateY: 0 }}
              exit={{ opacity: 0, translateY: "10px" }}
              transition={{ ease: "easeOut", duration: animationDuration }}
            >
              {children[1]}
            </motion.div>
          ) : null}
        </AnimatePresence>
      </motion.div>
    </motion.div>
  );
};

export default PageSwitcher;
