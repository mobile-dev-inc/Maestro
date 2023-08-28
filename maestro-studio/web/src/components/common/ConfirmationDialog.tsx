import { ReactNode, useState } from "react";
import { Button } from "../design-system/button";
import {
  Dialog,
  DialogTrigger,
  DialogContent,
  DialogHeader,
  DialogDescription,
  DialogTitle,
} from "../design-system/dialog";

export const ConfirmationDialog = ({
  title,
  content,
  mainAction,
  children,
}: {
  title: string;
  content: string;
  mainAction?: () => void;
  children?: ReactNode;
}) => {
  const [isOpen, setIsOpen] = useState<boolean>(false);
  return (
    <Dialog open={isOpen} onOpenChange={(val) => setIsOpen(val)}>
      <DialogTrigger asChild>{children}</DialogTrigger>
      <DialogContent>
        <div className="flex gap-20 p-8 items-stretch">
          <div className="flex-grow min-w-0">
            <DialogHeader className="pb-4">
              <DialogTitle className="text-xl font-medium leading-normal text-gray-800 dark:text-white">
                {title}
              </DialogTitle>
            </DialogHeader>
            <DialogDescription>
              <p className="text-base mb-6">{content}</p>
              <div className="modal-footer flex flex-shrink-0 flex-wrap items-center justify-end rounded-b-md gap-2">
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
                    mainAction && mainAction();
                  }}
                  type="button"
                  size="md"
                  variant="primary-red"
                >
                  Confirm
                </Button>
              </div>
            </DialogDescription>
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
};
