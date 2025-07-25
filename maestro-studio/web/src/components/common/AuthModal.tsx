import { ReactNode, useState } from "react";
import {
  Dialog,
  DialogTrigger,
  DialogContent,
  DialogHeader,
  DialogDescription,
  DialogTitle,
} from "../design-system/dialog";
import { Button } from "../design-system/button";
import copy from "copy-to-clipboard";

export default function AuthModal({
  open,
  onOpenChange,
  children,
}: {
  open?: boolean;
  onOpenChange?: (val: boolean) => void;
  children?: ReactNode;
}) {
  const [isBeingCopied, setIsBeingCopied] = useState<boolean>(false);

  const copyCommand = (command: string) => {
    copy(command);
    setIsBeingCopied(true);
    setTimeout(() => {
      setIsBeingCopied(false);
    }, 1000);
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogTrigger asChild>{children}</DialogTrigger>
      <DialogContent>
        <div className="flex gap-20 p-8 items-stretch">
          <div className="flex-grow min-w-0">
            <DialogHeader className="pb-4">
              <DialogTitle className="text-left text-3xl">
                Authentication Required
              </DialogTitle>
            </DialogHeader>
            <DialogDescription>
              <p className="text-base mb-6">
                To access this feature, you must be authenticated with Maestro.
              </p>
              <div className="bg-gray-200 dark:bg-gray-800 px-3 gap-2 py-2 rounded-lg flex">
                <p className="font-mono font-bold flex-grow py-1.5">
                  maestro login
                </p>
                {isBeingCopied ? (
                  <Button
                    variant="primary-green"
                    size="sm"
                    icon="RiCheckLine"
                  />
                ) : (
                  <Button
                    onClick={() => {
                      copyCommand("maestro login");
                    }}
                    variant="quaternary"
                    size="sm"
                    icon="RiFileCopyLine"
                  />
                )}
              </div>
            </DialogDescription>
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
}
